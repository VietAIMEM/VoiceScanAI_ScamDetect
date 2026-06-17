import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const ScamCallApp());
}

class ScamCallApp extends StatelessWidget {
  const ScamCallApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'Scam Call Guard',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF1A7F64),
          brightness: Brightness.light,
        ),
        useMaterial3: true,
      ),
      home: const DetectionDashboard(),
    );
  }
}

class NativeDetectionBridge {
  static const MethodChannel _methods = MethodChannel(
    'scam_call_guard/detection',
  );
  static const EventChannel _events = EventChannel(
    'scam_call_guard/detection_scores',
  );

  Stream<DetectionScores> get scores {
    return _events.receiveBroadcastStream().map((event) {
      return DetectionScores.fromMap(Map<dynamic, dynamic>.from(event as Map));
    });
  }

  Future<void> requestCorePermissions() async {
    await _methods.invokeMethod<void>('requestCorePermissions');
  }

  Future<void> openOverlaySettings() async {
    await _methods.invokeMethod<void>('openOverlaySettings');
  }

  Future<bool> canDrawOverlay() async {
    final result = await _methods.invokeMethod<bool>('canDrawOverlay');
    return result ?? false;
  }

  Future<void> startManualDetection() async {
    await _methods.invokeMethod<void>('startManualDetection');
  }

  Future<void> startScreenAudioDetection() async {
    await _methods.invokeMethod<void>('startScreenAudioDetection');
  }

  Future<void> stopDetection() async {
    await _methods.invokeMethod<void>('stopDetection');
  }
}

class DetectionScores {
  const DetectionScores({
    required this.voice,
    required this.text,
    required this.total,
    required this.status,
    required this.updatedAtMillis,
    required this.conformer,
    required this.aasist,
    required this.textLabel,
    required this.transcript,
    required this.riskLevel,
    required this.riskLabel,
  });

  factory DetectionScores.initial() {
    return const DetectionScores(
      voice: 0,
      text: 0,
      total: 0,
      status: 'Idle',
      updatedAtMillis: 0,
      conformer: 0,
      aasist: 0,
      textLabel: '',
      transcript: '',
      riskLevel: 'idle',
      riskLabel: 'Idle',
    );
  }

  factory DetectionScores.fromMap(Map<dynamic, dynamic> map) {
    return DetectionScores(
      voice: ((map['voice'] as num?) ?? 0).toDouble(),
      text: ((map['text'] as num?) ?? 0).toDouble(),
      total: ((map['total'] as num?) ?? 0).toDouble(),
      status: (map['status'] as String?) ?? 'Listening',
      updatedAtMillis: ((map['updatedAtMillis'] as num?) ?? 0).toInt(),
      conformer: ((map['conformer'] as num?) ?? 0).toDouble(),
      aasist: ((map['aasist'] as num?) ?? 0).toDouble(),
      textLabel: (map['textLabel'] as String?) ?? '',
      transcript: (map['transcript'] as String?) ?? '',
      riskLevel: (map['riskLevel'] as String?) ?? 'idle',
      riskLabel: (map['riskLabel'] as String?) ?? 'Idle',
    );
  }

  final double voice;
  final double text;
  final double total;
  final String status;
  final int updatedAtMillis;
  final double conformer;
  final double aasist;
  final String textLabel;
  final String transcript;
  final String riskLevel;
  final String riskLabel;
}

class DetectionDashboard extends StatefulWidget {
  const DetectionDashboard({super.key});

  @override
  State<DetectionDashboard> createState() => _DetectionDashboardState();
}

class _DetectionDashboardState extends State<DetectionDashboard> {
  final NativeDetectionBridge _bridge = NativeDetectionBridge();
  StreamSubscription<DetectionScores>? _subscription;
  DetectionScores _scores = DetectionScores.initial();
  bool _isRunning = false;
  bool _canDrawOverlay = false;
  String? _message;

  @override
  void initState() {
    super.initState();
    _subscription = _bridge.scores.listen((scores) {
      if (!mounted) return;
      setState(() {
        _scores = scores;
        _isRunning = scores.status != 'Idle' && scores.status != 'Stopped';
      });
    });
    _refreshOverlayState();
  }

  @override
  void dispose() {
    _subscription?.cancel();
    super.dispose();
  }

  Future<void> _refreshOverlayState() async {
    final canDraw = await _bridge.canDrawOverlay();
    if (!mounted) return;
    setState(() => _canDrawOverlay = canDraw);
  }

