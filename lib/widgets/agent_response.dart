import 'dart:async';
import 'dart:ui' show ImageFilter;

import 'package:flutter/material.dart';
import 'package:font_awesome_flutter/font_awesome_flutter.dart';

import '../theme.dart';
import 'common.dart';

/// How a run looks while it is happening.
///
/// Three rules, taken from the two apps this is modelled on. Motion that never
/// stops is motion nobody reads, so every animation here ends when the work
/// ends. A step exists because something happened, never because a pipeline
/// says it should. And the elapsed time counts work, not the minutes Luna
/// spent waiting for you to tap Allow.

const Curve _ease = Cubic(0.23, 1, 0.32, 1);
const Curve _wordEase = Cubic(0.22, 0.61, 0.25, 1);

/// Text with a highlight travelling through it. It runs only while [active];
/// when the work stops the label goes flat, which is the whole point.
class ShimmerLabel extends StatefulWidget {
  const ShimmerLabel({
    super.key,
    required this.text,
    this.active = true,
    this.size = 12.5,
    this.weight = 500,
  });

  final String text;
  final bool active;
  final double size;
  final double weight;

  @override
  State<ShimmerLabel> createState() => _ShimmerLabelState();
}

class _ShimmerLabelState extends State<ShimmerLabel> with SingleTickerProviderStateMixin {
  late final AnimationController _sweep = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1400),
  );

  @override
  void initState() {
    super.initState();
    if (widget.active) _sweep.repeat();
  }

  @override
  void didUpdateWidget(covariant ShimmerLabel old) {
    super.didUpdateWidget(old);
    if (widget.active && !_sweep.isAnimating) {
      _sweep.repeat();
    } else if (!widget.active && _sweep.isAnimating) {
      _sweep.stop();
      _sweep.value = 0;
    }
  }

  @override
  void dispose() {
    _sweep.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final TextStyle style =
        LunaTheme.text(size: widget.size, weight: widget.weight, color: LunaTheme.ink2);
    if (!widget.active) return Text(widget.text, style: style);
    return AnimatedBuilder(
      animation: _sweep,
      builder: (BuildContext context, Widget? child) {
        final double t = _sweep.value;
        return ShaderMask(
          blendMode: BlendMode.srcIn,
          shaderCallback: (Rect bounds) => LinearGradient(
            begin: Alignment(-1.6 + 3.2 * t, 0),
            end: Alignment(-0.6 + 3.2 * t, 0),
            colors: <Color>[LunaTheme.ink3, LunaTheme.ink, LunaTheme.ink3],
          ).createShader(bounds),
          child: child,
        );
      },
      child: Text(widget.text, style: style.copyWith(color: LunaTheme.ink)),
    );
  }
}

/// A 3×3 grid of squares with a wavefront moving diagonally across it. Small
/// enough to sit on a line of text, and it says "busy" without a spinner's
/// implication that there is a fixed amount left to go.
class PixelLoader extends StatefulWidget {
  const PixelLoader({super.key, this.size = 13, this.active = true});

  final double size;
  final bool active;

  @override
  State<PixelLoader> createState() => _PixelLoaderState();
}

class _PixelLoaderState extends State<PixelLoader> with SingleTickerProviderStateMixin {
  late final AnimationController _wave = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 650),
  );

  @override
  void initState() {
    super.initState();
    if (widget.active) _wave.repeat();
  }

  @override
  void didUpdateWidget(covariant PixelLoader old) {
    super.didUpdateWidget(old);
    if (widget.active && !_wave.isAnimating) {
      _wave.repeat();
    } else if (!widget.active && _wave.isAnimating) {
      _wave.stop();
    }
  }

  @override
  void dispose() {
    _wave.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final double cell = (widget.size - 2 * 1.5) / 3;
    return SizedBox(
      width: widget.size,
      height: widget.size,
      child: AnimatedBuilder(
        animation: _wave,
        builder: (BuildContext context, Widget? _) {
          return Column(
            mainAxisSize: MainAxisSize.min,
            children: List<Widget>.generate(3, (int row) {
              return Padding(
                padding: EdgeInsets.only(bottom: row == 2 ? 0 : 1.5),
                child: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: List<Widget>.generate(3, (int column) {
                    // The wavefront is the diagonal, so the grid reads as one
                    // sweep rather than nine independent blinks.
                    final double phase = (row + column) / 4;
                    final double distance = ((_wave.value - phase) % 1.0);
                    final double lit = distance < 0.35 ? 1 - (distance / 0.35) : 0.0;
                    return Padding(
                      padding: EdgeInsets.only(right: column == 2 ? 0 : 1.5),
                      child: Container(
                        width: cell,
                        height: cell,
                        decoration: BoxDecoration(
                          color: Color.lerp(LunaTheme.ink4, LunaTheme.ink, lit),
                          borderRadius: BorderRadius.circular(1.5),
                        ),
                      ),
                    );
                  }),
                ),
              );
            }),
          );
        },
      ),
    );
  }
}

