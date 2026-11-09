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
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The preview goes through CameraX's Preview use case into a Flutter
 * SurfaceTexture, which stays on the GPU and costs no CPU per frame; scanning
 * goes through ImageAnalysis, whose Y plane reaches Rust over JNI without
 * passing through Dart.
 *
 * **Diagnostic mode (SCAN_ENABLED = false).**
 *
 * A large Y-plane (4096x2160, 8.8 MB) crashes the native scanner. Before
 * investigating why, the buffer has to be measured: is the HAL delivering a
 * real picture, a zeroed buffer, or all-0xFF? While [SCAN_ENABLED] is false,
 * nothing is passed to NativeScanner.scanFrame; every frame is copied into a
 * reused buffer, sampled (every 64th pixel: min, max, avg), and the first 16
 * bytes are hex-encoded. The summary lands in debugState and on the
 * diagnostic panel. Flip [SCAN_ENABLED] to true once the summary proves the
 * buffer holds a live picture; a crash then is about what Rust does with the
 * input, not about whether the input exists.
 *
 * **Huawei 0x0 bug (unchanged).**
 *
 * On some Huawei EMUI builds (STG-LX2 / P Smart 5G, Android 12, EMUI 12) the
 * analysis stream receives zero-sized ImageProxy objects at every
 * preview-aligned resolution. Preview renders fine, the device's
 * capabilities include LEVEL_3 with YUV_REPROCESSING, and its
 * guaranteed-configuration table says:
 *
 *     PRIV PREVIEW | PRIV 640x480 | YUV MAXIMUM | RAW MAXIMUM
 *
 * For a combination with Preview, the only guaranteed YUV size is MAXIMUM
 * (4096x2160 here). Smaller YUV requests get a zero-sized buffer. So the
 * analyser asks for [ANALYSIS_MAX_SIZE] by name.
 *
 * **Buffer caching.**
 *
 * At 4096x2160 and ~30 fps, allocating fresh ByteArrays per frame (y at
 * 8.8 MB, nv21 at 13 MB) is ~680 MB/s of allocations, which no GC keeps up
 * with. Three buffers are cached and reused: [yBuf], [nv21Buf],
 * [downscaleBuf]. NV21 is only built when grabFrame has actually asked for
 * it (the picker, when several codes are in view).
 *
 * **try-catch.**
 *
 * Every callback and public method is wrapped in try-catch(Throwable). It
 * does not catch a Rust panic that aborts via SIGABRT — that is not a Java
 * exception. If the app still crashes after this, logcat will carry an
 * `Abort message:` and the input to Rust needs inspecting.
 */
class WxScanLivePlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler, ActivityAware, LifecycleOwner {