  Future<void> _runAction(
    Future<void> Function() action,
    String message,
  ) async {
    try {
      await action();
      if (!mounted) return;
      setState(() => _message = message);
    } on PlatformException catch (error) {
      if (!mounted) return;
      setState(() => _message = error.message ?? error.code);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Scam Call Guard')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
          children: [
            _StatusHeader(scores: _scores, isRunning: _isRunning),
            const SizedBox(height: 16),
            _RiskBanner(scores: _scores),
            const SizedBox(height: 16),
            _ScoreGauge(label: 'Voice Detect', value: _scores.voice),
            const SizedBox(height: 12),
            _ScoreGauge(label: 'Text Detect', value: _scores.text),
            const SizedBox(height: 12),
            _ScoreGauge(
              label: 'Total Detect',
              value: _scores.total,
              riskLevel: _scores.riskLevel,
            ),
            const SizedBox(height: 20),
            _DebugPanel(scores: _scores),
            const SizedBox(height: 20),
            _PermissionPanel(
              canDrawOverlay: _canDrawOverlay,
              onRequestPermissions:
                  () => _runAction(
                    _bridge.requestCorePermissions,
                    'Da yeu cau quyen microphone, phone va notification.',
                  ),
              onOpenOverlaySettings: () async {
                await _runAction(
                  _bridge.openOverlaySettings,
                  'Mo man hinh cap quyen overlay.',
                );
                await _refreshOverlayState();
              },
            ),
            const SizedBox(height: 20),
            Row(
              children: [
                Expanded(
                  child: FilledButton.icon(
                    onPressed:
                        _isRunning
                            ? null
                            : () => _runAction(
                              _bridge.startManualDetection,
                              'Dang chay manual realtime service.',
                            ),
                    icon: const Icon(Icons.play_arrow),
                    label: const Text('Start test'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: OutlinedButton.icon(
                    onPressed:
                        _isRunning
                            ? () => _runAction(
                              _bridge.stopDetection,
                              'Da dung detection service.',
                            )
                            : null,
                    icon: const Icon(Icons.stop),
                    label: const Text('Stop'),
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            OutlinedButton.icon(
              onPressed:
                  _isRunning
                      ? null
                      : () => _runAction(
                        _bridge.startScreenAudioDetection,
                        'Dang xin quyen screen audio capture.',
                      ),
              icon: const Icon(Icons.screen_share),
              label: const Text('Start screen audio'),
            ),
            if (_message != null) ...[
              const SizedBox(height: 16),
              Text(
                _message!,
                style: TextStyle(color: Theme.of(context).colorScheme.primary),
              ),
            ],
            const SizedBox(height: 20),
            const _ImplementationNote(),
          ],
        ),
      ),
    );
  }
}

class _StatusHeader extends StatelessWidget {
  const _StatusHeader({required this.scores, required this.isRunning});

  final DetectionScores scores;
  final bool isRunning;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerHighest,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        children: [
          Icon(
            isRunning ? Icons.graphic_eq : Icons.pause_circle_outline,
            color: isRunning ? scheme.primary : scheme.onSurfaceVariant,
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  scores.status,
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 4),
                Text(
                  'Native foreground service + overlay scaffold',
                  style: Theme.of(context).textTheme.bodySmall,
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _RiskBanner extends StatelessWidget {
  const _RiskBanner({required this.scores});

  final DetectionScores scores;

  @override
  Widget build(BuildContext context) {
    final colors = _riskColors(context, scores.riskLevel);
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: colors.background,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: colors.foreground.withValues(alpha: 0.35)),
      ),
      child: Row(
        children: [
          Icon(_riskIcon(scores.riskLevel), color: colors.foreground),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              '${scores.riskLabel} - Total ${(scores.total.clamp(0, 1) * 100).round()}%',
              style: Theme.of(context).textTheme.titleSmall?.copyWith(
                color: colors.foreground,
                fontWeight: FontWeight.w700,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _ScoreGauge extends StatelessWidget {
  const _ScoreGauge({required this.label, required this.value, this.riskLevel});

  final String label;
  final double value;
  final String? riskLevel;

  @override
  Widget build(BuildContext context) {
    final percent = (value.clamp(0, 1) * 100).round();
    final colors = _riskColors(context, riskLevel ?? 'low');
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        border: Border.all(
          color:
              riskLevel == null
                  ? Theme.of(context).colorScheme.outlineVariant
                  : colors.foreground.withValues(alpha: 0.45),
        ),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(
                  label,
                  style: Theme.of(context).textTheme.titleSmall,
                ),
              ),
              Text(
                '$percent%',
                style: Theme.of(context).textTheme.titleMedium?.copyWith(
                  color: riskLevel == null ? null : colors.foreground,
                  fontWeight: riskLevel == null ? null : FontWeight.w700,
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          LinearProgressIndicator(
            value: value.clamp(0, 1),
            color: riskLevel == null ? null : colors.foreground,
          ),
        ],
      ),
    );
  }
}

({Color background, Color foreground}) _riskColors(
  BuildContext context,
  String riskLevel,
) {
  return switch (riskLevel) {
    'high' => (
      background: const Color(0xFFFFECE8),
      foreground: const Color(0xFFB3261E),
    ),
    'medium' => (
      background: const Color(0xFFFFF4D7),
      foreground: const Color(0xFF765300),
    ),
    _ => (
      background: Theme.of(context).colorScheme.surfaceContainerLowest,
      foreground: Theme.of(context).colorScheme.primary,
    ),
  };
}

IconData _riskIcon(String riskLevel) {
  return switch (riskLevel) {
    'high' => Icons.warning,
    'medium' => Icons.error_outline,
    _ => Icons.verified_user_outlined,
  };
}

class _PermissionPanel extends StatelessWidget {
  const _PermissionPanel({
    required this.canDrawOverlay,
    required this.onRequestPermissions,
    required this.onOpenOverlaySettings,
  });

  final bool canDrawOverlay;
  final VoidCallback onRequestPermissions;
  final VoidCallback onOpenOverlaySettings;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Text('Setup', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          onPressed: onRequestPermissions,
          icon: const Icon(Icons.mic),
          label: const Text('Request core permissions'),
        ),
        const SizedBox(height: 8),
        OutlinedButton.icon(
          onPressed: onOpenOverlaySettings,
          icon: Icon(canDrawOverlay ? Icons.check_circle : Icons.open_in_new),
          label: Text(
            canDrawOverlay
                ? 'Overlay permission granted'
                : 'Grant overlay permission',
          ),
        ),
      ],
    );
  }
}

class _DebugPanel extends StatelessWidget {
  const _DebugPanel({required this.scores});

  final DetectionScores scores;

  @override
  Widget build(BuildContext context) {
    final scheme = Theme.of(context).colorScheme;
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        color: scheme.surfaceContainerLowest,
        border: Border.all(color: scheme.outlineVariant),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Icon(Icons.tune, size: 18, color: scheme.primary),
              const SizedBox(width: 8),
              Text('Debug', style: Theme.of(context).textTheme.titleSmall),
            ],
          ),
          const SizedBox(height: 12),
          _DebugMetric(label: 'Conformer', value: scores.conformer),
          const SizedBox(height: 8),
          _DebugMetric(label: 'AASIST', value: scores.aasist),
          const SizedBox(height: 8),
          _DebugText(
            label: 'fastText label',
            value: scores.textLabel.isEmpty ? 'waiting' : scores.textLabel,
          ),
          const SizedBox(height: 8),
          _DebugText(
            label: 'Transcript',
            value:
                scores.transcript.isEmpty
                    ? 'waiting for speech'
                    : scores.transcript,
          ),
        ],
      ),
    );
  }
}

class _DebugMetric extends StatelessWidget {
  const _DebugMetric({required this.label, required this.value});

  final String label;
  final double value;

  @override
  Widget build(BuildContext context) {
    final percent = (value.clamp(0, 1) * 100).round();
    return Row(children: [Expanded(child: Text(label)), Text('$percent%')]);
  }
}

class _DebugText extends StatelessWidget {
  const _DebugText({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(label, style: Theme.of(context).textTheme.labelMedium),
        const SizedBox(height: 2),
        Text(value, style: Theme.of(context).textTheme.bodySmall),
      ],
    );
  }
}

class _ImplementationNote extends StatelessWidget {
  const _ImplementationNote();

  @override
  Widget build(BuildContext context) {
    return Text(
      'Realtime pipeline local: AudioRecord, sherpa-onnx Zipformer, fastText, Conformer va AASIST dang chay trong Android foreground service.',
      style: Theme.of(context).textTheme.bodySmall,
    );
  }
}