/// The line that sits under the thread while a run is in flight: what Luna is
/// doing, how long she has been doing it, and the way out.
class AgentWorkingLine extends StatelessWidget {
  const AgentWorkingLine({
    super.key,
    required this.label,
    required this.elapsed,
    required this.onStop,
    this.waiting = false,
  });

  final String label;
  final Duration elapsed;

  /// Waiting on you is not working. The clock stops and the motion stops with
  /// it, so a paused run cannot be mistaken for a slow one.
  final bool waiting;
  final VoidCallback onStop;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.fromLTRB(20, 10, 20, 0),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
      decoration: BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rPill),
      child: Row(
        children: <Widget>[
          PixelLoader(active: !waiting),
          const SizedBox(width: 10),
          Flexible(child: ShimmerLabel(text: label, active: !waiting)),
          const SizedBox(width: 8),
          Text(
            formatDuration(elapsed),
            style: LunaTheme.monoStyle(size: 11.5, color: LunaTheme.ink3),
          ),
          const Spacer(),
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: onStop,
            child: Semantics(
              button: true,
              label: 'Stop the job',
              child: Row(
                children: <Widget>[
                  Glyph(FontAwesomeIcons.stop, size: 10.5, color: LunaTheme.ink),
                  const SizedBox(width: 6),
                  Text('Stop', style: LunaTheme.text(size: 12.5, weight: 600)),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

/// One line of the trace.
class TraceStep {
  const TraceStep({
    required this.label,
    required this.state,
    this.detail = '',
  });

  final String label;

  /// running, done, replayed, blocked, denied.
  final String state;
  final String detail;

  bool get isDone => state == 'done' || state == 'replayed';
  bool get isRefused => state == 'denied' || state == 'blocked';
  bool get isRunning => state == 'running';
}

/// The expandable record of what Luna actually did. It opens itself while the
/// run is live so you can watch, and folds back to one line when the run ends —
/// unless you touched it, in which case you decide.
class AgentTrace extends StatefulWidget {
  const AgentTrace({
    super.key,
    required this.steps,
    required this.running,
    required this.elapsed,
  });

  final List<TraceStep> steps;
  final bool running;
  final Duration elapsed;

  @override
  State<AgentTrace> createState() => _AgentTraceState();
}

class _AgentTraceState extends State<AgentTrace> {
  bool? _pinned;

  bool get _open => _pinned ?? widget.running;

  @override
  Widget build(BuildContext context) {
    final String heading = widget.running
        ? 'Working'
        : 'Thought for ${formatDuration(widget.elapsed, precise: true)}';
    return Container(
      margin: const EdgeInsets.fromLTRB(52, 10, 20, 0),
      padding: const EdgeInsets.fromLTRB(15, 4, 13, 6),
      decoration: BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rStep),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: () => setState(() => _pinned = !_open),
            child: Semantics(
              button: true,
              label: '$heading, ${widget.steps.length} steps',
              child: Padding(
                padding: const EdgeInsets.symmetric(vertical: 7),
                child: Row(
                  children: <Widget>[
                    ShimmerLabel(
                      text: heading,
                      active: widget.running,
                      size: 12.5,
                      weight: 600,
                    ),
                    const SizedBox(width: 8),
                    Text(
                      '${widget.steps.length} ${widget.steps.length == 1 ? 'step' : 'steps'}',
                      style: LunaTheme.text(size: 12, color: LunaTheme.ink3),
                    ),
                    const Spacer(),
                    // Always drawn. A control that only appears on hover does
                    // not exist on a phone.
                    AnimatedRotation(
                      turns: _open ? 0.5 : 0,
                      duration: const Duration(milliseconds: 260),
                      curve: _ease,
                      child: Glyph(FontAwesomeIcons.chevronDown,
                          size: 10, color: LunaTheme.ink3),
                    ),
                  ],
                ),
              ),
            ),
          ),
          AnimatedSize(
            duration: const Duration(milliseconds: 400),
            curve: _ease,
            alignment: Alignment.topCenter,
            child: _open
                ? Padding(
                    padding: const EdgeInsets.only(bottom: 4),
                    child: Container(
                      padding: const EdgeInsets.only(left: 13),
                      decoration: BoxDecoration(
                        border: Border(left: BorderSide(color: LunaTheme.line, width: 1)),
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: widget.steps.map(_row).toList(),
                      ),
                    ),
                  )
                : const SizedBox(width: double.infinity),
          ),
        ],
      ),
    );
  }

  Widget _row(TraceStep step) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 6.5),
      child: Row(
        children: <Widget>[
          SizedBox(
            width: 17,
            height: 17,
            child: step.isRunning
                ? const Center(child: PixelLoader(size: 9))
                : Container(
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: step.isDone ? LunaTheme.ink : LunaTheme.fill2,
                      shape: BoxShape.circle,
                    ),
                    child: Glyph(
                      step.isRefused ? FontAwesomeIcons.xmark : FontAwesomeIcons.check,
                      size: 8,
                      color: step.isDone ? LunaTheme.onInk : LunaTheme.ink3,
                    ),
                  ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text(
              step.label,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: LunaTheme.text(
                size: 13,
                weight: step.isDone ? 550 : 500,
                color: step.isDone ? LunaTheme.ink : LunaTheme.ink3,
              ),
            ),
          ),
          if (step.detail.isNotEmpty) ...<Widget>[
            const SizedBox(width: 8),
            ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 130),
              child: Text(
                step.detail,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                textAlign: TextAlign.right,
                style: LunaTheme.text(size: 12, color: LunaTheme.ink3),
              ),
            ),
          ],
        ],
      ),
    );
  }
}