    companion object {
        private const val TAG = "wxscan_live"

        private const val ANALYSIS_WIDTH = 1280
        private const val ANALYSIS_HEIGHT = 720

        /** MAXIMUM YUV, the only size LEVEL_3 guarantees with Preview. */
        private val ANALYSIS_MAX_SIZE = Size(4096, 2160)

        private fun targetSize(shortSide: Int): Size = when (shortSide) {
            720 -> Size(1280, 720)
            1080 -> Size(1920, 1080)
            0 -> Size(3840, 2160)
            else -> Size(shortSide * 16 / 9, shortSide)
        }

        private const val ROTATION_CHECK_DELAY_MS = 120L
        private const val SETTLE_CHECKS = 3
        private const val KICK_COOLDOWN_MS = 1000L

        /**
         * When true, frames are passed to NativeScanner.scanFrame.
         *
         * Set to false for the current investigation. Every frame is still
         * copied and measured, and the result is surfaced through
         * debugState; nothing reaches Rust. That is what makes it safe to
         * look at the buffer without risking the crash that prompted the
         * diagnostic. Flip to true once the summary proves the buffer holds
         * a picture.
         */
        private const val SCAN_ENABLED = false

        /** How many frames the diagnostic logs in full, before it settles
         *  into a per-frame summary in debugState. */
        private const val DIAG_FRAMES = 5
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

    // Cached buffers. Allocated once per session and reused; per-frame
    // allocation of Y / NV21 / downscaled planes is fatal on this device.
    private var yBuf: ByteArray? = null
    private var nv21Buf: ByteArray? = null
    private var downscaleBuf: ByteArray? = null

    @Volatile private var lastNv21: ByteArray? = null
    @Volatile private var lastNv21W = 0
    @Volatile private var lastNv21H = 0
    @Volatile private var lastNv21Rot = 0

    /** Set by grabFrame, cleared after the next NV21 build. */
    @Volatile private var needNv21 = false

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

    private var statFrames = 0
    private var statTotalMs = 0L
    private var statMaxMs = 0L

    @Volatile private var analyzedFrames = 0L
    @Volatile private var skippedFrames = 0L
    @Volatile private var emptyFrames = 0L
    @Volatile private var firstNScans = 0

    @Volatile private var lastKickAt = 0L
    @Volatile private var kickStrategy = 0
    @Volatile private var kickCount = 0

    // Diagnostic: last measured Y-plane stats.
    @Volatile private var diagW = 0
    @Volatile private var diagH = 0
    @Volatile private var diagStride = 0
    @Volatile private var diagLen = 0
    @Volatile private var diagMin = 0
    @Volatile private var diagMax = 0
    @Volatile private var diagAvg = 0.0
    @Volatile private var diagFirst16 = ""
    @Volatile private var diagFramesSeen = 0L

    // ---------- FlutterPlugin ----------
    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        try {
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
                android.util.Log.e(TAG, "NativeScanner.ping FAILED", e)
                false
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "onAttachedToEngine failed", e)
        }
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        try {
            methodChannel.setMethodCallHandler(null)
            eventChannel.setStreamHandler(null)
            sizeChannel.setStreamHandler(null)
            teardown()
            if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.CREATED)) {
                lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
            }
            worker.shutdown()
            camExec.shutdown()
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "onDetachedFromEngine failed", e)
        }
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
        try {
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
                                android.util.Log.e(TAG, "rebind failed", e)
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
                    try {
                        needNv21 = true
                        result.success(grabJpeg())
                    } catch (e: Throwable) {
                        android.util.Log.e(TAG, "grabFrame err", e)
                        result.success(null)
                    }
                }
                "setZoom" -> {
                    val want = (call.argument<Double>("ratio") ?: 1.0).toFloat()
                    try {
                        val state = camera?.cameraInfo?.zoomState?.value
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
                    main.post { try { kickAnalysisStream(force = true) } catch (_: Throwable) {} }
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
                            "scanTargetSize" to
                                "${targetSize(shortSide).width}x${targetSize(shortSide).height}",
                            "yBufCached" to (yBuf?.size ?: 0),
                            "nv21BufCached" to (nv21Buf?.size ?: 0),
                            "downscaleBufCached" to (downscaleBuf?.size ?: 0),
                            "scanEnabled" to SCAN_ENABLED,
                            "diagW" to diagW,
                            "diagH" to diagH,
                            "diagStride" to diagStride,
                            "diagLen" to diagLen,
                            "diagMin" to diagMin,
                            "diagMax" to diagMax,
                            "diagAvg" to diagAvg,
                            "diagFirst16" to diagFirst16,
                            "diagFramesSeen" to diagFramesSeen,
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
                        try {
                            val stride = w + 16
                            val padded = ByteArray(stride * h)
                            for (yy in 0 until h) {
                                System.arraycopy(gray, yy * w, padded, yy * stride, w)
                            }
                            val t0 = android.os.SystemClock.elapsedRealtime()
                            val json = try {
                                NativeScanner.scanFrame(scannerHandle, padded, w, h, stride, rot, false)
                            } catch (e: Throwable) { "ERR ${e.message}" }
                            val dt = android.os.SystemClock.elapsedRealtime() - t0
                            android.util.Log.i(TAG, "selfTestNative rot=$rot ${dt}ms -> $json")
                            main.post { result.success(json) }
                        } catch (e: Throwable) {
                            android.util.Log.e(TAG, "selfTestNative failed", e)
                            main.post { result.success("ERR ${e.message}") }
                        }
                    }
                }
                "dispose" -> {
                    val session = call.argument<Number>("sessionId")?.toLong() ?: 0L
                    if (session == 0L || session == sessionId) teardown()
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "onMethodCall(${call.method}) failed", e)
            try { result.error("PLUGIN_ERROR", e.message, null) } catch (_: Throwable) {}
        }
    }

    private fun ensureScanner(
        detect: ByteArray?,
        sr: ByteArray?,
        detectPath: String?,
        srPath: String?,
        borrowed: Long,
    ) {
        try {
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
                    return
                }
            }

            val empty = ByteArray(0)
            val fromPaths = detectPath != null || srPath != null
            scannerHandle = try {
                val h = if (fromPaths) NativeScanner.createFromPaths(detectPath, srPath)
                        else NativeScanner.create(detect ?: empty, sr ?: empty)
                android.util.Log.i(TAG, "scanner created: handle=$h paths=$fromPaths")
                h
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "scanner create FAILED", e)
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
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "ensureScanner failed", e)
            scannerHandle = 0L
            modelsLoaded = false
        }
    }

    private fun releaseScanner() {
        try {
            if (scannerHandle == 0L) return
            val h = scannerHandle
            scannerHandle = 0L
            modelsLoaded = false
            worker.execute { try { NativeScanner.release(h) } catch (_: Throwable) {} }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "releaseScanner failed", e)
        }
    }

    private fun handleInitialize(
        result: MethodChannel.Result,
        detect: ByteArray?,
        sr: ByteArray?,
        detectPath: String?,
        srPath: String?,
        borrowed: Long,
    ) {
        try {
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
            main.post {
                try { bindCamera(result, detect, sr, detectPath, srPath, borrowed) }
                catch (e: Throwable) {
                    android.util.Log.e(TAG, "bindCamera failed", e)
                    try { result.error("INIT_ERROR", e.message, null) } catch (_: Throwable) {}
                }
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "handleInitialize failed", e)
            try { result.error("INIT_ERROR", e.message, null) } catch (_: Throwable) {}
        }
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
        try {
            val bound = textureEntry
            if (bound != null && cameraProvider != null) {
                sessionId = ++lastSessionId
                ensureScanner(detect, sr, detectPath, srPath, borrowed)
                if (shortSide != boundShortSide) {
                    try { rebindUseCases() } catch (e: Throwable) {
                        android.util.Log.e(TAG, "rebind on takeover failed", e)
                    }
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
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            textureEntry?.let { try { it.release() } catch (_: Throwable) {} }
            val entry = textureRegistry.createSurfaceTexture()
            textureEntry = entry
            val st: SurfaceTexture = entry.surfaceTexture()

            val future = ProcessCameraProvider.getInstance(appContext)
            future.addListener({
                try {
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
                        android.util.Log.e(TAG, "bindUseCases failed", e)
                        result.error("INIT_ERROR", e.message, null)
                    }
                } catch (e: Throwable) {
                    android.util.Log.e(TAG, "future listener failed", e)
                }
            }, ContextCompat.getMainExecutor(appContext))
        } catch (e: Throwable) {
            starting = false
            android.util.Log.e(TAG, "bindCamera failed", e)
            result.error("INIT_ERROR", e.message, null)
        }
    }

    private fun bindUseCases(st: SurfaceTexture) {
        val provider = cameraProvider ?: return
        provider.unbindAll()
        boundShortSide = shortSide

        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val previewTargetSize = targetSize(shortSide)
        val analysisTargetSize = Size(640,480)

        lastTargetRotation = uiRotation()
        displayRotationDegrees = rotationDegrees(lastTargetRotation)

        val previewUseCase = Preview.Builder()
            .setTargetResolution(previewTargetSize)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        previewUseCase.setSurfaceProvider { request: SurfaceRequest ->
            try {
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
                    try { surface.release() } catch (_: Throwable) {}
                }
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "surfaceProvider failed", e)
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
            "binding: preview=$previewTargetSize analysis=$analysisTargetSize " +
            "scanEnabled=$SCAN_ENABLED"
        )

        camera = provider.bindToLifecycle(
            this, selector, previewUseCase, analysisUseCase
        )
        startOrientationListener()

        previewUseCase.resolutionInfo?.resolution?.let {
            bufW = it.width; bufH = it.height
        }
        recomputePreviewSize()

        emptyFrames = 0
        kickStrategy = 0
        lastKickAt = 0
        diagFramesSeen = 0

        main.postDelayed({ try { kickAnalysisStream(force = false) } catch (_: Throwable) {} }, 400L)
        main.postDelayed({ try { kickAnalysisStream(force = false) } catch (_: Throwable) {} }, 1400L)

        focusAt(0.5, 0.5)
    }

    private fun kickAnalysisStream(force: Boolean = false) {
        try {
            val cam = camera ?: return
            val now = android.os.SystemClock.elapsedRealtime()
            if (!force && now - lastKickAt < KICK_COOLDOWN_MS) return
            lastKickAt = now
            kickCount++

            val strategy = if (force) 0 else kickStrategy % 3
            if (!force) kickStrategy++

            when (strategy) {
                0 -> {
                    val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1.0f
                    if (!force && current > 1.1f) return
                    val base = if (current > 0.99f) 1.0f else current
                    cam.cameraControl.setZoomRatio(base * 1.02f)
                    main.postDelayed({
                        try { cam.cameraControl.setZoomRatio(base) } catch (_: Throwable) {}
                    }, 120L)
                }
                1 -> {
                    cam.cameraControl.enableTorch(true)
                    main.postDelayed({
                        try { cam.cameraControl.enableTorch(false) } catch (_: Throwable) {}
                    }, 25L)
                }
                2 -> focusAt(0.5, 0.5)
            }
        } catch (e: Throwable) {
            android.util.Log.w(TAG, "kick failed: ${e.message}")
        }
    }

    private fun rebindUseCases() {
        try {
            val st = textureEntry?.surfaceTexture() ?: return
            bindUseCases(st)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "rebindUseCases failed", e)
        }
    }

    private fun displayRotation(): Int =
        try {
            @Suppress("DEPRECATION")
            activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        } catch (_: Throwable) { Surface.ROTATION_0 }

    private fun startOrientationListener() {
        try {
            val ctx = activity ?: appContext
            orientationListener?.disable()
            orientationListener = object : OrientationEventListener(ctx) {
                override fun onOrientationChanged(orientation: Int) {
                    try { scheduleRotationCheck() } catch (_: Throwable) {}
                }
            }.apply { if (canDetectOrientation()) enable() }

            configCallback?.let { activity?.unregisterComponentCallbacks(it) }
            configCallback = object : ComponentCallbacks {
                override fun onConfigurationChanged(newConfig: Configuration) {
                    try { scheduleRotationCheck() } catch (_: Throwable) {}
                }
                override fun onLowMemory() {}
            }
            @Suppress("DEPRECATION")
            activity?.registerComponentCallbacks(configCallback!!)

            scheduleRotationCheck()
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "startOrientationListener failed", e)
        }
    }

    private fun scheduleRotationCheck() {
        main.removeCallbacks(rotationCheck)
        main.postDelayed(rotationCheck, ROTATION_CHECK_DELAY_MS)
    }

    private val rotationCheck = object : Runnable {
        override fun run() {
            try {
                val rot = uiRotation()
                if (rot != lastTargetRotation) {
                    lastTargetRotation = rot
                    analysis?.targetRotation = rot
                    displayRotationDegrees = rotationDegrees(rot)
                    pushPreviewSize()
                    settleChecksLeft = SETTLE_CHECKS
                } else {
                    settleChecksLeft--
                }
                if (settleChecksLeft > 0) main.postDelayed(this, ROTATION_CHECK_DELAY_MS)
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "rotationCheck failed", e)
            }
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
        previewW = w
        previewH = h
        previewRotation = rot
        pushPreviewSize()
    }

    private fun pushPreviewSize() {
        try { sizeHandler.sink?.success(previewSizeMap()) } catch (_: Throwable) {}
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
            try { events?.success(previewSizeMap()) } catch (_: Throwable) {}
        }
        override fun onCancel(arguments: Any?) { sink = null }
    }

    // ---------- cached buffer helpers ----------

    private fun ensureYBuffer(size: Int): ByteArray {
        var b = yBuf
        if (b == null || b.size < size) {
            b = ByteArray(size)
            yBuf = b
        }
        return b
    }

    private fun ensureNv21Buffer(size: Int): ByteArray {
        var b = nv21Buf
        if (b == null || b.size < size) {
            b = ByteArray(size)
            nv21Buf = b
        }
        return b
    }

    private fun ensureDownscaleBuffer(size: Int): ByteArray {
        var b = downscaleBuf
        if (b == null || b.size < size) {
            b = ByteArray(size)
            downscaleBuf = b
        }
        return b
    }

    /**
     * Copies the Y plane into the cached [yBuf] and returns the logical size
     * (which may be less than `yBuf.size` — the buffer is reused as-is).
     *
     * The Y plane of a YUV_420_888 ImageProxy has `rowStride` bytes per row
     * and `height` rows, so the buffer's `remaining()` is usually
     * `rowStride * height`, not `width * height`. Both are handled: the
     * caller is told `rowStride` separately and measures the image with it.
     */
    private fun copyYPlane(image: ImageProxy): Triple<ByteArray, Int, Int>? {
        val plane = image.planes[0]
        val remaining = plane.buffer.remaining()
        val stride = plane.rowStride
        val buf = ensureYBuffer(remaining)
        plane.buffer.get(buf, 0, remaining)
        return Triple(buf, remaining, stride)
    }

    /**
     * Downscales a Y plane into [dst] (nearest-neighbour), honouring the
     * source row padding. `dst` must be at least `dstW * dstH` bytes.
     *
     * Nearest-neighbour rather than averaging: the source is 2 to 3 times
     * larger than the destination and the destination is what the scanner
     * was built for; QR modules are high-contrast edges and sampling
     * preserves them. Averaging would cost CPU for detail the detector
     * resizes away anyway.
     */
    private fun downscaleGrayInto(
        src: ByteArray,
        dst: ByteArray,
        srcW: Int,
        srcH: Int,
        srcStride: Int,
        dstW: Int,
        dstH: Int,
    ) {
        val xRatio = srcW.toFloat() / dstW
        val yRatio = srcH.toFloat() / dstH
        var di = 0
        for (dy in 0 until dstH) {
            val sy = (dy * yRatio).toInt().coerceAtMost(srcH - 1)
            val srcRow = sy * srcStride
            for (dx in 0 until dstW) {
                val sx = (dx * xRatio).toInt().coerceAtMost(srcW - 1)
                dst[di++] = src[srcRow + sx]
            }
        }
    }

    /**
     * Cheap Y-plane statistics for the diagnostic.
     *
     * Samples every 64th pixel in each row (col += 64) rather than the whole
     * plane: a 4096x2160 buffer is 8.8 MB and the point is a min, max and
     * average rather than a pixel-exact histogram. 1/64 of a natural image
     * is representative.
     *
     * Returns `(min, max, avg)`. `diagFirst16` is set as a side effect: the
     * first 16 bytes of the buffer, hex-encoded with spaces — the very start
     * of the Y-plane, which distinguishes "zeroed buffer" (all 00) from
     * "white frame" (all FF) from "live picture" (mixed).
     */
    private fun measureYPlane(
        y: ByteArray,
        w: Int,
        h: Int,
        stride: Int,
    ): Triple<Int, Int, Double> {
        var mn = 255
        var mx = 0
        var sum = 0L
        var count = 0L
        for (row in 0 until h) {
            val rowStart = row * stride
            var col = 0
            while (col < w) {
                val idx = rowStart + col
                if (idx >= y.size) break
                val v = y[idx].toInt() and 0xFF
                if (v < mn) mn = v
                if (v > mx) mx = v
                sum += v
                count++
                col += 64
            }
        }
        val n = minOf(16, y.size)
        val sb = StringBuilder(n * 3)
        for (k in 0 until n) {
            val b = y[k].toInt() and 0xFF
            if (b < 16) sb.append('0')
            sb.append(Integer.toHexString(b)).append(' ')
        }
        diagFirst16 = sb.toString().trim()
        val avg = if (count > 0) sum.toDouble() / count.toDouble() else 0.0
        return Triple(mn, mx, avg)
    }

    /// ImageProxy (YUV_420_888) to NV21, into the cached [nv21Buf].
    private fun toNv21(image: ImageProxy): ByteArray {
        val w = image.width
        val h = image.height
        val needed = w * h * 3 / 2
        val out = ensureNv21Buffer(needed)

        val yPlane = image.planes[0]
        val yBufLocal = yPlane.buffer.duplicate()
        val yRowStride = yPlane.rowStride
        var pos = 0
        for (row in 0 until h) {
            yBufLocal.position(row * yRowStride)
            yBufLocal.get(out, pos, w)
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
    // In diagnostic mode (SCAN_ENABLED = false) nothing is passed to Rust.
    // The Y plane is copied into a reused buffer, measured, and surfaced
    // through debugState. In normal mode the Y plane is downscaled to what
    // the scanner was built for and handed to submitScan.
    private fun analyze(image: ImageProxy) {
        try {
            if (image.width == 0 || image.height == 0) {
                emptyFrames++
                if (emptyFrames == 5L || emptyFrames == 30L || emptyFrames == 120L) {
                    android.util.Log.w(
                        TAG,
                        "empty #$emptyFrames: ${image.width}x${image.height} " +
                        "format=${image.format} — kicking",
                    )
                    main.post { try { kickAnalysisStream(force = false) } catch (_: Throwable) {} }
                }
                try { image.close() } catch (_: Throwable) {}
                return
            }
            if (emptyFrames > 0) {
                android.util.Log.i(TAG, "stream woke after $emptyFrames empty frames")
                emptyFrames = 0
            }

            val w = image.width
            val h = image.height
            val rot = image.imageInfo.rotationDegrees

            // Copy the Y plane into a reused buffer.
            val copied = copyYPlane(image)
            if (copied == null) {
                try { image.close() } catch (_: Throwable) {}
                return
            }
            val (y, yLen, rowStride) = copied

            // Optionally build NV21 for grabFrame. Only when asked — this is
            // the expensive path and it is not needed on ordinary frames.
            if (scanning && needNv21) {
                try {
                    val nv21 = toNv21(image)
                    lastNv21 = nv21
                    lastNv21W = w
                    lastNv21H = h
                    lastNv21Rot = rot
                    needNv21 = false
                } catch (e: Throwable) {
                    android.util.Log.w(
                        TAG,
                        "nv21 err (grabFrame disabled): ${e.message} " +
                        "format=${image.format} planes=${image.planes.size}",
                    )
                    lastNv21 = null
                    needNv21 = false
                }
            }

            try { image.close() } catch (_: Throwable) {}

            // ---- Measure, always. ----
            val stats = measureYPlane(y, w, h, rowStride)
            diagW = w
            diagH = h
            diagStride = rowStride
            diagLen = yLen
            diagMin = stats.first
            diagMax = stats.second
            diagAvg = stats.third
            diagFramesSeen++

            if (diagFramesSeen <= DIAG_FRAMES) {
                android.util.Log.i(
                    TAG,
                    "diag #$diagFramesSeen: ${w}x$h stride=$rowStride " +
                    "len=$yLen min=${stats.first} max=${stats.second} " +
                    "avg=${String.format(Locale.US, "%.1f", stats.third)} " +
                    "first16=$diagFirst16",
                )
            }

            // ---- Diagnostic gate ----
            if (!SCAN_ENABLED) {
                analyzedFrames++
                return
            }

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

            val scanTarget = targetSize(shortSide)
            val dstSize = scanTarget.width * scanTarget.height
            val dst = ensureDownscaleBuffer(dstSize)
            downscaleGrayInto(
                y, dst, w, h, rowStride, scanTarget.width, scanTarget.height,
            )

            analyzedFrames++
            busy = true
            try {
                submitScan(dst, scanTarget.width, scanTarget.height, scanTarget.width, rot)
            } catch (_: java.util.concurrent.RejectedExecutionException) {
                busy = false
            }
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "analyze failed: ${e.message}", e)
            try { image.close() } catch (_: Throwable) {}
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

                main.post { try { scanSink?.success(json) } catch (_: Throwable) {} }
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "scan err: ${e.message}")
            } finally {
                busy = false
            }
        }
    }

    private fun teardown() {
        try {
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
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "teardown failed", e)
        }
    }
}