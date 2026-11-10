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
import android.util.Rational
import android.util.Size
import android.content.res.Configuration
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
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
import androidx.lifecycle.Observer
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
 * Preview goes through CameraX into a Flutter SurfaceTexture (GPU, no CPU
 * per frame); ImageAnalysis sends the Y plane straight to Rust over JNI.
 *
 * **The Huawei bind bug, and why this version waits on CameraState.**
 *
 * On STG-LX2 / P Smart 5G (EMUI 12, Android 12), the analysis stream is
 * silent (zero-sized ImageProxy or no frames at all) unless the analyser
 * attaches in a particular way. The previous iterations of this file tried
 * several ways to hit that way, all of them on fixed timers:
 *
 *   * two-step bind after 800 ms (cold),
 *   * a second plain bind after 500 ms (warm),
 *   * a rebind chain that cycled through sizes,
 *   * zoom kicks, AF kicks, torch blips.
 *
 * Timers are the wrong tool. `bindToLifecycle` returns a [Camera] immediately,
 * but the underlying Camera2 session is created asynchronously — the state
 * goes PENDING_OPEN → OPENING → OPEN, and only when it reaches OPEN are the
 * surfaces actually attached and streaming. A rebind scheduled 500 ms after
 * the previous one may arrive before the previous session finished opening,
 * and CameraX cancels the earlier one — producing exactly the 0x0 frame
 * state this file has been fighting.
 *
 * So the bind sequence is now driven by [CameraState] instead:
 *
 *   1. bindToLifecycle(Preview alone).
 *   2. Wait for cameraState.type == OPEN, plus 300 ms of settling.
 *   3. bindToLifecycle(UseCaseGroup(Preview, Analysis, shared ViewPort)).
 *   4. Wait for cameraState.type == OPEN again.
 *   5. Wait for frames.
 *
 * Every bind — first open, resolution change, manual kick — goes through
 * this same sequence. There is no "warm" shortcut and no retry loop: the
 * sequence itself is the retry, because each step waits until the camera
 * is genuinely ready before doing the next thing.
 *
 * A timeout of 3 s on each "wait for OPEN" covers the case where
 * CameraState never reaches OPEN (Huawei HALs have been known to hold the
 * state at OPENING forever). On timeout, the step proceeds anyway, so a
 * broken CameraState does not deadlock the sequence.
 *
 * The single remaining async step is a manual `kickStream` from the
 * diagnostic panel, which just restarts the sequence from the top.
 *
 * **setOutputImageRotationEnabled(false).** Explicit, so the ImageProxy is
 * in the sensor's orientation with the rotation in imageInfo.rotationDegrees.
 *
 * **Everything else as before.** Shared AspectRatioStrategy, same target
 * size for both use cases, FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
 * SCAN_ENABLED gate, downscale only if the HAL hands back something much
 * bigger, cached Y/NV21/downscale buffers, try-catch(Throwable) everywhere.
 */
