import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:font_awesome_flutter/font_awesome_flutter.dart';

import '../theme.dart';

/// The Luna mascot: a crescent with two closed eyes. The single exception to
/// the monochrome rule, and the only thing in the app that carries colour.
class Mark extends StatelessWidget {
  const Mark({super.key, this.size = 32, this.chip = false});

  final double size;
  final bool chip;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: CustomPaint(painter: _MarkPainter(chip: chip)),
    );
  }
}

class _MarkPainter extends CustomPainter {
  _MarkPainter({required this.chip});

  final bool chip;

  @override
  void paint(Canvas canvas, Size size) {
    final Paint body = Paint()..color = chip ? LunaTheme.mascotChip : LunaTheme.mascot;
    final Rect bounds = Offset.zero & size;

    if (chip) {
      canvas.drawRRect(
        RRect.fromRectAndRadius(bounds, Radius.circular(size.width * 0.28)),
        body,
      );
    } else {
      // A blob, then a bite out of the top-right shoulder: a moon, not a circle.
      final Path moon = Path()..addOval(bounds.deflate(size.width * 0.02));
      final Path bite = Path()
        ..addOval(Rect.fromCircle(
          center: Offset(size.width * 0.98, size.height * 0.10),
          radius: size.width * 0.44,
        ));
      canvas.drawPath(Path.combine(PathOperation.difference, moon, bite), body);
    }

    // Two sleeping eyes, tilted towards each other.
    final Paint eye = Paint()..color = LunaTheme.paper;
    final double eyeWidth = size.width * 0.085;
    final double eyeHeight = size.height * (chip ? 0.22 : 0.25);
    final Offset centre = Offset(size.width * (chip ? 0.5 : 0.47), size.height * 0.52);

    for (int index = 0; index < 2; index++) {
      final double dx = index == 0 ? -size.width * 0.12 : size.width * 0.12;
      final double angle = (index == 0 ? -1 : 1) * 0.19;
      canvas.save();
      canvas.translate(centre.dx + dx, centre.dy);
      canvas.rotate(angle);
      canvas.drawRRect(
        RRect.fromRectAndRadius(
          Rect.fromCenter(center: Offset.zero, width: eyeWidth, height: eyeHeight),
          Radius.circular(eyeWidth),
        ),
        eye,
      );
      canvas.restore();
    }
  }

  @override
  bool shouldRepaint(covariant _MarkPainter oldDelegate) => oldDelegate.chip != chip;
}

/// A FontAwesome glyph at the app's sizes. Icons are never decorative here:
/// each one has to say what the thing it labels actually is.
class Glyph extends StatelessWidget {
  const Glyph(this.icon, {super.key, this.size = 13.5, this.color});

  final FaIconData icon;
  final double size;
  final Color? color;

  @override
  Widget build(BuildContext context) =>
      FaIcon(icon, size: size, color: color ?? LunaTheme.ink2);
}

/// The circular 34px action button in a screen header.
class IconButtonSoft extends StatelessWidget {
  const IconButtonSoft({super.key, required this.icon, this.onTap, this.active = false, this.label});

  final FaIconData icon;
  final VoidCallback? onTap;
  final bool active;
  final String? label;

  @override
  Widget build(BuildContext context) {
    return Semantics(
      button: true,
      label: label,
      child: GestureDetector(
        behavior: HitTestBehavior.opaque,
        onTap: onTap,
        child: Container(
          width: 34,
          height: 34,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: active ? LunaTheme.ink : LunaTheme.fill,
            shape: BoxShape.circle,
          ),
          child: Glyph(icon, size: 13, color: active ? LunaTheme.onInk : LunaTheme.ink2),
        ),
      ),
    );
  }
}

/// Screen header: one 27px title, or a 16.5px name with the mascot beside it.
class ScreenTop extends StatelessWidget {
  const ScreenTop({
    super.key,
    required this.title,
    this.small = false,
    this.leading,
    this.actions = const <Widget>[],
  });

  final String title;
  final bool small;
  final Widget? leading;
  final List<Widget> actions;

  @override
  Widget build(BuildContext context) {
    final List<Widget> children = <Widget>[];
    if (leading != null) {
      children.add(leading!);
      children.add(const SizedBox(width: 9));
    }
    children.add(Expanded(
      child: Text(
        title,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: small ? LunaTheme.screenTitleSmall : LunaTheme.screenTitle,
      ),
    ));
    for (final Widget action in actions) {
      children.add(const SizedBox(width: 9));
      children.add(action);
    }
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 10),
      child: Row(children: children),
    );
  }
}