/// The answer, arriving a word at a time out of a blur.
///
/// The model decides when words appear; this only decides how they land. Words
/// that have finished landing are plain text again, so a long answer does not
/// carry hundreds of live animations.
class StreamedAnswer extends StatefulWidget {
  const StreamedAnswer({super.key, required this.text, this.animate = true});

  final String text;

  /// A reloaded message is already old news: it renders instantly.
  final bool animate;

  @override
  State<StreamedAnswer> createState() => _StreamedAnswerState();
}

class _StreamedAnswerState extends State<StreamedAnswer> {
  int _settled = 0;
  Timer? _tick;

  List<String> get _words => widget.text.split(RegExp(r'\s+'))..removeWhere((String w) => w.isEmpty);

  @override
  void initState() {
    super.initState();
    if (!widget.animate) {
      _settled = 1 << 30;
    } else {
      // 100ms a word, the same cadence as the reference. Words that arrive
      // faster than that queue up rather than flashing in together.
      _tick = Timer.periodic(const Duration(milliseconds: 100), (Timer _) {
        if (!mounted) return;
        if (_settled >= _words.length) return;
        setState(() => _settled++);
      });
    }
  }

  @override
  void dispose() {
    _tick?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final List<String> words = _words;
    if (!widget.animate || _settled >= words.length + 4) {
      return Text(widget.text, style: LunaTheme.body);
    }
    final int visible = words.length < _settled + 1 ? words.length : _settled + 1;
    return Wrap(
      spacing: 4.5,
      runSpacing: 3,
      crossAxisAlignment: WrapCrossAlignment.end,
      children: List<Widget>.generate(visible, (int index) {
        final bool fresh = index >= _settled - 1;
        if (!fresh) return Text(words[index], style: LunaTheme.body);
        return _Word(key: ValueKey<int>(index), word: words[index]);
      }),
    );
  }
}

class _Word extends StatefulWidget {
  const _Word({super.key, required this.word});

  final String word;

  @override
  State<_Word> createState() => _WordState();
}

class _WordState extends State<_Word> with SingleTickerProviderStateMixin {
  late final AnimationController _in = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 500),
  )..forward();

  @override
  void dispose() {
    _in.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnimatedBuilder(
      animation: _in,
      builder: (BuildContext context, Widget? child) {
        final double t = _wordEase.transform(_in.value);
        return Opacity(
          opacity: t,
          child: ImageFiltered(
            imageFilter: t >= 0.995
                ? ImageFilter.blur(sigmaX: 0, sigmaY: 0)
                : ImageFilter.blur(sigmaX: 10 * (1 - t), sigmaY: 10 * (1 - t)),
            child: child,
          ),
        );
      },
      child: Text(widget.word, style: LunaTheme.body),
    );
  }
}

/// 11.5s while it matters, 2m 04s when it is long.
String formatDuration(Duration value, {bool precise = false}) {
  final int millis = value.inMilliseconds;
  if (millis < 60000) {
    final double seconds = millis / 1000;
    return precise && seconds < 60
        ? '${seconds.toStringAsFixed(1)}s'
        : '${seconds.round()}s';
  }
  final int minutes = millis ~/ 60000;
  final int seconds = (millis % 60000) ~/ 1000;
  return '${minutes}m ${seconds.toString().padLeft(2, '0')}s';
}
