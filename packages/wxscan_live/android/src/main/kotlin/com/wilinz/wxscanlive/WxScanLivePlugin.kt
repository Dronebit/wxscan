package com.wilinz.wxscanlive

import android.Manifest
import android.app.Activity
import com.wilinz.wxscanlive.core.NativeScanner
import android.content.ComponentCallbacks
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.content.res.Configuration
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.ByteArrayOutputStream
import androidx.camera.core.Camera
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.view.TextureRegistry
import java.util.concurrent.Executors

/**
 * The preview goes through CameraX's Preview use case into a Flutter
 * SurfaceTexture, which stays on the GPU and costs no CPU per frame; scanning
 * goes through ImageAnalysis, whose Y plane reaches Rust over JNI without
 * passing through Dart.
 *
 * **Huawei 0x0 bug — Variant A (MAXIMUM YUV).**
 *
 * On some Huawei EMUI builds (STG-LX2 / P Smart 5G, Android 12, EMUI 12,
 * confirmed on the device this was written against) the analysis stream
 * receives zero-sized ImageProxy objects forever, at every preview-aligned
 * resolution (720p, 1080p, 4K), every output format and every backpressure
 * strategy. Preview renders fine, the camera is otherwise healthy, and the
 * device's capabilities include LEVEL_3 with YUV_REPROCESSING — so the HAL
 * can produce YUV in principle.
 *
 * The reason is the LEVEL_3 guaranteed-configuration table:
 *
 *     PRIV PREVIEW | PRIV 640x480 | YUV MAXIMUM | RAW MAXIMUM
 *
 * For a combination with Preview, the only guaranteed YUV size is MAXIMUM
 * (the largest YUV size the camera supports, 4096x2160 on this device).
 * Anything smaller — 1280x720, 1920x1080, 1440x1080, 960x720 — is *outside*
 * the guarantee. Most HALs are lenient and hand back the smaller YUV frame
 * anyway; this Huawei is not, and answers with a zero-sized buffer rather
 * than refusing the session. So the analyser asks for MAXIMUM YUV by name:
 * it is bigger and slower, but it is the one size the specification says
 * must work, and on this device it does.
 *
 * The preview keeps its own 16:9 request, which is what the UI was laid out
 * for. The two sizes need not match: CameraX negotiates them independently,
 * and the scan path never sees the preview's pixels.
 *
 * [kickAnalysisStream] is retained as a secondary path. On devices where
 * even the guaranteed MAXIMUM is not delivered (rare — observed on a
 * different Huawei model, not this one), sending a harmless control request
 * wakes the stream up, because the HAL does not begin streaming to the
 * analysis Surface until some CaptureRequest parameter changes.
 */
class WxScanLivePlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler, ActivityAware, LifecycleOwner {

    companion object {
        private const val TAG = "wxscan_live"

        /** Default preview resolution, in sensor orientation, so landscape. */
        private const val ANALYSIS_WIDTH = 1280
        private const val ANALYSIS_HEIGHT = 720

        /**
         * The YUV size the analyser asks for, regardless of [shortSide].
         *
         * LEVEL_3 guarantees exactly one YUV size in combination with
         * Preview, and that size is MAXIMUM. On the STG-LX2, MAXIMUM is
         * 4096x2160 (the largest entry in its YUV_420_888 output-size list),
         * and this is the only value the analyser receives non-zero frames
         * for. Asking for anything smaller returns a zero-sized ImageProxy
         * with no error — the HAL accepts the session and then never fills
         * the buffer.
         *
         * If a future device reports a different MAXIMUM, replace this
         * constant with that device's value. The lookup is not done at
         * runtime because the value is device-specific and the fallback
         * (CameraX picks the closest supported size) is the buggy path we
         * are trying to avoid.
         */
        private val ANALYSIS_MAX_SIZE = Size(4096, 2160)

        /** Short-side pixels to a target size, in landscape. 0 means the
         *  highest step, asked for here as 4K; CameraX falls back on its own
         *  if that is not available. */
        private fun targetSize(shortSide: Int): Size = when (shortSide) {
            720 -> Size(1280, 720)
            1080 -> Size(1920, 1080)
            0 -> Size(3840, 2160)
            else -> Size(shortSide * 16 / 9, shortSide)
        }

        private const val ROTATION_CHECK_DELAY_MS = 120L
        private const val SETTLE_CHECKS = 3

        /** Milliseconds between kicks, so a storm of empty frames does not
         *  turn into a storm of control requests. */
        private const val KICK_COOLDOWN_MS = 1000L
    }