/// Grey section label. Sentence case, never uppercase micro-type.
class SectionLabel extends StatelessWidget {
  const SectionLabel(this.text, {super.key, this.action, this.tight = false});

  final String text;
  final Widget? action;
  final bool tight;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.fromLTRB(20, tight ? 10 : 14, 20, 7),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.baseline,
        textBaseline: TextBaseline.alphabetic,
        children: <Widget>[
          Text(text, style: LunaTheme.sectionLabel),
          const Spacer(),
          if (action != null) action!,
        ],
      ),
    );
  }
}

/// A grey rounded group. Rows inside get hairline separators.
class Group extends StatelessWidget {
  const Group({super.key, required this.children, this.plain = false});

  final List<Widget> children;
  final bool plain;

  @override
  Widget build(BuildContext context) {
    final List<Widget> rows = <Widget>[];
    for (int index = 0; index < children.length; index++) {
      rows.add(children[index]);
      if (index != children.length - 1) {
        rows.add(Container(height: 1, color: const Color(0x0D000000)));
      }
    }
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 20),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 2),
      decoration: BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rGroup),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: rows),
    );
  }
}

/// A plain white list with hairline separators.
class PlainList extends StatelessWidget {
  const PlainList({super.key, required this.children});

  final List<Widget> children;

  @override
  Widget build(BuildContext context) {
    final List<Widget> rows = <Widget>[];
    for (int index = 0; index < children.length; index++) {
      rows.add(children[index]);
      if (index != children.length - 1) {
        rows.add(Container(height: 1, color: LunaTheme.line));
      }
    }
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 20),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: rows),
    );
  }
}

/// One row: tile, title, subtitle, and whatever sits at the end.
/// A row. When it does something it is announced as a button, with its
/// subtitle read out as the hint — a screen reader should get the same
/// information a sighted person gets from the second line.
class LunaRow extends StatelessWidget {
  const LunaRow({
    super.key,
    this.icon,
    required this.title,
    this.subtitle,
    this.trailing,
    this.onTap,
    this.muted = false,
    this.tileOnFill = false,
    this.child,
  });

  final FaIconData? icon;
  final String title;
  final String? subtitle;
  final Widget? trailing;
  final VoidCallback? onTap;
  final bool muted;
  final bool tileOnFill;
  final Widget? child;

  @override
  Widget build(BuildContext context) {
    final Color titleColor = muted ? LunaTheme.ink4 : LunaTheme.ink;
    final Widget content = Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        children: <Widget>[
          if (icon != null) ...<Widget>[
            Container(
              width: 34,
              height: 34,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: tileOnFill ? LunaTheme.fill : LunaTheme.paper,
                borderRadius: LunaTheme.rTile,
              ),
              child: Glyph(icon!, size: 13.5, color: muted ? LunaTheme.ink4 : LunaTheme.ink2),
            ),
            const SizedBox(width: 12),
          ],
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                Text(title,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: LunaTheme.rowTitle.copyWith(color: titleColor)),
                if (subtitle != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 1),
                    child: Text(subtitle!,
                        maxLines: 1, overflow: TextOverflow.ellipsis, style: LunaTheme.rowSub),
                  ),
                if (child != null) child!,
              ],
            ),
          ),
          if (trailing != null) ...<Widget>[const SizedBox(width: 12), trailing!],
        ],
      ),
    );
    if (onTap == null) {
      return Semantics(label: title, hint: subtitle, child: content);
    }
    return Semantics(
      button: true,
      label: title,
      hint: subtitle,
      child: GestureDetector(behavior: HitTestBehavior.opaque, onTap: onTap, child: content),
    );
  }
}

/// Pill button. Filled black to act, grey to offer.
class PillButton extends StatelessWidget {
  const PillButton({
    super.key,
    required this.label,
    this.icon,
    this.onTap,
    this.soft = false,
    this.small = false,
    this.enabled = true,
  });

  final String label;
  final FaIconData? icon;
  final VoidCallback? onTap;
  final bool soft;
  final bool small;
  final bool enabled;

  @override
  Widget build(BuildContext context) {
    final Color background = soft || !enabled ? LunaTheme.fill : LunaTheme.ink;
    final Color foreground = !enabled
        ? LunaTheme.ink4
        : soft
            ? LunaTheme.ink
            : LunaTheme.onInk;
    return Semantics(
      button: true,
      label: label,
      enabled: enabled,
      child: GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: enabled ? onTap : null,
      child: Container(
        padding: small
            ? const EdgeInsets.symmetric(horizontal: 14, vertical: 6)
            : const EdgeInsets.symmetric(horizontal: 18, vertical: 10),
        decoration: BoxDecoration(color: background, borderRadius: LunaTheme.rPill),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            if (icon != null) ...<Widget>[
              Glyph(icon!, size: small ? 11 : 12, color: foreground),
              const SizedBox(width: 7),
            ],
            Text(label,
                style: LunaTheme.button
                    .copyWith(color: foreground, fontSize: small ? 12.5 : 13.5)),
          ],
        ),
      ),
      ),
    );
  }
}