class WxScanLivePlugin : FlutterPlugin, MethodChannel.MethodCallHandler,
    EventChannel.StreamHandler, ActivityAware, LifecycleOwner {

    companion object {
        private const val TAG = "wxscan_live"

        private const val ANALYSIS_WIDTH = 1280
        private const val ANALYSIS_HEIGHT = 720

        private fun targetSize(shortSide: Int): Size = when (shortSide) {
            720 -> Size(1280, 720)
            1080 -> Size(1920, 1080)
            0 -> Size(3840, 2160)
            else -> Size(shortSide * 16 / 9, shortSide)
        }

        private const val ROTATION_CHECK_DELAY_MS = 120L
        private const val SETTLE_CHECKS = 3

        private const val SCAN_ENABLED = true
        private const val DIAG_FRAMES = 5
        private const val DOWNSCALE_THRESHOLD = 2

        /** Delay between "cameraState OPEN" and the next bind step. The
         *  session is streaming by then; this is the cushion the HAL needs
         *  to accept a second bindToLifecycle without cancelling the first. */
        private const val OPEN_SETTLE_MS = 300L

        /** How long "wait for OPEN" may run before proceeding anyway. */
        private const val OPEN_TIMEOUT_MS = 3000L
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

    private var yBuf: ByteArray? = null
    private var nv21Buf: ByteArray? = null
    private var downscaleBuf: ByteArray? = null

    @Volatile private var lastNv21: ByteArray? = null
    @Volatile private var lastNv21W = 0
    @Volatile private var lastNv21H = 0
    @Volatile private var lastNv21Rot = 0

    @Volatile private var needNv21 = false

    @Volatile private var bufW = ANALYSIS_WIDTH
    @Volatile private var bufH = ANALYSIS_HEIGHT

    @Volatile private var shortSide = ANALYSIS_HEIGHT
    @Volatile private var targetShortSide = ANALYSIS_HEIGHT

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

    // Bind state.
    /** True once any frame has arrived for the current bind cycle. Reset by
     *  setResolution / kickStream. */
    @Volatile private var firstFrameReceived = false
    /** 0 = idle, 1 = Preview-only step done, 2 = UseCaseGroup step done. */
    @Volatile private var bindStage = 0
    @Volatile private var bindCount = 0
    /** Tasks scheduled for the current bind, cancelled on rebind / teardown. */
    private var pendingBinds: MutableList<Runnable> = mutableListOf()

    // CameraState waiting.
    private var currentWaiter: CameraOpenWaiter? = null

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
                    call.argument<Int>("shortSide")?.let {
                        shortSide = it
                        targetShortSide = it
                    }
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
                    if (cameraProvider == null) {
                        targetShortSide = want
                        result.success(null)
                    } else {
                        main.post {
                            try {
                                android.util.Log.i(
                                    TAG,
                                    "setResolution($want): full two-step bind",
                                )
                                targetShortSide = want
                                shortSide = want
                                firstFrameReceived = false
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
                // Manual restart of the whole bind sequence. Useful when a
                // session has gone silent for any reason.
                "kickStream" -> {
                    main.post {
                        try {
                            android.util.Log.i(TAG, "manual restart of bind sequence")
                            firstFrameReceived = false
                            shortSide = targetShortSide
                            rebindUseCases()
                        } catch (_: Throwable) {}
                    }
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
                            "targetShortSide" to targetShortSide,
                            "starting" to starting,
                            "sessionId" to sessionId,
                            "boundShortSide" to boundShortSide,
                            "hasProvider" to (cameraProvider != null),
                            "hasCamera" to (camera != null),
                            "hasAnalysis" to (analysis != null),
                            "hasPreview" to (preview != null),
                            "lastNv21" to (lastNv21?.size ?: 0),
                            "bindCount" to bindCount,
                            "bindStage" to bindStage,
                            "firstFrameReceived" to firstFrameReceived,
                            "scanEnabled" to SCAN_ENABLED,
                            "desiredSize" to
                                "${targetSize(targetShortSide).width}x" +
                                "${targetSize(targetShortSide).height}",
                            "yBufCached" to (yBuf?.size ?: 0),
                            "nv21BufCached" to (nv21Buf?.size ?: 0),
                            "downscaleBufCached" to (downscaleBuf?.size ?: 0),
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
            bindCount = 0
            firstFrameReceived = false
            bindStage = 0

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

    /**
     * Two-step bind, driven by CameraState.OPEN.
     *
     * Step 1: Preview alone. Wait for OPEN.
     * Step 2: UseCaseGroup(Preview, Analysis, ViewPort). Wait for OPEN.
     *
     * Every entry into this method — first open, resolution change, manual
     * kick — runs this same sequence. There is no warm/cold distinction and
     * no retry chain; the sequence waits for the camera to be genuinely
     * ready between steps, which is the only reliable signal on this device.
     */
    private fun bindUseCases(st: SurfaceTexture) {
        val provider = cameraProvider ?: return
        cancelPendingBinds()
        cancelCameraOpenWait()
        provider.unbindAll()
        boundShortSide = shortSide
        bindCount++

        val selector = CameraSelector.DEFAULT_BACK_CAMERA
        val desiredSize = targetSize(shortSide)

        val aspectRatioStrategy = AspectRatioStrategy(
            AspectRatio.RATIO_16_9,
            AspectRatioStrategy.FALLBACK_RULE_AUTO,
        )

        val previewResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectRatioStrategy)
            .setResolutionStrategy(
                ResolutionStrategy(
                    desiredSize,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()

        val analysisResolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(aspectRatioStrategy)
            .setResolutionStrategy(
                ResolutionStrategy(
                    desiredSize,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                )
            )
            .build()

        lastTargetRotation = uiRotation()
        displayRotationDegrees = rotationDegrees(lastTargetRotation)

        val previewUseCase = Preview.Builder()
            .setResolutionSelector(previewResolutionSelector)
            .setTargetRotation(Surface.ROTATION_0)
            .build()
        previewUseCase.setSurfaceProvider { request: SurfaceRequest ->
            try {
                val res = request.resolution
                android.util.Log.i(
                    TAG,
                    "surface request ${res.width}x${res.height} " +
                    "(current buf=${bufW}x$bufH)",
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
            .setResolutionSelector(analysisResolutionSelector)
            .setTargetRotation(lastTargetRotation)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageRotationEnabled(false)
            .build()
        analysisUseCase.setAnalyzer(camExec, ::analyze)
        analysis = analysisUseCase

        // ---- Step 1: Preview only ----
        bindStage = 1
        android.util.Log.i(
            TAG,
            "bind #$bindCount step-1: Preview-only at $desiredSize " +
            "(target=$targetShortSide scanEnabled=$SCAN_ENABLED)",
        )
        camera = provider.bindToLifecycle(this, selector, previewUseCase)
        startOrientationListener()

        previewUseCase.resolutionInfo?.resolution?.let {
            bufW = it.width; bufH = it.height
        }
        recomputePreviewSize()

        emptyFrames = 0
        diagFramesSeen = 0

        // ---- Wait for OPEN, then step 2 ----
        waitForCameraOpen("bind#$bindCount-step1") {
            try {
                if (firstFrameReceived) {
                    android.util.Log.i(
                        TAG,
                        "bind #$bindCount step-2 skipped: frame already received",
                    )
                    bindStage = 0
                    return@waitForCameraOpen
                }
                if (cameraProvider == null || textureEntry == null) {
                    android.util.Log.w(
                        TAG,
                        "bind #$bindCount step-2 skipped: provider or entry gone",
                    )
                    bindStage = 0
                    return@waitForCameraOpen
                }
                val viewPort = ViewPort.Builder(
                    Rational(16, 9),
                    lastTargetRotation,
                ).build()
                val group = UseCaseGroup.Builder()
                    .addUseCase(previewUseCase)
                    .addUseCase(analysisUseCase)
                    .setViewPort(viewPort)
                    .build()
                bindStage = 2
                android.util.Log.i(
                    TAG,
                    "bind #$bindCount step-2: UseCaseGroup at $desiredSize " +
                    "(viewPort=16:9 rot=$lastTargetRotation)",
                )
                camera = provider.bindToLifecycle(this, selector, group)
                analysisUseCase.resolutionInfo?.resolution?.let {
                    android.util.Log.i(
                        TAG,
                        "bind #$bindCount analysis resolved to ${it.width}x${it.height}",
                    )
                }

                waitForCameraOpen("bind#$bindCount-step2") {
                    bindStage = 0
                    android.util.Log.i(
                        TAG,
                        "bind #$bindCount complete, waiting for frames",
                    )
                }
            } catch (e: Throwable) {
                android.util.Log.e(TAG, "bind #$bindCount step-2 failed", e)
                bindStage = 0
            }
        }

        focusAt(0.5, 0.5)
    }

    // ---------- CameraState waiting ----------

    /**
     * A pending CameraState observer, with the timeout that will fire if
     * OPEN never arrives. Held so the sequence can be cancelled cleanly
     * when a rebind or teardown comes in mid-wait.
     */
    private class CameraOpenWaiter(
        val observer: Observer<CameraState>,
        val timeout: Runnable,
        val camera: Camera,
    )

    /**
     * Waits for [Camera] to reach [CameraState.Type.OPEN], then schedules
     * [onOpen] after [OPEN_SETTLE_MS]. If OPEN has not been seen within
     * [OPEN_TIMEOUT_MS], [onOpen] runs anyway — a stuck CameraState is
     * worse than a bind that fires a little early.
     *
     * Every call cancels the previous pending wait: only one wait can be
     * outstanding at a time, which is exactly the sequence's structure
     * (step-1 wait, then step-2 wait, never both).
     */
    private fun waitForCameraOpen(
        label: String,
        onOpen: () -> Unit,
    ) {
        cancelCameraOpenWait()
        val cam = camera
        if (cam == null) {
            android.util.Log.w(TAG, "[$label] no camera reference, waiting 500ms")
            val r = Runnable {
                if (!firstFrameReceived) onOpen()
            }
            pendingBinds.add(r)
            main.postDelayed(r, 500L)
            return
        }

        var fired = false

        val observer = object : Observer<CameraState> {
            override fun onChanged(state: CameraState) {
                if (fired) return
                if (state.type == CameraState.Type.OPEN) {
                    fired = true
                    android.util.Log.i(TAG, "[$label] state=OPEN")
                    try {
                        cam.cameraInfo.cameraState.removeObserver(this)
                    } catch (_: Throwable) {}
                    clearWaiterField()
                    val r = Runnable {
                        if (!firstFrameReceived) onOpen()
                    }
                    pendingBinds.add(r)
                    main.postDelayed(r, OPEN_SETTLE_MS)
                }
            }
        }

        val timeout = Runnable {
            if (fired) return@Runnable
            fired = true
            android.util.Log.w(
                TAG,
                "[$label] OPEN not reached in ${OPEN_TIMEOUT_MS}ms; proceeding anyway",
            )
            try {
                cam.cameraInfo.cameraState.removeObserver(observer)
            } catch (_: Throwable) {}
            clearWaiterField()
            val r = Runnable {
                if (!firstFrameReceived) onOpen()
            }
            pendingBinds.add(r)
            main.postDelayed(r, OPEN_SETTLE_MS)
        }

        try {
            cam.cameraInfo.cameraState.observeForever(observer)
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "[$label] observeForever failed: ${e.message}", e)
            val r = Runnable {
                if (!firstFrameReceived) onOpen()
            }
            pendingBinds.add(r)
            main.postDelayed(r, 500L)
            return
        }

        currentWaiter = CameraOpenWaiter(observer, timeout, cam)
        main.postDelayed(timeout, OPEN_TIMEOUT_MS)
    }

    private fun clearWaiterField() {
        currentWaiter = null
    }

    private fun cancelCameraOpenWait() {
        val w = currentWaiter ?: return
        currentWaiter = null
        try {
            w.camera.cameraInfo.cameraState.removeObserver(w.observer)
        } catch (_: Throwable) {}
        main.removeCallbacks(w.timeout)
    }

    private fun cancelPendingBinds() {
        for (r in pendingBinds) main.removeCallbacks(r)
        pendingBinds.clear()
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

    private fun copyYPlane(image: ImageProxy): Triple<ByteArray, Int, Int>? {
        val plane = image.planes[0]
        val remaining = plane.buffer.remaining()
        val stride = plane.rowStride
        val buf = ensureYBuffer(remaining)
        plane.buffer.get(buf, 0, remaining)
        return Triple(buf, remaining, stride)
    }

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

    private fun analyze(image: ImageProxy) {
        try {
            if (image.width == 0 || image.height == 0) {
                emptyFrames++
                if (emptyFrames == 5L || emptyFrames == 30L || emptyFrames == 120L) {
                    android.util.Log.w(
                        TAG,
                        "empty #$emptyFrames: ${image.width}x${image.height} " +
                        "format=${image.format} shortSide=$shortSide bindStage=$bindStage",
                    )
                }
                try { image.close() } catch (_: Throwable) {}
                return
            }
            if (emptyFrames > 0) {
                android.util.Log.i(TAG, "stream woke after $emptyFrames empty frames")
                emptyFrames = 0
            }

            if (!firstFrameReceived) {
                firstFrameReceived = true
                android.util.Log.i(
                    TAG,
                    "first frame arrived (${image.width}x${image.height} " +
                    "rot=${image.imageInfo.rotationDegrees}) — " +
                    "target=$targetShortSide current=$shortSide bindStage=$bindStage",
                )
            }

            val w = image.width
            val h = image.height
            val rot = image.imageInfo.rotationDegrees

            val copied = copyYPlane(image)
            if (copied == null) {
                try { image.close() } catch (_: Throwable) {}
                return
            }
            val (y, yLen, rowStride) = copied

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

            val desiredSize = targetSize(targetShortSide)
            val desiredPixels = desiredSize.width.toLong() * desiredSize.height
            val framePixels = w.toLong() * h

            if (framePixels > desiredPixels * DOWNSCALE_THRESHOLD) {
                val dstSize = desiredSize.width * desiredSize.height
                val dst = ensureDownscaleBuffer(dstSize)
                downscaleGrayInto(
                    y, dst, w, h, rowStride,
                    desiredSize.width, desiredSize.height,
                )
                android.util.Log.i(
                    TAG,
                    "downscale: ${w}x$h -> ${desiredSize.width}x${desiredSize.height}",
                )
                analyzedFrames++
                busy = true
                try {
                    submitScan(dst, desiredSize.width, desiredSize.height, desiredSize.width, rot)
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    busy = false
                }
            } else {
                analyzedFrames++
                busy = true
                try {
                    submitScan(y, w, h, rowStride, rot)
                } catch (_: java.util.concurrent.RejectedExecutionException) {
                    busy = false
                }
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
            cancelCameraOpenWait()
            cancelPendingBinds()
            firstFrameReceived = false
            bindStage = 0
            bindCount = 0
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
            releaseScanner()
        } catch (e: Throwable) {
            android.util.Log.e(TAG, "teardown failed", e)
        }
    }
}