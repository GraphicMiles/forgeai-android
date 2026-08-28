import 'dart:convert';

import 'package:flutter/foundation.dart';

/// One line in the log.
class LogLine {
  const LogLine({
    required this.at,
    required this.level,
    required this.side,
    required this.where,
    required this.what,
  });

  factory LogLine.fromMap(Map<String, dynamic> raw) {
    return LogLine(
      at: DateTime.fromMillisecondsSinceEpoch(
          (raw['at'] as num?)?.toInt() ?? DateTime.now().millisecondsSinceEpoch),
      level: (raw['level'] as String?) ?? 'info',
      side: (raw['side'] as String?) ?? 'java',
      where: (raw['where'] as String?) ?? 'unknown',
      what: (raw['what'] as String?) ?? '',
    );
  }

  final DateTime at;

  /// `error`, `warn` or `info`.
  final String level;

  /// Which half of the app wrote it: `java` or `dart`.
  final String side;

  /// The part of the app it came from — `http`, `engine`, `call`, a screen.
  final String where;

  final String what;

  bool get bad => level == 'error';

  String get clock {
    final String h = at.hour.toString().padLeft(2, '0');
    final String m = at.minute.toString().padLeft(2, '0');
    final String s = at.second.toString().padLeft(2, '0');
    final String ms = at.millisecond.toString().padLeft(3, '0');
    return '$h:$m:$s.$ms';
  }

  /// The form that goes on the clipboard. Fixed columns, so a pasted log is
  /// still readable in a message box that does not wrap kindly.
  String get plain => '$clock  ${level.padRight(5)} ${side.padRight(4)} '
      '${where.padRight(10)} $what';
}

/// What the app is doing, as text.
///
/// Kept apart from [LunaCore] on purpose: a log line arrives every few
/// milliseconds during a run, and rebuilding four screens for each one would
/// make the panel the slowest thing in the app. Only the panel listens here.
class DebugLog extends ChangeNotifier {
  static const int _max = 500;

  final List<LogLine> _lines = <LogLine>[];

  /// Newest first.
  List<LogLine> get lines => List<LogLine>.unmodifiable(_lines);

  int get errorCount => _lines.where((LogLine line) => line.bad).length;

  bool get isEmpty => _lines.isEmpty;

  /// Written by Dart itself — a widget that threw, a channel call that failed.
  void write(String level, String where, String what) {
    _add(LogLine(
      at: DateTime.now(),
      level: level,
      side: 'dart',
      where: where,
      what: what.replaceAll('\n', ' ').trim(),
    ));
  }

  void note(String where, String what) => write('info', where, what);

  void fail(String where, String what) => write('error', where, what);

  /// A line pushed up from Java.
  void fromJava(Object? raw) {
    if (raw is! Map) return;
    _add(LogLine.fromMap(
        raw.map((Object? k, Object? v) => MapEntry<String, dynamic>('$k', v))));
  }

  /// The lines Java already had before the panel was opened.
  void seed(String? json) {
    if (json == null || json.isEmpty) return;
    final Object? parsed = jsonDecode(json);
    if (parsed is! List) return;
    final List<LogLine> older = parsed
        .whereType<Map<Object?, Object?>>()
        .map((Map<Object?, Object?> item) => LogLine.fromMap(
            item.map((Object? k, Object? v) => MapEntry<String, dynamic>('$k', v))))
        .toList();
    // Anything already on screen is newer than anything being seeded.
    for (final LogLine line in older) {
      if (_lines.length >= _max) break;
      _lines.add(line);
    }
    _lines.sort((LogLine a, LogLine b) => b.at.compareTo(a.at));
    notifyListeners();
  }

  void clear() {
    _lines.clear();
    notifyListeners();
  }

  void _add(LogLine line) {
    _lines.insert(0, line);
    while (_lines.length > _max) {
      _lines.removeLast();
    }
    notifyListeners();
  }

  /// Everything, oldest first, ready to paste into a bug report.
  String asText({bool errorsOnly = false}) {
    final List<LogLine> wanted =
        errorsOnly ? _lines.where((LogLine line) => line.bad).toList() : _lines;
    if (wanted.isEmpty) return 'Nothing logged.';
    final StringBuffer out = StringBuffer();
    out.writeln('Luna log — ${DateTime.now()}');
    out.writeln('${wanted.length} lines, newest last');
    out.writeln('');
    for (final LogLine line in wanted.reversed) {
      out.writeln(line.plain);
    }
    return out.toString();
  }
}