/// Segmented control. The selected segment is white on grey, never coloured.
class Segmented extends StatelessWidget {
  const Segmented({super.key, required this.items, required this.index, required this.onChanged});

  final List<String> items;
  final int index;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 20),
      padding: const EdgeInsets.all(3),
      decoration: BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rPill),
      child: Row(
        children: List<Widget>.generate(items.length, (int itemIndex) {
          final bool selected = itemIndex == index;
          return Expanded(
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: () => onChanged(itemIndex),
              child: Container(
                padding: const EdgeInsets.symmetric(vertical: 7),
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: selected ? LunaTheme.paper : null,
                  borderRadius: LunaTheme.rPill,
                ),
                child: Text(
                  items[itemIndex],
                  style: LunaTheme.text(
                    size: 12.5,
                    weight: selected ? 600 : 500,
                    color: selected ? LunaTheme.ink : LunaTheme.ink3,
                  ),
                ),
              ),
            ),
          );
        }),
      ),
    );
  }
}

/// The toggle. Filled black is on; grey is off. No colour, no glow.
class LunaSwitch extends StatelessWidget {
  const LunaSwitch({super.key, required this.value, this.onChanged});

  final bool value;
  final ValueChanged<bool>? onChanged;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onChanged == null ? null : () => onChanged!(!value),
      child: Container(
        width: 44,
        height: 26,
        decoration: BoxDecoration(
          color: value ? LunaTheme.ink : LunaTheme.fill2,
          borderRadius: LunaTheme.rPill,
        ),
        child: Stack(
          children: <Widget>[
            AnimatedPositioned(
              duration: const Duration(milliseconds: 140),
              curve: Curves.easeOut,
              left: value ? 21 : 3,
              top: 3,
              child: Container(
                width: 20,
                height: 20,
                decoration: BoxDecoration(color: LunaTheme.paper, shape: BoxShape.circle),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// A chip. Grey means free; filled black means it stops for you.
class LunaChip extends StatelessWidget {
  const LunaChip(this.label, {super.key, this.held = false, this.mono = false});

  final String label;
  final bool held;
  final bool mono;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.only(right: 5, bottom: 6),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 5),
      decoration: BoxDecoration(
        color: held ? LunaTheme.ink : LunaTheme.fill,
        borderRadius: LunaTheme.rPill,
      ),
      child: Text(
        label,
        style: mono
            ? LunaTheme.monoStyle(size: 11.5, color: held ? LunaTheme.onInk : LunaTheme.ink2)
            : LunaTheme.text(
                size: 12,
                weight: held ? 550 : 500,
                color: held ? LunaTheme.onInk : LunaTheme.ink2,
              ),
      ),
    );
  }
}

/// The grey explanatory note, with the glyph that names its subject.
class Note extends StatelessWidget {
  const Note({super.key, required this.icon, required this.children});

  final FaIconData icon;
  final List<InlineSpan> children;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.fromLTRB(20, 9, 20, 0),
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rNote),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Padding(
            padding: const EdgeInsets.only(top: 2),
            child: Glyph(icon, size: 12, color: LunaTheme.ink),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Text.rich(
              TextSpan(children: children),
              style: LunaTheme.text(size: 12.5, color: LunaTheme.ink2, height: 1.45),
            ),
          ),
        ],
      ),
    );
  }
}

/// Empty state. Says what is missing and what to do about it.
class EmptyState extends StatelessWidget {
  const EmptyState({
    super.key,
    required this.icon,
    required this.title,
    required this.body,
    this.action,
    this.mascot = false,
  });

  final FaIconData icon;
  final String title;
  final String body;
  final Widget? action;
  final bool mascot;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.fromLTRB(20, 10, 20, 0),
      padding: const EdgeInsets.symmetric(horizontal: 18, vertical: 20),
      decoration: BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rGroup),
      child: Column(
        children: <Widget>[
          if (mascot) const Mark(size: 40) else Glyph(icon, size: 18, color: LunaTheme.ink4),
          const SizedBox(height: 9),
          Text(title,
              textAlign: TextAlign.center,
              style: LunaTheme.displayStyle(size: 15, weight: 700, letterSpacing: -0.02)),
          const SizedBox(height: 4),
          Text(body,
              textAlign: TextAlign.center,
              style: LunaTheme.text(size: 13, color: LunaTheme.ink3, height: 1.5)),
          if (action != null) ...<Widget>[const SizedBox(height: 12), action!],
        ],
      ),
    );
  }
}

