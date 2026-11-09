/// A phone-only diagnostic panel. Opens from the scanner's bug button,
/// shows everything Dart and the native side are willing to say, and can
/// copy the lot to the clipboard for sending on — no file, no external
/// dependencies.
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:wxscan_live/wxscan_live.dart';

const _channel = MethodChannel('wxscan_live');

class DebugPanel extends StatefulWidget {
  final WxScanController? controller;
  final int framesReceived;
  final int lastResults;
  final int lastCandidates;
  final DateTime? lastFrameAt;
  final List<String> log;

  const DebugPanel({
    super.key,
    required this.controller,
    required this.framesReceived,
    required this.lastResults,
    required this.lastCandidates,
    required this.lastFrameAt,
    required this.log,
  });

  @override
  State<DebugPanel> createState() => _DebugPanelState();
}

class _DebugPanelState extends State<DebugPanel> {
  Map<String, dynamic> _native = {};
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    _refresh();
    _poll = Timer.periodic(
      const Duration(milliseconds: 800),
      (_) => _refresh(),
    );
  }

  Future<void> _refresh() async {
    try {
      final r = await _channel.invokeMethod<Map>('debugState');
      if (!mounted) return;
      setState(() => _native = Map<String, dynamic>.from(r ?? {}));
    } catch (e) {
      if (!mounted) return;
      setState(() => _native = {'error': e.toString()});
    }
  }

  String _report() {
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
    b.writeln('frames received: ${widget.framesReceived}');
    b.writeln('last frame results: ${widget.lastResults}');
    b.writeln('last frame candidates: ${widget.lastCandidates}');
    b.writeln('last frame at: ${widget.lastFrameAt}');
    if (widget.lastFrameAt != null) {
      b.writeln(
        'ms since last frame: '
        '${DateTime.now().difference(widget.lastFrameAt!).inMilliseconds}',
      );
    }
    b.writeln();
    b.writeln('--- controller value ---');
    final v = widget.controller?.value;
    if (v != null) {
      b.writeln('nativeReady: ${v.nativeReady}');
      b.writeln('previewSize: ${v.previewSize}');
      b.writeln('torchEnabled: ${v.torchEnabled}');
      b.writeln('zoom: ${v.zoom}');
      b.writeln('resolution: ${v.resolution}');
    } else {
      b.writeln('controller is null');
    }
    b.writeln();
    b.writeln('--- log tail ---');
    for (final line in widget.log) {
      b.writeln(line);
    }
    return b.toString();
  }

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: _report()));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      const SnackBar(
        content: Text('Copied. Paste into Telegram to send.'),
      ),
    );
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final v = widget.controller?.value;
    final msSince = widget.lastFrameAt == null
        ? null
        : DateTime.now().difference(widget.lastFrameAt!).inMilliseconds;

    return Material(
      color: Colors.black87,
      child: SafeArea(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Container(
              padding: const EdgeInsets.all(8),
              color: Colors.black54,
              child: Row(
                children: [
                  const Expanded(
                    child: Text(
                      'wxscan debug',
                      style: TextStyle(
                        color: Colors.white,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.copy, color: Colors.white),
                    onPressed: _copy,
                    tooltip: 'Copy to clipboard',
                  ),
                  IconButton(
                    icon: const Icon(Icons.close, color: Colors.white),
                    onPressed: () => Navigator.of(context).pop(),
                  ),
                ],
              ),
            ),
            Expanded(
              child: SingleChildScrollView(
                padding: const EdgeInsets.all(12),
                child: DefaultTextStyle(
                  style: const TextStyle(
                    color: Colors.white,
                    fontFamily: 'monospace',
                    fontSize: 11,
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _section('DART: controller', {
                        'nativeReady': v?.nativeReady,
                        'previewSize': v?.previewSize,
                        'torchEnabled': v?.torchEnabled,
                        'zoom': v?.zoom,
                        'resolution': v?.resolution,
                      }),
                      _section('DART: stream', {
                        'frames received': widget.framesReceived,
                        'last results': widget.lastResults,
                        'last candidates': widget.lastCandidates,
                        'ms since last frame': msSince,
                      }),
                      _section('NATIVE: scanner', {
                        'nativeReady': _native['nativeReady'],
                        'modelsLoaded': _native['modelsLoaded'],
                        'scannerHandle': _native['scannerHandle'],
                        'busy': _native['busy'],
                        'scanning': _native['scanning'],
                      }),
                      _section('NATIVE: pipeline', {
                        'hasProvider': _native['hasProvider'],
                        'hasCamera': _native['hasCamera'],
                        'hasPreview': _native['hasPreview'],
                        'hasAnalysis': _native['hasAnalysis'],
                        'starting': _native['starting'],
                      }),
                      _section('NATIVE: frames', {
                        'analyzedFrames': _native['analyzedFrames'],
                        'skippedFrames': _native['skippedFrames'],
                        'bufW/bufH': '${_native['bufW']}x${_native['bufH']}',
                        'previewW/H':
                            '${_native['previewW']}x${_native['previewH']}',
                        'shortSide': _native['shortSide'],
                        'boundShortSide': _native['boundShortSide'],
                        'lastNv21 bytes': _native['lastNv21'],
                      }),
                      const SizedBox(height: 12),
                      _section('DART: last 30 log lines', {
                        for (var i = 0; i < widget.log.length; i++)
                          '[$i]': widget.log[i],
                      }),
                    ],
                  ),
                ),
              ),
            ),
          ],
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
              fontFamily: 'monospace',
              fontSize: 12,
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