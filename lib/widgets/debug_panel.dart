import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:font_awesome_flutter/font_awesome_flutter.dart';

import '../core/debug_log.dart';
import '../core/luna_core.dart';
import '../theme.dart';
import 'common.dart';

/// The debug panel: what the app is doing, on top of whatever screen you are on.
///
/// It sits above the four screens rather than inside one of them, because the
/// moment you need it is the moment something went wrong somewhere else. Closed
/// it is a 34px button; open it is a sheet you can still see the app behind.
class DebugOverlay extends StatefulWidget {
  const DebugOverlay({super.key, required this.core, required this.child});

  final LunaCore core;
  final Widget child;

  @override
  State<DebugOverlay> createState() => _DebugOverlayState();
}

class _DebugOverlayState extends State<DebugOverlay> {
  bool _open = false;
  bool _errorsOnly = false;
  bool _copied = false;

  DebugLog get _log => widget.core.debug;

  Future<void> _copy() async {
    await Clipboard.setData(ClipboardData(text: _log.asText(errorsOnly: _errorsOnly)));
    if (!mounted) return;
    setState(() => _copied = true);
    await Future<void>.delayed(const Duration(milliseconds: 1400));
    if (mounted) setState(() => _copied = false);
  }

  Future<void> _copyOne(LogLine line) async {
    await Clipboard.setData(ClipboardData(text: line.plain));
    if (!mounted) return;
    ScaffoldMessenger.of(context).clearSnackBars();
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      backgroundColor: LunaTheme.ink,
      duration: const Duration(milliseconds: 1200),
      behavior: SnackBarBehavior.floating,
      shape: const RoundedRectangleBorder(borderRadius: LunaTheme.rNote),
      content: Text('Line copied', style: LunaTheme.text(size: 13, color: LunaTheme.onInk)),
    ));
  }

  @override
  Widget build(BuildContext context) {
    return Stack(
      children: <Widget>[
        widget.child,
        if (_open)
          Positioned(
            left: 0,
            right: 0,
            bottom: 0,
            child: AnimatedBuilder(
              animation: _log,
              builder: (BuildContext context, Widget? _) => _panel(context),
            ),
          )
        else
          Positioned(
            // Hard against the right edge, a third of the way up: clear of the
            // composer, where the only Stop control lives, and clear of every
            // screen header.
            right: 0,
            bottom: MediaQuery.of(context).size.height * 0.30,
            child: AnimatedBuilder(
              animation: _log,
              builder: (BuildContext context, Widget? _) => _button(),
            ),
          ),
      ],
    );
  }

  /// Closed: a small tab on the edge. Filled when something has failed, with
  /// the count, so you do not have to open it to know.
  Widget _button() {
    final int failures = _log.errorCount;
    final bool bad = failures > 0;
    return Semantics(
      button: true,
      label: 'Open the debug log',
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: () => setState(() => _open = true),
        child: Container(
          width: 27,
          padding: const EdgeInsets.symmetric(vertical: 9),
          decoration: BoxDecoration(
            color: bad ? LunaTheme.ink : LunaTheme.fill2,
            borderRadius: const BorderRadius.horizontal(left: Radius.circular(999)),
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Glyph(
                FontAwesomeIcons.bug,
                size: 12,
                color: bad ? LunaTheme.onInk : LunaTheme.ink3,
              ),
              if (bad) ...<Widget>[
                const SizedBox(height: 4),
                Text(
                  failures > 99 ? '99' : '$failures',
                  style: LunaTheme.text(size: 10, weight: 600, color: LunaTheme.onInk),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }

  /// Open. Half the screen, so the app behind it is still visible.
  Widget _panel(BuildContext context) {
    final List<LogLine> lines = _errorsOnly
        ? _log.lines.where((LogLine line) => line.bad).toList()
        : _log.lines;
    final double height = MediaQuery.of(context).size.height * 0.52;

    return ClipRRect(
      borderRadius: LunaTheme.rSheet,
      child: Container(
        height: height,
        color: LunaTheme.paper,
        child: Column(
        children: <Widget>[
          Container(height: 1, color: LunaTheme.line),
          _header(lines.length),
          Padding(
            padding: const EdgeInsets.only(bottom: 8),
            child: Segmented(
              items: const <String>['Everything', 'Errors'],
              index: _errorsOnly ? 1 : 0,
              onChanged: (int index) => setState(() => _errorsOnly = index == 1),
            ),
          ),
          Expanded(
            child: lines.isEmpty
                ? Center(
                    child: Text(
                      _errorsOnly ? 'Nothing has failed.' : 'Nothing logged yet.',
                      style: LunaTheme.text(size: 13, color: LunaTheme.ink3),
                    ),
                  )
                : ListView.builder(
                    padding: const EdgeInsets.fromLTRB(14, 2, 14, 16),
                    itemCount: lines.length,
                    itemBuilder: (BuildContext context, int index) => _line(lines[index]),
                  ),
          ),
          SizedBox(height: MediaQuery.of(context).padding.bottom),
        ],
        ),
      ),
    );
  }

  Widget _header(int shown) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 14, 12, 10),
      child: Row(
        children: <Widget>[
          Text('Debug log', style: LunaTheme.displayStyle(size: 16.5, weight: 700)),
          const SizedBox(width: 8),
          Text(
            _copied ? 'Copied' : '$shown ${shown == 1 ? 'line' : 'lines'}',
            style: LunaTheme.text(size: 12, color: LunaTheme.ink3),
          ),
          const Spacer(),
          IconButtonSoft(
            icon: _copied ? FontAwesomeIcons.check : FontAwesomeIcons.copy,
            label: 'Copy the log',
            active: _copied,
            onTap: _copy,
          ),
          const SizedBox(width: 6),
          IconButtonSoft(
            icon: FontAwesomeIcons.trashCan,
            label: 'Clear the log',
            onTap: () async {
              await widget.core.clearLog();
              if (mounted) setState(() {});
            },
          ),
          const SizedBox(width: 6),
          IconButtonSoft(
            icon: FontAwesomeIcons.chevronDown,
            label: 'Close the debug log',
            onTap: () => setState(() => _open = false),
          ),
        ],
      ),
    );
  }

  /// One line: when, where, what. Failures carry weight, not colour.
  Widget _line(LogLine line) {
    final bool bad = line.bad;
    final bool warn = line.level == 'warn';
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onLongPress: () => _copyOne(line),
      child: Container(
        margin: const EdgeInsets.only(bottom: 2),
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 7),
        decoration: BoxDecoration(
          color: bad ? LunaTheme.fill2 : null,
          borderRadius: LunaTheme.rTile,
        ),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(line.clock, style: LunaTheme.monoStyle(size: 10.5, color: LunaTheme.ink4)),
            const SizedBox(width: 8),
            SizedBox(
              width: 58,
              child: Text(
                line.where,
                overflow: TextOverflow.ellipsis,
                style: LunaTheme.monoStyle(
                  size: 10.5,
                  weight: 600,
                  color: bad ? LunaTheme.ink : LunaTheme.ink3,
                ),
              ),
            ),
            const SizedBox(width: 6),
            Expanded(
              child: Text(
                line.what,
                style: LunaTheme.monoStyle(
                  size: 11,
                  weight: bad ? 600 : 400,
                  color: bad || warn ? LunaTheme.ink : LunaTheme.ink2,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
