/// A phone-only diagnostic panel. Opens from a long-press on the scan
/// screen, shows everything the native side is willing to say, and can
/// export the lot to a file for sending on.
import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:path_provider/path_provider.dart';
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
  String? _savedPath;

  @override
  void initState() {
    super.initState();
    _refresh();
    _poll = Timer.periodic(const Duration(milliseconds: 800), (_) => _refresh());
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

  Future<void> _export() async {
    final dir = await getApplicationDocumentsDirectory();
    final stamp = DateTime.now().toIso8601String().replaceAll(':', '-');
    final file = File('${dir.path}/wxscan_debug_$stamp.txt');
    final sb = StringBuffer();
    sb.writeln('=== wxscan debug ===');
    sb.writeln('generated: ${DateTime.now()}');
    sb.writeln();
    sb.writeln('--- native state ---');
    for (final e in _native.entries) {
      sb.writeln('${e.key}: ${e.value}');
    }
    sb.writeln();
    sb.writeln('--- dart counters ---');
    sb.writeln('frames received: ${widget.framesReceived}');
    sb.writeln('last frame results: ${widget.lastResults}');
    sb.writeln('last frame candidates: ${widget.lastCandidates}');
    sb.writeln('last frame at: ${widget.lastFrameAt}');
    if (widget.lastFrameAt != null) {
      sb.writeln('ms since last frame: '
          '${DateTime.now().difference(widget.lastFrameAt!).inMilliseconds}');
    }
    sb.writeln();
    sb.writeln('--- controller value ---');
    final v = widget.controller?.value;
    if (v != null) {
      sb.writeln('nativeReady: ${v.nativeReady}');
      sb.writeln('previewSize: ${v.previewSize}');
    } else {
      sb.writeln('controller is null');
    }
    sb.writeln();
    sb.writeln('--- log tail ---');
    for (final line in widget.log) {
      sb.writeln(line);
    }
    await file.writeAsString(sb.toString(), flush: true);
    if (!mounted) return;
    setState(() => _savedPath = file.path);
    // Also copy to clipboard so it can be pasted straight into Telegram.
    await Clipboard.setData(ClipboardData(text: sb.toString()));
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text('Saved + copied:\n${file.path}')),
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
                      style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700),
                    ),
                  ),
                  IconButton(
                    icon: const Icon(Icons.save_alt, color: Colors.white),
                    onPressed: _export,
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
                      color: Colors.white, fontFamily: 'monospace', fontSize: 11),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      _section('DART: controller', {
                        'nativeReady': v?.nativeReady,
                        'previewSize': v?.previewSize,
                        'torchEnabled': v?.torchEnabled,
                        'zoom': v?.zoom,
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
                        'previewW/H': '${_native['previewW']}x${_native['previewH']}',
                        'shortSide': _native['shortSide'],
                        'boundShortSide': _native['boundShortSide'],
                        'lastNv21 bytes': _native['lastNv21'],
                      }),
                      if (_savedPath != null)
                        Padding(
                          padding: const EdgeInsets.only(top: 12),
                          child: Text(
                            'SAVED:\n$_savedPath\n(copied to clipboard)',
                            style: const TextStyle(
                                color: Colors.greenAccent, fontSize: 11),
                          ),
                        ),
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
          Text(title,
              style: const TextStyle(
                  color: Colors.amber,
                  fontFamily: 'monospace',
                  fontSize: 12,
                  fontWeight: FontWeight.w700)),
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