    @Volatile private var scannerHandle: Long = 0L
    @Volatile private var modelsLoaded = false

    private lateinit var appContext: Context
    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private lateinit var sizeChannel: EventChannel
    private lateinit var textureRegistry: TextureRegistry

    private var activity: Activity? = null
    private val main = Handler(Looper.getMainLooper())

    private val camExec = Executors.newSingleThreadExecutor()
    private val worker = Executors.newSingleThreadExecutor()

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private var scanSink: EventChannel.EventSink? = null
    private var textureEntry: TextureRegistry.SurfaceTextureEntry? = null
    private var starting = false

    private var sessionId = 0L
    private var lastSessionId = 0L
    private var boundShortSide = 0
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewSurface: Surface? = null
    private var preview: Preview? = null
    private var analysis: ImageAnalysis? = null

    @Volatile private var lastNv21: ByteArray? = null
    @Volatile private var lastNv21W = 0
    @Volatile private var lastNv21H = 0
    @Volatile private var lastNv21Rot = 0

    @Volatile private var bufW = ANALYSIS_WIDTH
    @Volatile private var bufH = ANALYSIS_HEIGHT

    @Volatile private var shortSide = ANALYSIS_HEIGHT
    @Volatile private var previewW = ANALYSIS_HEIGHT
    @Volatile private var previewH = ANALYSIS_WIDTH

    @Volatile private var previewRotation = 90
    private var orientationListener: OrientationEventListener? = null
    private var configCallback: ComponentCallbacks? = null
    private var lastTargetRotation = Surface.ROTATION_0
    private var settleChecksLeft = 0

    @Volatile private var displayRotationDegrees = 0

    @Volatile private var busy = false
    @Volatile private var scanning = true
    @Volatile private var nativeReady = false

    // Timing stats: one line every 30 frames.
    private var statFrames = 0
    private var statTotalMs = 0L
    private var statMaxMs = 0L

    // Diagnostic counters, surfaced through debugState.
    @Volatile private var analyzedFrames = 0L
    @Volatile private var skippedFrames = 0L
    @Volatile private var emptyFrames = 0L
    @Volatile private var firstNScans = 0

    // Kick bookkeeping.
    @Volatile private var lastKickAt = 0L
    @Volatile private var kickStrategy = 0
    @Volatile private var kickCount = 0

