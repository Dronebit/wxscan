import 'dart:async';
import 'dart:developer' as developer;
import 'dart:math' as math;

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:wxscan_live/wxscan_live.dart';

import 'pick_overlay.dart';
import 'pick_page.dart';
import 'result_page.dart';
import 'scanner.dart';

/// The scanning screen: camera preview, a viewfinder, and the corners of
/// whatever was decoded highlighted.
class ScanPage extends StatefulWidget {
  const ScanPage({super.key});

  @override
  State<ScanPage> createState() => _ScanPageState();
}

class _ScanPageState extends State<ScanPage> with WidgetsBindingObserver {
  WxScanController? _controller;
  StreamSubscription<ScanOutcome>? _sub;
  String? _error;
  bool _hasTorch = false;
  bool _nnEnabled = false;

  ScanOutcome? _lastFrame;
  ScanOutcome? _pickFrame;
  bool _navigating = false;
  double _maxZoom = 1;

  double get _zoom => _controller?.value.zoom ?? 1;

  void _onControllerChanged() {
    if (mounted) setState(() {});
  }

  int _undecodableStreak = 0;
  int _emptyStreak = 0;
  DateTime? _nothingSince;
  DateTime? _multiWaitSince;

  WxResolution _resolution = WxResolution.p720;

  DateTime _lastZoomAt = DateTime.fromMillisecondsSinceEpoch(0);
  double _gestureZoomBase = 1;
  DateTime _manualZoomAt = DateTime.fromMillisecondsSinceEpoch(0);

  Uint8List? _frozenFrame;
  DateTime _pickExitAt = DateTime.fromMillisecondsSinceEpoch(0);

  Offset? _focusPoint;
  Timer? _focusTimer;
  DateTime _manualFocusAt = DateTime.fromMillisecondsSinceEpoch(0);
  DateTime _lastAutoFocusAt = DateTime.fromMillisecondsSinceEpoch(0);
  Offset? _lastAutoFocusPoint;

  double _zoomTarget = 1;
  Timer? _zoomDriver;
  var _zoomBusy = false;
  double _zoomRate = 0;
  DateTime _zoomSteppedAt = DateTime.fromMillisecondsSinceEpoch(0);

  Timer? _watchdogTimer;
  int _rawFrameCount = 0;
  String _lastFrameInfo = 'waiting for first frame';
  final List<String> _logBuffer = [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _boot();
  }

  Future<void> _boot() async {
    _log('boot: start');

    _watchdogTimer?.cancel();
    _watchdogTimer = null;
    _rawFrameCount = 0;
    _lastFrameInfo = 'waiting for first frame';
    _sub?.cancel();
    _sub = null;
    final previous = _controller;
    if (previous != null) {
      previous.removeListener(_onControllerChanged);
      previous.dispose();
      _controller = null;
    }

    try {
      _nnEnabled = await Scanner.init();
      if (kDebugMode) {
        unawaited(Scanner.selfTest());
      }

      if (defaultTargetPlatform == TargetPlatform.android ||
          defaultTargetPlatform == TargetPlatform.iOS) {
        final status = await Permission.camera.request();
        if (!status.isGranted) {
          if (mounted) {
            setState(() => _error = 'Camera permission is needed to scan');
          }
          return;
        }
      }

      final controller = WxScanController(
        scanner: Scanner.instance,
        resolution: _resolution,
      );
      await controller.initialize(
        detectModel: Scanner.detectModel,
        srModel: Scanner.srModel,
        detectModelPath: Scanner.detectModelPath,
        srModelPath: Scanner.srModelPath,
      );
      _log(
        'init ok: nativeReady=${controller.value.nativeReady} '
        'previewSize=${controller.value.previewSize}',
      );
      if (kDebugMode) {
        unawaited(Scanner.selfTestCameraPath());
      }
      final hasTorch = await controller.hasTorch();
      final zoom = await controller.zoomRange();
      _maxZoom = zoom.max;
      if (!mounted) {
        controller.dispose();
        return;
      }
      controller.addListener(_onControllerChanged);
      _sub = controller.scans.listen(_onFrame, onError: (Object e, StackTrace st) {
        _log('scan stream error: $e');
      });
      setState(() {
        _controller = controller;
        _hasTorch = hasTorch;
        _error = controller.value.nativeReady
            ? null
            : 'The native scanner failed to load';
      });

      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (!mounted || _controller == null) return;
        _controller!.setScanning(true);
        _log('scanning started post-frame');
      });

