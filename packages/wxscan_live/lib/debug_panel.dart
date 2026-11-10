/// A phone-only diagnostic panel. Opens from the scanner's bug button,
/// shows everything Dart and the native side are willing to say, and can
/// copy the lot to the clipboard for sending on — no file, no external
/// dependencies.
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:wxscan_live/wxscan_live.dart';

const _channel = MethodChannel('wxscan_live');

/// Diagnostic panel that opens from the scanner's bug button. Shows Dart-
/// side counters, the native state map, and the last dozen log lines,
/// refreshed every 700 ms. No native calls of its own — it reads the same
/// `debugState` the plugin exposes — so it works even when the native layer
/// is dead.
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

  /// Human-readable ImageFormat constant name and whether it is what this
  /// scanner wants (YUV_420_888, three planes, pixelStride 1).
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

  @override
  Widget build(BuildContext context) {
    final native = _native;
    final fmt = native['lastImageFormat'] as int?;

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
                  'set': widget.state._controller != null,
                  'nativeReady': widget.state._controller?.value.nativeReady,
                  'previewSize': widget.state._controller?.value.previewSize,
                  'torchEnabled': widget.state._controller?.value.torchEnabled,
                  'resolution': widget.state._controller?.value.resolution,
                }),
                _section('DART: stream', {
                  'rawFrameCount': widget.state._rawFrameCount,
                  'lastFrameInfo': widget.state._lastFrameInfo,
                  'nnEnabled': widget.state._nnEnabled,
                }),

                // ---- NEW: what the analyser is actually receiving ----
                _section('NATIVE: frame format (ImageAnalysis output)', {
                  'imageFormat': fmt != null ? '$fmt — ${_formatName(fmt)}' : '(none)',
                  'planes count':
                      '${native['lastPlanesCount']} '
                      '${_planesHint(native['lastPlanesCount'] as int?)}',
                  'pixelStride':
                      '${native['lastPixelStride']} '
                      '${_pixelStrideHint(native['lastPixelStride'] as int?)}',
                  'rowStride': native['lastRowStride'],
                  'frame size':
                      '${native['lastFrameW']}x${native['lastFrameH']}',
                  'frame rotation': native['lastFrameRot'],
                }),

                _section('NATIVE: scanner', {
                  'modelsLoaded': native['modelsLoaded'],
                  'scannerHandle': native['scannerHandle'],
                  'busy': native['busy'],
                  'scanning': native['scanning'],
                  'bindCount': native['bindCount'],
                  'firstFrameReceived': native['firstFrameReceived'],
                }),
                _section('NATIVE: pipeline', {
                  'hasProvider': native['hasProvider'],
                  'hasCamera': native['hasCamera'],
                  'hasPreview': native['hasPreview'],
                  'hasAnalysis': native['hasAnalysis'],
                  'scanEnabled': native['scanEnabled'],
                  'desiredSize': native['desiredSize'],
                  'shortSide': native['shortSide'],
                  'targetShortSide': native['targetShortSide'],
                }),
                _section('NATIVE: last frame buffer', {
                  'size': '${native['diagW']}x${native['diagH']}',
                  'stride': native['diagStride'],
                  'len': native['diagLen'],
                  'min/max/avg':
                      '${native['diagMin']} / ${native['diagMax']} / '
                      '${native['diagAvg']}',
                  'first16': native['diagFirst16'],
                  'frames seen': native['diagFramesSeen'],
                  'analyzed': native['analyzedFrames'],
                  'skipped': native['skippedFrames'],
                  'empty': native['emptyFrames'],
                }),
                _section('DART: last 30 log lines', {
                  for (var i = 0; i < widget.state._logBuffer.length; i++)
                    '#$i': widget.state._logBuffer[i],
                }),
                const SizedBox(height: 20),
                const Text(
                  'If imageFormat is 1 (RGBA_8888): the HAL ignored the '
                  'requested YUV format. Rust is reading 1/4 of the pixels as '
                  'if they were grayscale — the detector still finds a QR '
                  'shape, but the decoder cannot read it. That is the '
                  '"candidates > 0, results = 0" symptom. Need to convert '
                  'RGBA→Y in Kotlin, or find a CameraX setting the HAL obeys.',
                  style: TextStyle(color: Colors.white54, fontSize: 11),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  String _planesHint(int? planes) {
    switch (planes) {
      case 1: return '(1 = RGBA/NV21, wrong)';
      case 3: return '(3 = YUV, correct)';
      case null:
      case 0:
        return '(no frame)';
      default: return '(unexpected)';
    }
  }

  String _pixelStrideHint(int? stride) {
    switch (stride) {
      case 1: return '(1 = Y-plane, correct)';
      case 4: return '(4 = RGBA, wrong)';
      case null:
      case 0:
        return '(none)';
      default: return '(unexpected)';
    }
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