    // ---------- FlutterPlugin ----------
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        appContext = binding.applicationContext
        textureRegistry = binding.textureRegistry
        methodChannel = MethodChannel(binding.binaryMessenger, "wxscan_live")
        methodChannel.setMethodCallHandler(this)
        eventChannel = EventChannel(binding.binaryMessenger, "wxscan_live/scan")
        eventChannel.setStreamHandler(this)
        sizeChannel = EventChannel(binding.binaryMessenger, "wxscan_live/preview_size")
        sizeChannel.setStreamHandler(sizeHandler)
        nativeReady = try {
            val ping = NativeScanner.ping()
            android.util.Log.i(TAG, "NativeScanner.ping() = '$ping'")
            ping == "wxscan"
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "NativeScanner.ping FAILED — library not loaded", e)
            false
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        sizeChannel.setStreamHandler(null)
        teardown()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        }
        worker.shutdown()
        camExec.shutdown()
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) { activity = binding.activity }
    override fun onDetachedFromActivityForConfigChanges() {}
    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) { activity = binding.activity }
    override fun onDetachedFromActivity() { activity = null }

    // ---------- EventChannel ----------
    override fun onListen(arguments: Any?, events: EventChannel.EventSink?) { scanSink = events }
    override fun onCancel(arguments: Any?) { scanSink = null }

    // ---------- MethodChannel ----------
    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "initialize" -> {
                call.argument<Int>("shortSide")?.let { shortSide = it }
                handleInitialize(
                    result,
                    call.argument<ByteArray>("detectModel"),
                    call.argument<ByteArray>("srModel"),
                    call.argument<String>("detectModelPath"),
                    call.argument<String>("srModelPath"),
                    call.argument<Number>("scannerHandle")?.toLong() ?: 0L,
                )
            }
            "setResolution" -> {
                val want = call.argument<Int>("shortSide") ?: ANALYSIS_HEIGHT
                if (want == shortSide || cameraProvider == null) {
                    result.success(null)
                } else {
                    shortSide = want
                    main.post {
                        try {
                            rebindUseCases()
                            result.success(null)
                        } catch (e: Throwable) {
                            result.error("RESOLUTION_ERROR", e.message, null)
                        }
                    }
                }
            }
            "setScanning" -> {
                scanning = call.argument<Boolean>("value") ?: true
                result.success(null)
            }
            "setTorch" -> {
                val on = call.argument<Boolean>("value") ?: false
                try {
                    camera?.cameraControl?.enableTorch(on)
                    result.success(null)
                } catch (e: Throwable) {
                    result.error("TORCH_ERROR", e.message, null)
                }
            }
            "hasTorch" -> result.success(camera?.cameraInfo?.hasFlashUnit() ?: false)
            "grabFrame" -> {
                try { result.success(grabJpeg()) }
                catch (e: Throwable) {
                    android.util.Log.e(TAG, "grabFrame err: ${e.message}")
                    result.success(null)
                }
            }
            "setZoom" -> {
                val want = (call.argument<Double>("ratio") ?: 1.0).toFloat()
                try {
                    val info = camera?.cameraInfo
                    val state = info?.zoomState?.value
                    if (state == null) {
                        result.success(1.0)
                    } else {
                        val clamped = want.coerceIn(state.minZoomRatio, state.maxZoomRatio)
                        camera?.cameraControl?.setZoomRatio(clamped)
                        result.success(clamped.toDouble())
                    }
                } catch (e: Throwable) {
                    result.error("ZOOM_ERROR", e.message, null)
                }
            }
            "focusAt" -> {
                val x = call.argument<Double>("x") ?: -1.0
                val y = call.argument<Double>("y") ?: -1.0
                result.success(focusAt(x, y))
            }
            "zoomRange" -> {
                val state = camera?.cameraInfo?.zoomState?.value
                result.success(
                    mapOf(
                        "min" to (state?.minZoomRatio ?: 1f).toDouble(),
                        "max" to (state?.maxZoomRatio ?: 1f).toDouble(),
                        "current" to (state?.zoomRatio ?: 1f).toDouble(),
                    )
                )
            }
            "kickStream" -> {
                main.post { kickAnalysisStream(force = true) }
                result.success(null)
            }
            "debugState" -> {
                result.success(
                    mapOf(
                        "nativeReady" to nativeReady,
                        "modelsLoaded" to modelsLoaded,
                        "scannerHandle" to scannerHandle,
                        "busy" to busy,
                        "scanning" to scanning,
                        "analyzedFrames" to analyzedFrames,
                        "skippedFrames" to skippedFrames,
                        "emptyFrames" to emptyFrames,
                        "previewW" to previewW,
                        "previewH" to previewH,
                        "bufW" to bufW,
                        "bufH" to bufH,
                        "shortSide" to shortSide,
                        "starting" to starting,
                        "sessionId" to sessionId,
                        "boundShortSide" to boundShortSide,
                        "hasProvider" to (cameraProvider != null),
                        "hasCamera" to (camera != null),
                        "hasAnalysis" to (analysis != null),
                        "hasPreview" to (preview != null),
                        "lastNv21" to (lastNv21?.size ?: 0),
                        "lastKickAt" to lastKickAt,
                        "kickStrategy" to kickStrategy,
                        "kickCount" to kickCount,
                        "analysisMaxSize" to "${ANALYSIS_MAX_SIZE.width}x${ANALYSIS_MAX_SIZE.height}",
                    )
                )
            }
            "selfTestNative" -> {
                val gray = call.argument<ByteArray>("gray")
                val w = call.argument<Int>("width") ?: 0
                val h = call.argument<Int>("height") ?: 0
                val rot = call.argument<Int>("rotation") ?: 0
                if (gray == null || w <= 0 || h <= 0) {
                    result.error("BAD_ARGS", "missing grayscale data", null); return
                }
                worker.execute {
                    val stride = w + 16
                    val padded = ByteArray(stride * h)
                    for (y in 0 until h) {
                        System.arraycopy(gray, y * w, padded, y * stride, w)
                    }
                    val t0 = android.os.SystemClock.elapsedRealtime()
                    val json = try {
                        NativeScanner.scanFrame(scannerHandle, padded, w, h, stride, rot, false)
                    } catch (e: Throwable) { "ERR ${e.message}" }
                    val dt = android.os.SystemClock.elapsedRealtime() - t0
                    android.util.Log.i(TAG, "selfTestNative rot=$rot ${dt}ms -> $json")
                    main.post { result.success(json) }
                }
            }
            "dispose" -> {
                val session = call.argument<Number>("sessionId")?.toLong() ?: 0L
                if (session == 0L || session == sessionId) teardown()
                result.success(null)
            }
            else -> result.notImplemented()
        }
    }

    private fun ensureScanner(
        detect: ByteArray?,
        sr: ByteArray?,
        detectPath: String?,
        srPath: String?,
        borrowed: Long,
    ) {
        releaseScanner()

        if (borrowed != 0L) {
            scannerHandle = try {
                NativeScanner.retain(borrowed)
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "scanner retain FAILED borrowed=$borrowed", e)
                0L
            }
            if (scannerHandle != 0L) {
                modelsLoaded = try {
                    NativeScanner.hasDetector(scannerHandle)
                } catch (_: Throwable) { false }
                android.util.Log.i(
                    TAG,
                    "scanner borrowed: handle=$scannerHandle modelsLoaded=$modelsLoaded",
                )
                return
            }
        }

        val empty = ByteArray(0)
        val fromPaths = detectPath != null || srPath != null
        scannerHandle = try {
            val h = if (fromPaths) NativeScanner.createFromPaths(detectPath, srPath)
                    else NativeScanner.create(detect ?: empty, sr ?: empty)
            android.util.Log.i(
                TAG,
                "scanner created: handle=$h paths=$fromPaths " +
                "detectPath=$detectPath srPath=$srPath",
            )
            h
        } catch (e: Throwable) {
            android.util.Log.e(
                TAG,
                "scanner create FAILED paths=$fromPaths " +
                "detectPath=$detectPath srPath=$srPath",
                e,
            )
            0L
        }
        modelsLoaded = scannerHandle != 0L && (detect != null || detectPath != null)
        if (scannerHandle == 0L) {
            scannerHandle = try {
                val h = NativeScanner.create(empty, empty)
                android.util.Log.w(TAG, "scanner fallback (no weights): handle=$h")
                h
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "scanner fallback FAILED", e)
                0L
            }
        }
    }

    private fun releaseScanner() {
        if (scannerHandle == 0L) return
        val h = scannerHandle
        scannerHandle = 0L
        modelsLoaded = false
        worker.execute { try { NativeScanner.release(h) } catch (_: Throwable) {} }
    }

    private fun handleInitialize(
        result: MethodChannel.Result,
        detect: ByteArray?,
        sr: ByteArray?,
        detectPath: String?,
        srPath: String?,
        borrowed: Long,
    ) {
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            activity?.requestPermissions(arrayOf(Manifest.permission.CAMERA), 0x0C0DE)
            result.error(
                "NO_PERMISSION",
                "camera permission not granted; a request was sent, try again once it is",
                null,
            )
            return
        }
        main.post { bindCamera(result, detect, sr, detectPath, srPath, borrowed) }
    }

    private fun infoMap(textureId: Long): Map<String, Any> = mapOf(
        "textureId" to textureId,
        "sessionId" to sessionId,
        "previewWidth" to previewW,
        "previewHeight" to previewH,
        "displayRotation" to displayRotationDegrees,
        "nativeReady" to nativeReady,
        "modelsLoaded" to modelsLoaded
    )

    private fun bindCamera(
        result: MethodChannel.Result,
        detect: ByteArray?,
        sr: ByteArray?,
        detectPath: String?,
        srPath: String?,
        borrowed: Long,
    ) {
        val bound = textureEntry
        if (bound != null && cameraProvider != null) {
            sessionId = ++lastSessionId
            ensureScanner(detect, sr, detectPath, srPath, borrowed)
            if (shortSide != boundShortSide) {
                try { rebindUseCases() } catch (_: Throwable) {}
            }
            result.success(infoMap(bound.id()))
            return
        }
        if (starting) {
            result.error("BUSY", "the camera is already starting", null)
            return
        }
        ensureScanner(detect, sr, detectPath, srPath, borrowed)
        starting = true
        try {
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            textureEntry?.let { try { it.release() } catch (_: Throwable) {} }
            val entry = textureRegistry.createSurfaceTexture()
            textureEntry = entry
            val st: SurfaceTexture = entry.surfaceTexture()

            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener({
                if (!starting) {
                    result.error("CANCELLED", "the camera was disposed while starting", null)
                    return@addListener
                }
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    bindUseCases(st)
                    sessionId = ++lastSessionId
                    starting = false
                    main.post { result.success(infoMap(entry.id())) }
                } catch (e: Throwable) {
                    starting = false
                    result.error("INIT_ERROR", e.message, null)
                }
            }, ContextCompat.getMainExecutor(appContext))
        } catch (e: Throwable) {
            starting = false
            result.error("INIT_ERROR", e.message, null)
        }
    }

    /**
     * Builds and binds the preview and analysis use cases.
     *
     * Preview keeps the resolution the Dart side asked for (720p / 1080p /
     * MAXIMUM in 16:9), because the picture is what the user sees and the
     * scanner UI is laid out for it.
     *
     * Analysis asks for [ANALYSIS_MAX_SIZE] regardless of what [shortSide]
     * says. See the class comment: the LEVEL_3 guaranteed combination for
     * Preview + YUV is PRIV PREVIEW | YUV MAXIMUM, and this Huawei HAL
     * enforces that literally, returning a zero-sized ImageProxy for every
     * smaller YUV request. The analyser is bigger and slower than it would
     * be at 720p, but it is the one request that works.
     */
    private fun bindUseCases(st: SurfaceTexture) {
        val provider = cameraProvider ?: return
        provider.unbindAll()
        boundShortSide = shortSide

        val selector = CameraSelector.DEFAULT_BACK_CAMERA

        // Preview size: what the UI asked for. Standard 16:9 sizes.
        val previewTargetSize = targetSize(shortSide)

        // Analysis size: MAXIMUM YUV, by specification. Do not compute this
        // from shortSide; the whole point of Variant A is to ask for the one
        // size that is guaranteed to be delivered.
        val analysisTargetSize = ANALYSIS_MAX_SIZE

        lastTargetRotation = uiRotation()
        displayRotationDegrees = rotationDegrees(lastTargetRotation)

        val previewUseCase = Preview.Builder()
            .setTargetResolution(previewTargetSize)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        previewUseCase.setSurfaceProvider { request: SurfaceRequest ->
            val res = request.resolution
            android.util.Log.i(
                TAG,
                "surface request ${res.width}x${res.height} (current buf=${bufW}x$bufH)"
            )
            st.setDefaultBufferSize(res.width, res.height)
            bufW = res.width
            bufH = res.height
            val surface = Surface(st)
            previewSurface = surface
            recomputePreviewSize()
            request.provideSurface(surface, ContextCompat.getMainExecutor(appContext)) {
                surface.release()
            }
        }
        preview = previewUseCase

        val analysisUseCase = ImageAnalysis.Builder()
            .setTargetResolution(analysisTargetSize)
            .setTargetRotation(lastTargetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
        analysisUseCase.setAnalyzer(camExec, ::analyze)
        analysis = analysisUseCase

        android.util.Log.i(
            TAG,
            "binding (Variant A): preview=$previewTargetSize " +
            "analysis=$analysisTargetSize (MAXIMUM YUV)",
        )

        camera = provider.bindToLifecycle(
            this, selector, previewUseCase, analysisUseCase
        )
        startOrientationListener()

        previewUseCase.resolutionInfo?.resolution?.let {
            bufW = it.width; bufH = it.height
        }
        recomputePreviewSize()

        // Reset per-session kick state.
        emptyFrames = 0
        kickStrategy = 0
        lastKickAt = 0

        // The MAXIMUM-YUV request is the primary fix; these kicks are a
        // secondary safety net for a HAL that even the guarantee does not
        // wake up. Harmless if the stream is already awake.
        main.postDelayed({ kickAnalysisStream(force = false) }, 400L)
        main.postDelayed({ kickAnalysisStream(force = false) }, 1400L)

        focusAt(0.5, 0.5)
    }

    /**
     * Sends one harmless control request to wake the analysis stream.
     *
     * Three strategies, cycled by [kickStrategy] so a device that ignores
     * one is tried on the others. Zoom is first: it is the same thing a
     * user pinch does (which is how this bug was first noticed on a
     * different Huawei model), it does not flash a light, and it does not
     * disturb focus. Torch second: brief and silent. AF trigger third:
     * cheapest of all, but on devices where the lens is already settled it
     * may not register as a change.
     *
     * Rate-limited by [KICK_COOLDOWN_MS]. `force = true` bypasses both the
     * cooldown and the zoom-in-progress guard.
     */
    private fun kickAnalysisStream(force: Boolean = false) {
        val cam = camera ?: return
        val now = android.os.SystemClock.elapsedRealtime()
        if (!force && now - lastKickAt < KICK_COOLDOWN_MS) return
        lastKickAt = now
        kickCount++

        val strategy = if (force) 0 else kickStrategy % 3
        if (!force) kickStrategy++

        try {
            when (strategy) {
                0 -> {
                    val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1.0f
                    if (!force && current > 1.1f) return
                    val base = if (current > 0.99f) 1.0f else current
                    cam.cameraControl.setZoomRatio(base * 1.02f)
                    main.postDelayed({
                        try { cam.cameraControl.setZoomRatio(base) } catch (_: Throwable) {}
                    }, 120L)
                    android.util.Log.i(TAG, "kick #$kickCount: zoom nudge")
                }
                1 -> {
                    cam.cameraControl.enableTorch(true)
                    main.postDelayed({
                        try { cam.cameraControl.enableTorch(false) } catch (_: Throwable) {}
                    }, 25L)
                    android.util.Log.i(TAG, "kick #$kickCount: torch blip")
                }
                2 -> {
                    focusAt(0.5, 0.5)
                    android.util.Log.i(TAG, "kick #$kickCount: af trigger")
                }
            }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "kick #$kickCount failed: ${e.message}")
        }
    }

    private fun rebindUseCases() {
        val st = textureEntry?.surfaceTexture() ?: return
        bindUseCases(st)
    }

    private fun displayRotation(): Int =
        try {
            @Suppress("DEPRECATION")
            activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        } catch (_: Throwable) { Surface.ROTATION_0 }

    private fun startOrientationListener() {
        val ctx = activity ?: appContext
        orientationListener?.disable()
        orientationListener = object : OrientationEventListener(ctx) {
            override fun onOrientationChanged(orientation: Int) {
                scheduleRotationCheck()
            }
        }.apply { if (canDetectOrientation()) enable() }

        configCallback?.let { activity?.unregisterComponentCallbacks(it) }
        configCallback = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                scheduleRotationCheck()
            }
            override fun onLowMemory() {}
        }
        @Suppress("DEPRECATION")
        activity?.registerComponentCallbacks(configCallback!!)

        scheduleRotationCheck()
    }

    private fun scheduleRotationCheck() {
        main.removeCallbacks(rotationCheck)
        main.postDelayed(rotationCheck, ROTATION_CHECK_DELAY_MS)
    }

    private val rotationCheck = object : Runnable {
        override fun run() {
            val rot = uiRotation()
            if (rot != lastTargetRotation) {
                lastTargetRotation = rot
                analysis?.targetRotation = rot
                displayRotationDegrees = rotationDegrees(rot)
                android.util.Log.i(TAG, "ui rotated -> ${displayRotationDegrees}deg")
                pushPreviewSize()
                settleChecksLeft = SETTLE_CHECKS
            } else {
                settleChecksLeft--
            }
            if (settleChecksLeft > 0) main.postDelayed(this, ROTATION_CHECK_DELAY_MS)
        }
    }

    private fun uiRotation(): Int {
        val rotation = displayRotation()
        val ctx = activity ?: appContext
        return when (ctx.resources.configuration.orientation) {
            Configuration.ORIENTATION_PORTRAIT ->
                if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_90)
                    Surface.ROTATION_0 else Surface.ROTATION_180
            Configuration.ORIENTATION_LANDSCAPE ->
                if (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_90)
                    Surface.ROTATION_90 else Surface.ROTATION_270
            else -> Surface.ROTATION_0
        }
    }

    private fun rotationDegrees(rot: Int): Int = when (rot) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun recomputePreviewSize() {
        val sensor = try {
            camera?.cameraInfo?.sensorRotationDegrees ?: 90
        } catch (_: Throwable) { 90 }
        val rot = ((sensor % 360) + 360) % 360
        val w = if (rot % 180 == 0) bufW else bufH
        val h = if (rot % 180 == 0) bufH else bufW
        if (w == previewW && h == previewH) return
        android.util.Log.i(
            TAG,
            "preview buf=${bufW}x$bufH sensor=$sensor rot=$rot -> ${w}x$h"
        )
        previewW = w
        previewH = h
        previewRotation = rot
        pushPreviewSize()
    }

    private fun pushPreviewSize() {
        sizeHandler.sink?.success(previewSizeMap())
    }

    private fun focusAt(x: Double, y: Double): Boolean {
        if (x < 0.0 || x > 1.0 || y < 0.0 || y > 1.0) return false
        val cam = camera ?: return false
        val (bx, by) = when (previewRotation) {
            90 -> y to (1 - x)
            180 -> (1 - x) to (1 - y)
            270 -> (1 - y) to x
            else -> x to y
        }
        return try {
            val point = SurfaceOrientedMeteringPointFactory(1f, 1f)
                .createPoint(bx.toFloat(), by.toFloat())
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
            ).build()
            if (!cam.cameraInfo.isFocusMeteringSupported(action)) return false
            cam.cameraControl.startFocusAndMetering(action)
            true
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "focusAt err: ${e.message}")
            false
        }
    }

    private fun previewSizeMap(): Map<String, Any> = mapOf(
        "width" to previewW,
        "height" to previewH,
        "displayRotation" to displayRotationDegrees
    )

    private val sizeHandler = object : EventChannel.StreamHandler {
        @Volatile var sink: EventChannel.EventSink? = null
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
            sink = events
            events?.success(previewSizeMap())
        }
        override fun onCancel(arguments: Any?) { sink = null }
    }

    /// ImageProxy (YUV_420_888) to NV21.
    private fun toNv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val out = ByteArray(w * h * 3 / 2)

        val yPlane = image.planes[0]
        val yBuf = yPlane.buffer.duplicate()
        val yRowStride = yPlane.rowStride
        var pos = 0
        for (row in 0 until h) {
            yBuf.position(row * yRowStride)
            yBuf.get(out, pos, w)
            pos += w
        }

        val uBuf = image.planes[1].buffer.duplicate()
        val vBuf = image.planes[2].buffer.duplicate()
        val uvRowStride = image.planes[1].rowStride
        val uvPixelStride = image.planes[1].pixelStride
        for (row in 0 until h / 2) {
            for (col in 0 until w / 2) {
                val idx = row * uvRowStride + col * uvPixelStride
                out[pos++] = vBuf.get(idx)
                out[pos++] = uBuf.get(idx)
            }
        }
        return out
    }

    /// Compresses the buffered frame to JPEG and brings it upright.
    private fun grabJpeg(): ByteArray? {
        val nv21 = lastNv21 ?: return null
        val w = lastNv21W
        val h = lastNv21H
        if (w == 0 || h == 0) return null

        val bos = ByteArrayOutputStream()
        YuvImage(nv21, ImageFormat.NV21, w, h, null)
            .compressToJpeg(Rect(0, 0, w, h), 88, bos)
        val rot = ((lastNv21Rot % 360) + 360) % 360
        if (rot == 0) return bos.toByteArray()

        val raw = bos.toByteArray()
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size) ?: return raw
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        val rotated = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        val out = ByteArrayOutputStream()
        rotated.compress(Bitmap.CompressFormat.JPEG, 88, out)
        if (rotated !== bmp) bmp.recycle()
        rotated.recycle()
        return out.toByteArray()
    }

    // ---------- per frame ----------
    //
    // The Y plane and the NV21 copy are extracted separately. Zero-sized
    // ImageProxy objects are the wake-up bug described in the class comment,
    // not a picture with nothing in it. They are counted, they schedule a
    // kick, and they never reach Rust.
    private fun analyze(image: ImageProxy) {
        if (image.width == 0 || image.height == 0) {
            emptyFrames++
            if (emptyFrames == 5L || emptyFrames == 30L || emptyFrames == 120L) {
                android.util.Log.w(
                    TAG,
                    "empty #$emptyFrames: ${image.width}x${image.height} " +
                    "format=${image.format} — kicking",
                )
                main.post { kickAnalysisStream(force = false) }
            }
            image.close()
            return
        }
        if (emptyFrames > 0) {
            android.util.Log.i(TAG, "stream woke after $emptyFrames empty frames")
            emptyFrames = 0
        }

        val y: ByteArray
        val w: Int
        val h: Int
        val rowStride: Int
        val rot: Int

        try {
            val plane = image.planes[0]
            rowStride = plane.rowStride
            w = image.width
            h = image.height
            rot = image.imageInfo.rotationDegrees
            y = ByteArray(plane.buffer.remaining())
            plane.buffer.get(y)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "grab Y err: ${e.message}", e)
            image.close()
            return
        }

        if (scanning) {
            try {
                lastNv21 = toNv21(image)
                lastNv21W = w
                lastNv21H = h
                lastNv21Rot = rot
            } catch (e: Throwable) {
                android.util.Log.w(
                    TAG,
                    "nv21 err (grabFrame disabled): ${e.message} " +
                    "format=${image.format} planes=${image.planes.size}",
                )
                lastNv21 = null
            }
        }

        image.close()

        if (!scanning || !nativeReady || scannerHandle == 0L || busy) {
            if (skippedFrames++ % 60 == 0L) {
                android.util.Log.i(
                    TAG,
                    "frame skipped: scanning=$scanning " +
                    "nativeReady=$nativeReady " +
                    "scannerHandle=$scannerHandle busy=$busy " +
                    "format=${image.format} size=${w}x$h",
                )
            }
            return
        }
        analyzedFrames++
        busy = true
        try {
            submitScan(y, w, h, rowStride, rot)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            busy = false
        }
    }

    private fun submitScan(y: ByteArray, w: Int, h: Int, rowStride: Int, rot: Int) {
        worker.execute {
            try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val json = NativeScanner.scanFrame(scannerHandle, y, w, h, rowStride, rot, false)
                val dt = android.os.SystemClock.elapsedRealtime() - t0

                if (firstNScans < 5) {
                    firstNScans++
                    android.util.Log.i(
                        TAG,
                        "scan #$firstNScans: ${w}x$h rot=$rot stride=$rowStride " +
                        "yLen=${y.size} dt=${dt}ms json='${json.take(120)}'",
                    )
                }

                statFrames++
                statTotalMs += dt
                if (dt > statMaxMs) statMaxMs = dt
                if (statFrames >= 30) {
                    val n = statFrames
                    android.util.Log.i(
                        TAG,
                        "scan ${statTotalMs / n}ms avg / ${statMaxMs}ms max (${w}x$h rot=$rot)"
                    )
                    statFrames = 0; statTotalMs = 0; statMaxMs = 0
                }

                main.post { scanSink?.success(json) }
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "scan err: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun teardown() {
        starting = false
        orientationListener?.disable()
        orientationListener = null
        @Suppress("DEPRECATION")
        configCallback?.let { activity?.unregisterComponentCallbacks(it) }
        configCallback = null
        main.removeCallbacks(rotationCheck)
        preview = null
        analysis = null
        try { cameraProvider?.unbindAll() } catch (_: Throwable) {}
        cameraProvider = null
        camera = null
        try { previewSurface?.release() } catch (_: Throwable) {}
        previewSurface = null
        try { textureEntry?.release() } catch (_: Throwable) {}
        textureEntry = null
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
        sessionId = 0L
        boundShortSide = 0
        lastNv21 = null
        emptyFrames = 0
        lastKickAt = 0
        kickStrategy = 0
        releaseScanner()
    }
}