      _watchdogTimer = Timer(const Duration(seconds: 7), () {
        if (!mounted || _rawFrameCount > 0) return;
        _log('watchdog: no frames in 7s');
        setState(() {
          _error = 'The camera is not delivering frames.\n'
              'Try a different resolution in the top bar, or restart the app.';
        });
      });
    } on PlatformException catch (e) {
      if (!mounted) return;
      _log('boot failed: ${e.code}: ${e.message}');
      setState(() => _error = '${e.code}: ${e.message}');
    } catch (e) {
      if (!mounted) return;
      _log('boot failed: $e');
      setState(() => _error = '$e');
    }
  }

  void _onFrame(ScanOutcome frame) {
    _rawFrameCount++;

    if (_rawFrameCount <= 5) {
      _log(
        'frame #$_rawFrameCount: ${frame.width}x${frame.height} '
        'results=${frame.results.length} '
        'candidates=${frame.candidates.length}',
      );
    }

    if (_rawFrameCount == 1) {
      _watchdogTimer?.cancel();
      _watchdogTimer = null;
      if (_error != null && _error!.contains('not delivering frames')) {
        setState(() => _error = null);
      }
    }

    if (frame.width == 0 || frame.height == 0) {
      if (mounted) {
        setState(() => _lastFrameInfo = 'frame #$_rawFrameCount: 0x0 (driver)');
      } else {
        _lastFrameInfo = 'frame #$_rawFrameCount: 0x0 (driver)';
      }
      return;
    }

    if (mounted) {
      setState(() => _lastFrameInfo = 'frame #$_rawFrameCount: '
          '${frame.width}x${frame.height} '
          'r=${frame.results.length} c=${frame.candidates.length}');
    } else {
      _lastFrameInfo = 'frame #$_rawFrameCount: '
          '${frame.width}x${frame.height} '
          'r=${frame.results.length} c=${frame.candidates.length}';
    }

    if (!mounted || _navigating) return;
    setState(() => _lastFrame = frame);

    if (_pickFrame != null) return;

    if (frame.results.isEmpty) {
      _multiWaitSince = null;
      _autoZoom(frame);
      _autoFocus(frame);
      _blindFocus(frame);
      return;
    }
    _resetZoom();
    _nothingSince = null;

    if (frame.results.length > 1) {
      _multiWaitSince = null;
      HapticFeedback.mediumImpact();
      _enterPick(frame);
      return;
    }

    if (frame.candidates.length > 1) {
      final now = DateTime.now();
      _multiWaitSince ??= now;
      if (now.difference(_multiWaitSince!) < _kMultiCodeGrace) return;
    }
    _multiWaitSince = null;
    _openResults(frame.results);
  }

  Future<void> _enterPick(ScanOutcome frame) async {
    setState(() => _pickFrame = frame);
    await _controller!.setScanning(false);
    final jpeg = await _controller!.grabFrame();
    if (!mounted || _pickFrame == null) return;
    setState(() => _frozenFrame = jpeg);
  }

  void _exitPick() {
    _multiWaitSince = null;
    setState(() {
      _pickFrame = null;
      _frozenFrame = null;
      _lastFrame = null;
    });
    _pickExitAt = DateTime.now();
    _controller!.setScanning(true);
  }

  void _openResults(List<ScanResult> results) {
    _navigating = true;
    _controller!.setScanning(false);
    HapticFeedback.mediumImpact();
    Navigator.of(context)
        .push(MaterialPageRoute(builder: (_) => ResultPage(results: results)))
        .then((_) {
          _navigating = false;
          _multiWaitSince = null;
          if (mounted) {
            setState(() {
              _pickFrame = null;
              _frozenFrame = null;
              _lastFrame = null;
            });
          }
          _resetZoom();
          _controller!.setScanning(true);
        });
  }

  void _autoZoom(ScanOutcome frame) {
    final nowTs = DateTime.now();
    if (nowTs.difference(_manualZoomAt) < _kManualZoomHold) return;
    if (nowTs.difference(_pickExitAt) < _kPickExitZoomHold) return;
    if (!frame.hasUndecodable) {
      _undecodableStreak = 0;
      if (frame.candidates.isEmpty && ++_emptyStreak > 45) _resetZoom();
      return;
    }
    _emptyStreak = 0;
    if (++_undecodableStreak < 3) return;

    final box = _largestCandidateBox(frame);
    if (box == null) return;
    final frac = math.max(box.w, box.h);
    if (frac <= 0 || frac >= _kZoomTargetFraction) return;

    final rel = math.min(_kZoomTargetFraction / frac, _safeZoomHeadroom(box));
    final want = (_zoom * rel)
        .clamp(1.0, math.min(_maxZoom, _kMaxAutoZoom))
        .toDouble();
    if (want <= _zoom * 1.02) return;

    _zoomTarget = want;
    _startZoomDriver();
  }

  void _startZoomDriver() {
    if (_zoomDriver != null) return;
    _zoomRate = 0;
    _zoomSteppedAt = DateTime.now();
    _zoomDriver = Timer.periodic(_kZoomStepEvery, (_) => _stepZoom());
  }

  void _stopZoomDriver() {
    _zoomDriver?.cancel();
    _zoomDriver = null;
    _zoomRate = 0;
  }

  void _stepZoom() {
    if (_zoomBusy) return;
    final from = _zoom;
    if ((_zoomTarget / from - 1).abs() < 0.005) {
      _stopZoomDriver();
      return;
    }

    final now = DateTime.now();
    final dt = now.difference(_zoomSteppedAt).inMicroseconds / 1e6;
    _zoomSteppedAt = now;
    if (dt <= 0) return;

    _zoomRate = math.min(
      1.0,
      _zoomRate + dt / (_kZoomRampIn.inMilliseconds / 1000),
    );
    final k = 1 - math.exp(-dt / (_kZoomSettle.inMilliseconds / 1000));

    final logFrom = math.log(from);
    final next = math.exp(
      logFrom + (math.log(_zoomTarget) - logFrom) * k * _zoomRate,
    );

    _zoomBusy = true;
    _controller!.setZoom(next).then((actual) {
      _zoomBusy = false;
      if (!mounted) return;
      setState(() {});
      if ((actual - from).abs() < 0.0005 && _zoomRate >= 1) {
        _zoomTarget = actual;
        _stopZoomDriver();
      }
    });
  }

  void _autoFocus(ScanOutcome frame) {
    if (!frame.hasUndecodable) return;

    final now = DateTime.now();
    if (now.difference(_manualFocusAt) < _kManualFocusHold) return;
    if (now.difference(_lastAutoFocusAt) < _kAutoFocusInterval) return;

    final box = _largestCandidateBox(frame);
    if (box == null) return;
    final frac = math.max(box.w, box.h);
    if (frac <= 0 || frac >= _kAutoFocusMaxFraction) return;

    final point = Offset(box.cx, box.cy);
    final last = _lastAutoFocusPoint;
    if (last != null &&
        (point - last).distance < _kAutoFocusMoved &&
        now.difference(_lastAutoFocusAt) < _kAutoFocusRepeat) {
      return;
    }

    final turns = _controller?.value.previewSize?.quarterTurns ?? 0;
    final (x, y) = _previewFraction(box.cx, box.cy, turns);
    if (x < 0 || x > 1 || y < 0 || y > 1) return;

    _lastAutoFocusAt = now;
    _lastAutoFocusPoint = point;
    unawaited(_controller!.focusAt(x, y));
  }

  void _blindFocus(ScanOutcome frame) {
    final now = DateTime.now();
    if (frame.candidates.isNotEmpty) {
      _nothingSince = null;
      return;
    }
    final since = _nothingSince ??= now;
    if (now.difference(since) < _kBlindFocusAfter) return;

    if (now.difference(_manualFocusAt) < _kManualFocusHold) return;
    if (now.difference(_lastAutoFocusAt) < _kBlindFocusInterval) return;

    _lastAutoFocusAt = now;
    _lastAutoFocusPoint = null;
    unawaited(_controller!.focusAt(0.5, 0.5));
  }

  void _resetZoom() {
    _undecodableStreak = 0;
    _emptyStreak = 0;
    if (_zoomTarget == 1 && _zoom == 1) return;
    _zoomTarget = 1;
    _startZoomDriver();
  }

  ({double cx, double cy, double w, double h})? _largestCandidateBox(
    ScanOutcome f,
  ) {
    if (f.width == 0 || f.height == 0) return null;
    ({double cx, double cy, double w, double h})? best;
    var bestArea = 0.0;
    for (final c in f.candidates) {
      if (c.length < 4) continue;
      var minX = double.infinity, maxX = -double.infinity;
      var minY = double.infinity, maxY = -double.infinity;
      for (final p in c) {
        minX = math.min(minX, p.dx);
        maxX = math.max(maxX, p.dx);
        minY = math.min(minY, p.dy);
        maxY = math.max(maxY, p.dy);
      }
      final w = (maxX - minX) / f.width;
      final h = (maxY - minY) / f.height;
      if (w * h > bestArea) {
        bestArea = w * h;
        best = (
          cx: (minX + maxX) / 2 / f.width,
          cy: (minY + maxY) / 2 / f.height,
          w: w,
          h: h,
        );
      }
    }
    return best;
  }

  double _safeZoomHeadroom(({double cx, double cy, double w, double h}) box) {
    double axis(double center, double size) {
      final margin = (center - 0.5).abs() + size / 2;
      return margin <= 0 ? _kMaxAutoZoom : 1 / (2 * margin);
    }

    return math.min(axis(box.cx, box.w), axis(box.cy, box.h));
  }

  void _onScaleStart(ScaleStartDetails d) {
    if (d.pointerCount >= 2) _gestureZoomBase = _zoom;
  }

  void _onScaleUpdate(ScaleUpdateDetails d) {
    if (d.pointerCount < 2) return;
    final now = DateTime.now();
    if (now.difference(_lastZoomAt) < const Duration(milliseconds: 60)) return;

    final want = (_gestureZoomBase * d.scale).clamp(1.0, _maxZoom).toDouble();
    if ((want - _zoom).abs() < 0.02) return;
    _lastZoomAt = now;
    _manualZoomAt = now;
    _stopZoomDriver();
    _zoomTarget = want;
    _controller!.setZoom(want).then((actual) {
      if (mounted) setState(() {});
    });
  }

  void _onFocusTap(Offset tap, Size box) {
    final size = _controller?.value.previewSize;
    if (size == null || _controller == null) return;

    final m = _coverFit(box, size.rotatedWidth, size.rotatedHeight);
    final rx = (tap.dx - m.dx) / (size.rotatedWidth * m.scale);
    final ry = (tap.dy - m.dy) / (size.rotatedHeight * m.scale);
    if (rx < 0 || rx > 1 || ry < 0 || ry > 1) return;

    final (x, y) = _previewFraction(rx, ry, size.quarterTurns);

    _manualFocusAt = DateTime.now();
    _focusTimer?.cancel();
    setState(() => _focusPoint = tap);
    _focusTimer = Timer(const Duration(milliseconds: 900), () {
      if (mounted) setState(() => _focusPoint = null);
    });
    unawaited(_controller!.focusAt(x, y));
  }

  Widget _buildFocusLayer() {
    return LayoutBuilder(
      builder: (context, constraints) {
        final box = Size(constraints.maxWidth, constraints.maxHeight);
        return GestureDetector(
          behavior: HitTestBehavior.translucent,
          onTapUp: (d) => _onFocusTap(d.localPosition, box),
          child: const SizedBox.expand(),
        );
      },
    );
  }

  Widget _buildPicker(ScanOutcome frame) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final size = Size(constraints.maxWidth, constraints.maxHeight);
        return GestureDetector(
          behavior: HitTestBehavior.opaque,
          onTapDown: (d) {
            final hit = pickHitTest(d.localPosition, BoxFit.cover, size, frame);
            if (hit != null) {
              _openResults([hit]);
            } else {
              _exitPick();
            }
          },
          child: Stack(
            fit: StackFit.expand,
            children: [
              if (_frozenFrame != null)
                Image.memory(
                  _frozenFrame!,
                  fit: BoxFit.cover,
                  gaplessPlayback: true,
                ),
              CustomPaint(
                size: Size.infinite,
                painter: PickPainter(frame: frame, fit: BoxFit.cover),
              ),
            ],
          ),
        );
      },
    );
  }

  Future<void> _cycleResolution() async {
    const order = WxResolution.values;
    final next = order[(order.indexOf(_resolution) + 1) % order.length];
    setState(() {
      _resolution = next;
      _lastFrame = null;
      _rawFrameCount = 0;
      _lastFrameInfo = 'changing to ${next.label}...';
    });
    try {
      await _controller!.setResolution(next);
      _log('resolution changed to ${next.label}');
    } on PlatformException catch (e) {
      _log('resolution change failed: ${e.code}: ${e.message}');
      if (mounted) setState(() => _error = '${e.code}: ${e.message}');
    }
  }

  Future<void> _openDebugPanel() async {
    await Navigator.of(context).push(
      MaterialPageRoute<void>(
        builder: (_) => _DebugPanelPage(state: this),
      ),
    );
  }

  Future<void> _pickFromGallery() async {
    final PickedPicture? picked;
    try {
      picked = await Scanner.pickAndScan();
    } on UnreadableImage {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text('That file is not a picture this device can read'),
          ),
        );
      }
      return;
    }
    if (picked == null || !mounted) return;
    final found = picked.outcome;
    if (found.results.isEmpty) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            found.candidates.isEmpty
                ? 'No QR code found in that picture'
                : 'A code was spotted but could not be read — it may be too small '
                      'in the picture, or too blurred',
          ),
        ),
      );
      return;
    }
    _navigating = true;
    await _controller!.setScanning(false);
    if (!mounted) return;
    var results = found.results;
    if (results.length > 1) {
      final image = await picked.file.readAsBytes();
      if (!mounted) return;
      final chosen = await Navigator.of(context).push<ScanResult>(
        MaterialPageRoute<ScanResult>(
          builder: (_) => PickPage(image: image, outcome: found),
        ),
      );
      if (chosen == null) {
        _navigating = false;
        await _controller!.setScanning(true);
        return;
      }
      results = [chosen];
      if (!mounted) return;
    }
    await Navigator.of(
      context,
    ).push(MaterialPageRoute(builder: (_) => ResultPage(results: results)));
    _navigating = false;
    await _controller!.setScanning(true);
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _controller?.setScanning(!_navigating);
    } else {
      _controller?.setScanning(false);
    }
  }

  @override
  void dispose() {
    _watchdogTimer?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    _sub?.cancel();
    _controller?.removeListener(_onControllerChanged);
    _controller?.dispose();
    _controller = null;
    _focusTimer?.cancel();
    _zoomDriver?.cancel();
    super.dispose();
  }

  void _log(String msg) {
    final stamp = DateTime.now().toIso8601String().substring(11, 23);
    _logBuffer.add('$stamp $msg');
    if (_logBuffer.length > 60) _logBuffer.removeAt(0);
    developer.log(msg, name: 'wxscan.scan');
    // ignore: avoid_print
    print('[wxscan.scan] $msg');
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: _pickFrame == null,
      onPopInvokedWithResult: (didPop, _) {
        if (didPop) return;
        _exitPick();
      },
      child: Scaffold(
        backgroundColor: Colors.black,
        body: GestureDetector(
          behavior: HitTestBehavior.deferToChild,
          onScaleStart: _onScaleStart,
          onScaleUpdate: _onScaleUpdate,
          child: _Shell(
            child: Stack(
              fit: StackFit.expand,
              children: [
                if (_controller != null) _buildPreview(_controller!),
                const IgnorePointer(child: _ScanLineOverlay()),
                if (_lastFrame != null &&
                    _controller != null &&
                    _pickFrame == null)
                  IgnorePointer(
                    child: CustomPaint(
                      painter: _CodeMarkerPainter(frame: _lastFrame!),
                    ),
                  ),
                if (_controller != null) _buildFocusLayer(),
                if (_focusPoint != null) _FocusReticle(at: _focusPoint!),
                if (_pickFrame != null) _buildPicker(_pickFrame!),
                _buildTopBar(),
                if (_error != null) _buildError(_error!),
                _buildBottomBar(),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildPreview(WxScanController controller) {
    final size = controller.value.previewSize;
    if (size == null) return const SizedBox.shrink();
    return ClipRect(
      child: FittedBox(
        fit: BoxFit.cover,
        child: SizedBox(
          width: size.rotatedWidth.toDouble(),
          height: size.rotatedHeight.toDouble(),
          child: RotatedBox(
            quarterTurns: size.quarterTurns,
            child: SizedBox(
              width: size.width.toDouble(),
              height: size.height.toDouble(),
              child: WxScanPreview(controller: controller),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildTopBar() {
    return Align(
      alignment: Alignment.topCenter,
      child: SafeArea(
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 8),
          child: _pickFrame != null ? _buildPickBar() : _buildScanBar(),
        ),
      ),
    );
  }

  Widget _buildPickBar() {
    return Row(
      children: [
        IconButton(
          onPressed: _exitPick,
          tooltip: 'Back',
          style: IconButton.styleFrom(
            backgroundColor: Colors.black54,
            foregroundColor: Colors.white,
          ),
          icon: const Icon(Icons.arrow_back),
        ),
        const SizedBox(width: 12),
        const Expanded(child: _Hint('Tap a marker to open that code')),
      ],
    );
  }

  static const double _barLine = 48;

  Widget _buildScanBar() {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          height: _barLine,
          child: Row(
            children: [
              IconButton(
                onPressed: () => Navigator.of(context).maybePop(),
                tooltip: 'Back',
                style: IconButton.styleFrom(
                  backgroundColor: Colors.black38,
                  foregroundColor: Colors.white,
                ),
                icon: const Icon(Icons.arrow_back),
              ),
              const SizedBox(width: 8),
              const Text(
                'Scan',
                style: TextStyle(
                  color: Colors.white,
                  fontSize: 18,
                  fontWeight: FontWeight.w600,
                ),
              ),
            ],
          ),
        ),
        const SizedBox(width: 12),
        Expanded(
          child: ConstrainedBox(
            constraints: const BoxConstraints(minHeight: _barLine),
            child: Align(
              alignment: Alignment.centerRight,
              heightFactor: 1,
              child: Wrap(
                alignment: WrapAlignment.end,
                spacing: 8,
                runSpacing: 6,
                children: [
                  _chip(
                    _resolution.label,
                    onTap: _controller == null ? null : _cycleResolution,
                    icon: Icons.hd_outlined,
                  ),
                  _chip(
                    _nnEnabled ? 'CNN detection on' : 'Image processing only',
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }

  Widget _chip(String text, {VoidCallback? onTap, IconData? icon}) {
    final body = Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: Colors.white12,
        borderRadius: BorderRadius.circular(12),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[
            Icon(icon, size: 14, color: Colors.white70),
            const SizedBox(width: 4),
          ],
          Text(
            text,
            style: const TextStyle(color: Colors.white70, fontSize: 12),
          ),
        ],
      ),
    );
    if (onTap == null) return body;
    return GestureDetector(onTap: onTap, child: body);
  }

  Widget _buildError(String message) {
    return Center(
      child: Container(
        margin: const EdgeInsets.symmetric(horizontal: 32),
        padding: const EdgeInsets.all(16),
        decoration: BoxDecoration(
          color: Colors.black87,
          borderRadius: BorderRadius.circular(12),
        ),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(Icons.error_outline, color: Colors.orange, size: 32),
            const SizedBox(height: 12),
            Text(
              message,
              textAlign: TextAlign.center,
              style: const TextStyle(color: Colors.white),
            ),
            const SizedBox(height: 12),
            FilledButton(
              onPressed: () {
                setState(() => _error = null);
                _boot();
              },
              child: const Text('Retry'),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildBottomBar() {
    if (_pickFrame != null) return const SizedBox.shrink();
    return Align(
      alignment: Alignment.bottomCenter,
      child: SafeArea(
        child: Padding(
          padding: EdgeInsets.only(
            bottom: MediaQuery.sizeOf(context).height < 560 ? 12 : 40,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              _Hint(
                _zoom > 1
                    ? '${_zoom.toStringAsFixed(1)}x - pinch to zoom'
                    : 'Put the QR code in the frame to scan it',
              ),
              const SizedBox(height: 8),
              Container(
                padding: const EdgeInsets.symmetric(
                  horizontal: 12,
                  vertical: 6,
                ),
                decoration: BoxDecoration(
                  color: Colors.red.withValues(alpha: 0.85),
                  borderRadius: BorderRadius.circular(6),
                ),
                child: Text(
                  _lastFrameInfo,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 12,
                    fontFamily: 'monospace',
                    fontWeight: FontWeight.w700,
                  ),
                ),
              ),
              const SizedBox(height: 20),
              Row(
                mainAxisAlignment: MainAxisAlignment.center,
                children: [
                  if (_hasTorch)
                    IconButton.filledTonal(
                      onPressed: () async {
                        await _controller!.setTorch(
                          !_controller!.value.torchEnabled,
                        );
                        setState(() {});
                      },
                      icon: Icon(
                        _controller!.value.torchEnabled
                            ? Icons.flashlight_on
                            : Icons.flashlight_off,
                      ),
                    ),
                  const SizedBox(width: 24),
                  IconButton.filledTonal(
                    onPressed: _pickFromGallery,
                    icon: const Icon(Icons.photo_library_outlined),
                  ),
                  const SizedBox(width: 24),
                  IconButton.filledTonal(
                    onPressed: _openDebugPanel,
                    icon: const Icon(Icons.bug_report_outlined),
                  ),
                ],
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Diagnostic panel that reads the plugin's `debugState` map every 700 ms
/// and renders it grouped by what it answers: what the controller thinks,
/// what the analyser is receiving, what the scanner is doing. No native
/// calls of its own — it works even when the native layer is dead.
class _DebugPanelPage extends StatefulWidget {
  final _ScanPageState state;
  const _DebugPanelPage({required this.state});

  @override
  State<_DebugPanelPage> createState() => _DebugPanelPageState();
}

class _DebugPanelPageState extends State<_DebugPanelPage> {
  Timer? _poll;
  Map<String, dynamic> _native = {};

  @override
  void initState() {
    super.initState();
    _refresh();
    _poll = Timer.periodic(
      const Duration(milliseconds: 700),
      (_) { _refresh(); },
    );
  }

  Future<void> _refresh() async {
    try {
      final r = await const MethodChannel('wxscan_live')
          .invokeMethod<Map>('debugState');
      if (!mounted) return;
      setState(() => _native = Map<String, dynamic>.from(r ?? {}));
    } catch (e) {
      if (!mounted) return;
      setState(() => _native = {'error': e.toString()});
    }
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  Future<void> _copy() async {
    final b = StringBuffer();
    b.writeln('=== wxscan debug ===');
    b.writeln('generated: ${DateTime.now()}');
    b.writeln();
    b.writeln('--- native state ---');
    for (final e in _native.entries) {
      b.writeln('${e.key}: ${e.value}');
    }
    b.writeln();
    b.writeln('--- dart counters ---');
    b.writeln('frames received: ${widget.state._rawFrameCount}');
    b.writeln('last frame info: ${widget.state._lastFrameInfo}');
    await Clipboard.setData(ClipboardData(text: b.toString()));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(content: Text('Copied to clipboard')),
    );
  }

  String _formatName(int? f) {
    switch (f) {
      case 35:  return 'YUV_420_888  ✓ correct';
      case 1:   return 'RGBA_8888   ✗ WRONG (4 bytes/px, Rust sees garbage)';
      case 17:  return 'NV21        ✗ wrong (Y then VU interleaved)';
      case 256: return 'JPEG        ✗ wrong';
      case -1:  return '(no frame yet)';
      default:  return 'unknown';
    }
  }

  String _planesHint(int? planes) {
    switch (planes) {
      case 1: return '(1 = RGBA/NV21, wrong)';
      case 3: return '(3 = YUV, correct)';
      case null:
      case 0: return '(no frame)';
      default: return '(unexpected)';
    }
  }

  String _pixelStrideHint(int? stride) {
    switch (stride) {
      case 1: return '(1 = Y-plane, correct)';
      case 4: return '(4 = RGBA, wrong)';
      case null:
      case 0: return '(none)';
      default: return '(unexpected)';
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.state;
    final v = s._controller?.value;
    final fmt = _native['lastImageFormat'] as int?;

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black87,
        foregroundColor: Colors.white,
        title: const Text('wxscan debug'),
        actions: [
          IconButton(
            icon: const Icon(Icons.copy),
            onPressed: _copy,
            tooltip: 'Copy all',
          ),
        ],
      ),
      body: SafeArea(
        child: SingleChildScrollView(
          padding: const EdgeInsets.all(12),
          child: DefaultTextStyle(
            style: const TextStyle(
              color: Colors.white,
              fontFamily: 'monospace',
              fontSize: 12,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                _section('DART: controller', {
                  'set': s._controller != null,
                  'nativeReady': v?.nativeReady,
                  'previewSize': v?.previewSize,
                  'torchEnabled': v?.torchEnabled,
                  'zoom': v?.zoom?.toStringAsFixed(2),
                  'resolution': v?.resolution,
                }),
                _section('DART: stream', {
                  'rawFrameCount': s._rawFrameCount,
                  'lastFrameInfo': s._lastFrameInfo,
                  'nnEnabled': s._nnEnabled,
                  'weightsProblem': Scanner.weightsProblem ?? '(none)',
                }),
                _section('NATIVE: frame format (ImageAnalysis output)', {
                  'imageFormat':
                      fmt != null ? '$fmt — ${_formatName(fmt)}' : '(none)',
                  'planes count':
                      '${_native['lastPlanesCount']} '
                      '${_planesHint(_native['lastPlanesCount'] as int?)}',
                  'pixelStride':
                      '${_native['lastPixelStride']} '
                      '${_pixelStrideHint(_native['lastPixelStride'] as int?)}',
                  'rowStride': _native['lastRowStride'],
                  'frame size':
                      '${_native['lastFrameW']}x${_native['lastFrameH']}',
                  'frame rotation': _native['lastFrameRot'],
                }),
                _section('NATIVE: scanner', {
                  'modelsLoaded': _native['modelsLoaded'],
                  'scannerHandle': _native['scannerHandle'],
                  'busy': _native['busy'],
                  'scanning': _native['scanning'],
                  'bindCount': _native['bindCount'],
                  'firstFrameReceived': _native['firstFrameReceived'],
                }),
                _section('NATIVE: pipeline', {
                  'hasProvider': _native['hasProvider'],
                  'hasCamera': _native['hasCamera'],
                  'hasPreview': _native['hasPreview'],
                  'hasAnalysis': _native['hasAnalysis'],
                  'scanEnabled': _native['scanEnabled'],
                  'desiredSize': _native['desiredSize'],
                  'shortSide': _native['shortSide'],
                  'targetShortSide': _native['targetShortSide'],
                }),
                _section('NATIVE: last frame buffer', {
                  'size': '${_native['diagW']}x${_native['diagH']}',
                  'stride': _native['diagStride'],
                  'len': _native['diagLen'],
                  'min/max/avg':
                      '${_native['diagMin']} / ${_native['diagMax']} / '
                      '${_native['diagAvg']}',
                  'first16': _native['diagFirst16'],
                  'frames seen': _native['diagFramesSeen'],
                  'analyzed': _native['analyzedFrames'],
                  'skipped': _native['skippedFrames'],
                  'empty': _native['emptyFrames'],
                }),
                _section('DART: last 30 log lines', {
                  for (var i = 0; i < s._logBuffer.length; i++)
                    '#$i': s._logBuffer[i],
                }),
                const SizedBox(height: 20),
                const Text(
                  'If imageFormat is 1 (RGBA_8888): the HAL ignored YUV, and '
                  'Rust is reading 1/4 of the pixels as grayscale — the '
                  'detector still finds a QR shape, the decoder cannot read '
                  'it. That is the "candidates > 0, results = 0" symptom.\n\n'
                  'If imageFormat is 35 (YUV_420_888), 3 planes, pixelStride '
                  '1: format is correct. If results are still 0, the frame '
                  'is fine and the decoder is the problem — try a larger QR, '
                  'or switch to 1080p, or check that sr.tflite loaded.',
                  style: TextStyle(color: Colors.white54, fontSize: 11),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _section(String title, Map<String, Object?> values) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 14),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            title,
            style: const TextStyle(
              color: Colors.amber,
              fontSize: 13,
              fontWeight: FontWeight.w700,
            ),
          ),
          const Divider(color: Colors.white24, height: 8),
          for (final e in values.entries)
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 1),
              child: Text('${e.key}: ${e.value}'),
            ),
        ],
      ),
    );
  }
}

/// Holds the scanner to a phone-shaped panel on a screen too big for it.
class _Shell extends StatelessWidget {
  const _Shell({required this.child});

  final Widget child;

  static const double _wideEnough = 720;
  static const double _tallEnough = 600;
  static const double _panelAspect = 0.52;

  @override
  Widget build(BuildContext context) {
    return LayoutBuilder(
      builder: (context, constraints) {
        final w = constraints.maxWidth, h = constraints.maxHeight;
        if (w < _wideEnough || h < _tallEnough) return child;

        final panelHeight = math.min(h - 48, 900.0);
        final panelWidth = math.min(w - 48, panelHeight * _panelAspect);
        return Center(
          child: SizedBox(
            width: panelWidth,
            height: panelHeight,
            child: ClipRRect(
              borderRadius: BorderRadius.circular(28),
              child: child,
            ),
          ),
        );
      },
    );
  }
}

/// A line of guidance over the camera picture.
class _Hint extends StatelessWidget {
  const _Hint(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 7),
        decoration: BoxDecoration(
          color: Colors.black.withValues(alpha: 0.55),
          borderRadius: BorderRadius.circular(16),
        ),
        child: Text(
          text,
          textAlign: TextAlign.center,
          style: const TextStyle(color: Colors.white, fontSize: 13),
        ),
      ),
    );
  }
}

/// The four corners a camera draws where it was told to focus.
class _FocusReticle extends StatefulWidget {
  const _FocusReticle({required this.at});

  final Offset at;

  @override
  State<_FocusReticle> createState() => _FocusReticleState();
}

class _FocusReticleState extends State<_FocusReticle>
    with SingleTickerProviderStateMixin {
  static const double _size = 78;

  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1100),
  )..forward();

  @override
  void didUpdateWidget(_FocusReticle old) {
    super.didUpdateWidget(old);
    if (widget.at != old.at) _controller.forward(from: 0);
  }

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  double _scaleAt(double t) {
    if (t < 0.24) {
      return 1.5 - 0.5 * Curves.easeOutCubic.transform(t / 0.24);
    }
    if (t < 0.46) {
      final p = (t - 0.24) / 0.22;
      return 1.0 - 0.08 * math.sin(p * math.pi);
    }
    return 1.0;
  }

  double _opacityAt(double t) {
    if (t < 0.06) return t / 0.06;
    if (t < 0.72) return 1.0;
    return 1.0 - (t - 0.72) / 0.28;
  }

  @override
  Widget build(BuildContext context) {
    return IgnorePointer(
      child: AnimatedBuilder(
        animation: _controller,
        builder: (context, _) {
          final t = _controller.value;
          final scale = _scaleAt(t);
          return Stack(
            children: [
              Positioned(
                left: widget.at.dx - _size / 2,
                top: widget.at.dy - _size / 2,
                child: Opacity(
                  opacity: _opacityAt(t).clamp(0.0, 1.0),
                  child: Transform.scale(
                    scale: scale,
                    child: CustomPaint(
                      size: const Size(_size, _size),
                      painter: _FocusCornersPainter(strokeScale: 1 / scale),
                    ),
                  ),
                ),
              ),
            ],
          );
        },
      ),
    );
  }
}

class _FocusCornersPainter extends CustomPainter {
  const _FocusCornersPainter({required this.strokeScale});

  final double strokeScale;

  @override
  void paint(Canvas canvas, Size size) {
    final arm = size.width * 0.28;
    final path = Path();
    for (final (cx, cy, sx, sy) in [
      (0.0, 0.0, 1.0, 1.0),
      (size.width, 0.0, -1.0, 1.0),
      (size.width, size.height, -1.0, -1.0),
      (0.0, size.height, 1.0, -1.0),
    ]) {
      path
        ..moveTo(cx + sx * arm, cy)
        ..lineTo(cx, cy)
        ..lineTo(cx, cy + sy * arm);
    }

    canvas.drawPath(
      path,
      Paint()
        ..style = PaintingStyle.stroke
        ..strokeWidth = 2 * strokeScale
        ..strokeCap = StrokeCap.round
        ..strokeJoin = StrokeJoin.round
        ..color = const Color(0xFF07C160),
    );
  }

  @override
  bool shouldRepaint(_FocusCornersPainter old) =>
      old.strokeScale != strokeScale;
}

/// The line that sweeps the picture, top to bottom, over and over.
class _ScanLineOverlay extends StatefulWidget {
  const _ScanLineOverlay();

  @override
  State<_ScanLineOverlay> createState() => _ScanLineOverlayState();
}

class _ScanLineOverlayState extends State<_ScanLineOverlay>
    with SingleTickerProviderStateMixin {
  late final AnimationController _controller = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 2600),
  )..repeat();

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _controller,
      builder: (context, _) => CustomPaint(
        painter: _ScanLinePainter(progress: _controller.value),
        size: Size.infinite,
      ),
    );
  }
}

class _ScanLinePainter extends CustomPainter {
  _ScanLinePainter({required this.progress});

  final double progress;

  static const _green = Color(0xFF07C160);

  @override
  void paint(Canvas canvas, Size size) {
    final y = size.height * progress;

    final fade = math
        .min(progress / 0.12, (1 - progress) / 0.12)
        .clamp(0.0, 1.0);
    if (fade <= 0) return;

    final trail = math.min(size.height * 0.14, 150.0);
    final top = math.max(y - trail, 0.0);
    if (y > top) {
      final band = Rect.fromLTRB(0, top, size.width, y);
      canvas.drawRect(
        band,
        Paint()
          ..shader = LinearGradient(
            begin: Alignment.topCenter,
            end: Alignment.bottomCenter,
            colors: [
              _green.withValues(alpha: 0),
              _green.withValues(alpha: 0.16 * fade),
            ],
          ).createShader(band),
      );
    }

    final line = Rect.fromLTWH(0, y - 1, size.width, 2);
    canvas.drawRect(
      line,
      Paint()
        ..shader = LinearGradient(
          colors: [
            _green.withValues(alpha: 0),
            _green.withValues(alpha: fade),
            _green.withValues(alpha: 0),
          ],
          stops: const [0.0, 0.5, 1.0],
        ).createShader(line),
    );
  }

  @override
  bool shouldRepaint(_ScanLinePainter old) => old.progress != progress;
}

/// What automatic zooming aims for: the code filling this fraction of the
/// picture's short side.
const double _kZoomTargetFraction = 0.45;

/// How often the zoom takes a step towards its target. Thirty a second: past
/// the point where the steps can be told apart, and not so many that the
/// platform call becomes the cost.
const Duration _kZoomStepEvery = Duration(milliseconds: 33);

/// Roughly how long the zoom takes to close most of the way onto its target.
/// It is a time constant, not a duration: the last of the distance is walked
/// slowest, which is what makes the arrival soft.
const Duration _kZoomSettle = Duration(milliseconds: 420);

/// How long the walk takes to come up to full speed, so that it starts by
/// easing off the mark instead of lurching.
const Duration _kZoomRampIn = Duration(milliseconds: 260);

/// The ceiling for automatic zooming; beyond it the picture shakes too much
/// and the target is easily lost.
const double _kMaxAutoZoom = 4.0;

/// A code smaller than this fraction of the picture is what automatic focusing
/// is for. Bigger than that and continuous focus, which weighs the middle,
/// already has it.
const double _kAutoFocusMaxFraction = 0.45;

/// How long automatic focusing stands aside after a tap. Long enough for the
/// tap's own focus to settle and be seen, and no longer: pointing somewhere by
/// hand is a hint about where to look, not an instruction to stop looking.
const Duration _kManualFocusHold = Duration(seconds: 2);

/// The soonest it will point focus somewhere again.
const Duration _kAutoFocusInterval = Duration(milliseconds: 1500);

/// Moving this much across the picture counts as somewhere else, and is worth
/// focusing on before [_kAutoFocusRepeat] has passed.
const double _kAutoFocusMoved = 0.06;

/// The soonest it will focus on the same place twice.
const Duration _kAutoFocusRepeat = Duration(seconds: 4);

/// How long frames must hold nothing whatsoever before [_blindFocus] stops
/// waiting for the detector. Long enough not to fire while the camera is still
/// opening, short enough that someone who points the phone at a code and holds
/// still is not left holding it.
const Duration _kBlindFocusAfter = Duration(seconds: 1);

/// And how often it may try again. Slower than [_kAutoFocusInterval], because
/// it is aiming at nothing in particular and every scan softens the picture on
/// its way through.
const Duration _kBlindFocusInterval = Duration(seconds: 2);

/// How long automatic zooming stands aside after a manual one.
const Duration _kManualZoomHold = Duration(seconds: 5);

/// How long automatic zooming waits after picking ends.
const Duration _kPickExitZoomHold = Duration(seconds: 3);

/// With one code decoded but more than one seen by detection, this is the
/// longest it waits for the second to come out so that picking can begin.
/// Failing that it takes the single code, rather than leaving the user
/// staring.
const Duration _kMultiCodeGrace = Duration(milliseconds: 700);

/// A point of the scanned frame, as fractions of its width and height, in the
/// coordinates `focusAt` takes.
(double, double) _previewFraction(double fx, double fy, int quarterTurns) =>
    switch (quarterTurns) {
      1 => (fy, 1 - fx),
      2 => (1 - fx, 1 - fy),
      3 => (1 - fy, fx),
      _ => (fx, fy),
    };

/// The mapping from frame coordinates to screen coordinates.
({double scale, double dx, double dy}) _coverFit(Size size, int fw, int fh) {
  final scale = math.max(size.width / fw, size.height / fh);
  return (
    scale: scale,
    dx: (size.width - fw * scale) / 2,
    dy: (size.height - fh * scale) / 2,
  );
}

/// Draws the four corners of each decoded code, mapping frame coordinates to
/// screen coordinates the way BoxFit.cover does.
class _CodeMarkerPainter extends CustomPainter {
  final ScanOutcome frame;

  _CodeMarkerPainter({required this.frame});

  @override
  void paint(Canvas canvas, Size size) {
    if (frame.results.isEmpty || frame.width == 0 || frame.height == 0) return;

    final m = _coverFit(size, frame.width, frame.height);
    final scale = m.scale;
    final dx = m.dx;
    final dy = m.dy;

    final paint = Paint()
      ..color = const Color(0xFF07C160)
      ..style = PaintingStyle.fill;

    for (final r in frame.results) {
      final path = Path();
      for (var i = 0; i < r.corners.length; i++) {
        final p = Offset(
          r.corners[i].dx * scale + dx,
          r.corners[i].dy * scale + dy,
        );
        if (i == 0) {
          path.moveTo(p.dx, p.dy);
        } else {
          path.lineTo(p.dx, p.dy);
        }
      }
      path.close();
      canvas.drawPath(
        path,
        Paint()
          ..color = const Color(0xFF07C160).withValues(alpha: 0.25)
          ..style = PaintingStyle.fill,
      );
      canvas.drawPath(
        path,
        Paint()
          ..color = const Color(0xFF07C160)
          ..strokeWidth = 3
          ..style = PaintingStyle.stroke,
      );
      final c = r.corners.centre;
      final cx = c.dx;
      final cy = c.dy;
      canvas.drawCircle(Offset(cx * scale + dx, cy * scale + dy), 8, paint);
    }
  }

  @override
  bool shouldRepaint(_CodeMarkerPainter old) => old.frame != frame;
}