/// Every secondary surface in the app is one of these.
Future<T?> showLunaSheet<T>({
  required BuildContext context,
  required String title,
  required WidgetBuilder builder,
  Widget? action,
}) {
  return showModalBottomSheet<T>(
    context: context,
    backgroundColor: LunaTheme.paper,
    barrierColor: const Color(0x520B0B0C),
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(borderRadius: LunaTheme.rSheet),
    builder: (BuildContext sheetContext) {
      return SafeArea(
        top: false,
        child: Padding(
          padding: EdgeInsets.only(bottom: MediaQuery.of(sheetContext).viewInsets.bottom),
          child: ConstrainedBox(
            constraints: BoxConstraints(
              maxHeight: MediaQuery.of(sheetContext).size.height * 0.86,
            ),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                const SizedBox(height: 9),
                Container(
                  width: 38,
                  height: 4,
                  decoration: BoxDecoration(
                      color: LunaTheme.fill2, borderRadius: LunaTheme.rPill),
                ),
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 8, 20, 10),
                  child: Row(
                    children: <Widget>[
                      Expanded(
                        child: Text(title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: LunaTheme.displayStyle(size: 19, weight: 700)),
                      ),
                      if (action != null) ...<Widget>[action, const SizedBox(width: 9)],
                      IconButtonSoft(
                        icon: FontAwesomeIcons.xmark,
                        label: 'Close',
                        onTap: () => Navigator.of(sheetContext).pop(),
                      ),
                    ],
                  ),
                ),
                Flexible(
                  child: SingleChildScrollView(
                    padding: const EdgeInsets.only(bottom: 14),
                    child: Builder(builder: builder),
                  ),
                ),
              ],
            ),
          ),
        ),
      );
    },
  );
}

/// A text field in the app's shape language.
class LunaField extends StatelessWidget {
  const LunaField({
    super.key,
    required this.label,
    required this.controller,
    this.hint,
    this.help,
    this.mono = false,
    this.autofocus = false,
    this.onSubmitted,
    this.onChanged,
    this.obscure = false,
    this.maxLines = 1,
  });

  final String label;
  final TextEditingController controller;
  final String? hint;
  final String? help;
  final bool mono;
  final bool autofocus;
  final ValueChanged<String>? onSubmitted;
  final ValueChanged<String>? onChanged;
  final bool obscure;

  /// More than one for a field that holds a list, like extra headers.
  final int maxLines;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 0, 20, 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(label, style: LunaTheme.sectionLabel),
          const SizedBox(height: 6),
          Container(
            decoration: BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rField),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 4),
            child: TextField(
              controller: controller,
              autofocus: autofocus,
              onSubmitted: onSubmitted,
              onChanged: onChanged,
              obscureText: obscure,
              minLines: 1,
              maxLines: obscure ? 1 : maxLines,
              cursorColor: LunaTheme.ink,
              cursorWidth: 1.6,
              style: mono
                  ? LunaTheme.monoStyle(size: 13, color: LunaTheme.ink)
                  : LunaTheme.text(size: 13.5),
              decoration: InputDecoration(
                isDense: true,
                border: InputBorder.none,
                hintText: hint,
                hintStyle: LunaTheme.text(size: 13.5, color: LunaTheme.ink4),
                contentPadding: const EdgeInsets.symmetric(vertical: 9),
              ),
            ),
          ),
          if (help != null)
            Padding(
              padding: const EdgeInsets.only(top: 6),
              child: Text(help!,
                  style: LunaTheme.text(size: 11.5, color: LunaTheme.ink3, height: 1.4)),
            ),
        ],
      ),
    );
  }
}

/// A progress bar: black on grey, 5px, pill ends.
class ProgressBar extends StatelessWidget {
  const ProgressBar({super.key, required this.value});

  final double value;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 7),
      child: ClipRRect(
        borderRadius: LunaTheme.rPill,
        child: Container(
          height: 5,
          color: LunaTheme.fill2,
          child: FractionallySizedBox(
            alignment: Alignment.centerLeft,
            widthFactor: math.max(0, math.min(1, value)),
            child: Container(color: LunaTheme.ink),
          ),
        ),
      ),
    );
  }
}
