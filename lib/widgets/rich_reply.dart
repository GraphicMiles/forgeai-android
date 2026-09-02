import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';

import '../theme.dart';

/// The model writes markdown. It always has.
///
/// Nothing rendered it, so a reply arrived on screen as
/// `**"Escape 100 Cops"**` — asterisks and all — and the emphasis the model
/// meant became noise the person has to read past. Pulling in a markdown
/// package for this would be a lot of machinery for the handful of marks a
/// chat reply actually uses, so this covers those and leaves everything else
/// as plain text.
///
/// Supported, deliberately: `**bold**`, `*italic*`, `` `code` ``, bullet
/// lines, and numbered lines. Anything unrecognised is shown exactly as the
/// model wrote it rather than swallowed, because silently eating a character
/// is worse than printing one.
class RichReply extends StatelessWidget {
  const RichReply(this.text, {super.key, this.style, this.onLinkTap});

  final String text;
  final TextStyle? style;
  final void Function(String url)? onLinkTap;

  @override
  Widget build(BuildContext context) {
    final TextStyle base = style ?? LunaTheme.body;
    final List<Widget> lines = <Widget>[];

    for (final String raw in text.split('\n')) {
      final String line = raw.trimRight();
      if (line.trim().isEmpty) {
        lines.add(const SizedBox(height: 10));
        continue;
      }

      final RegExpMatch? bullet =
          RegExp(r'^\s*[-*•]\s+(.*)$').firstMatch(line);
      final RegExpMatch? numbered =
          RegExp(r'^\s*(\d+)[.)]\s+(.*)$').firstMatch(line);

      if (bullet != null) {
        lines.add(_indented('•  ', bullet.group(1) ?? '', base));
        continue;
      }
      if (numbered != null) {
        lines.add(_indented(
            '${numbered.group(1)}.  ', numbered.group(2) ?? '', base));
        continue;
      }
      lines.add(Text.rich(TextSpan(children: _spans(line, base)),
          style: base));
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      mainAxisSize: MainAxisSize.min,
      children: lines,
    );
  }

  /// A list row: the marker sits in its own column so a wrapped second line
  /// lines up under the text and not under the bullet.
  Widget _indented(String marker, String rest, TextStyle base) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Text(marker, style: base),
          Expanded(
            child: Text.rich(TextSpan(children: _spans(rest, base)),
                style: base),
          ),
        ],
      ),
    );
  }

  /// One line, split into runs of styled text.
  ///
  /// Order matters: `**` has to be tested before `*`, or every bold marker
  /// reads as two empty italics.
  List<InlineSpan> _spans(String line, TextStyle base) {
    final List<InlineSpan> out = <InlineSpan>[];
    final RegExp pattern = RegExp(
      r'(\*\*(.+?)\*\*)'      // bold
      r'|(__(.+?)__)'          // bold, the other spelling
      r'|(\*(?!\s)(.+?)(?<!\s)\*)'  // italic, not a stray asterisk
      r'|(`(.+?)`)'            // inline code
      r'|(\[(.+?)\]\((\S+?)\))' // link
      r'|((?:https?://)\S+)',  // a bare url
    );

    int at = 0;
    for (final RegExpMatch m in pattern.allMatches(line)) {
      if (m.start > at) {
        out.add(TextSpan(text: line.substring(at, m.start)));
      }
      if (m.group(2) != null) {
        out.add(TextSpan(
            text: m.group(2),
            style: base.copyWith(fontWeight: FontWeight.w700)));
      } else if (m.group(4) != null) {
        out.add(TextSpan(
            text: m.group(4),
            style: base.copyWith(fontWeight: FontWeight.w700)));
      } else if (m.group(6) != null) {
        out.add(TextSpan(
            text: m.group(6),
            style: base.copyWith(fontStyle: FontStyle.italic)));
      } else if (m.group(8) != null) {
        out.add(TextSpan(
          text: m.group(8),
          style: base.copyWith(
            fontFamily: 'monospace',
            fontSize: (base.fontSize ?? 14.5) - 0.5,
            color: LunaTheme.ink2,
          ),
        ));
      } else if (m.group(10) != null) {
        out.add(_link(m.group(10) ?? '', m.group(11) ?? '', base));
      } else if (m.group(12) != null) {
        final String url = m.group(12) ?? '';
        out.add(_link(url, url, base));
      }
      at = m.end;
    }
    if (at < line.length) {
      out.add(TextSpan(text: line.substring(at)));
    }
    return out;
  }

  InlineSpan _link(String label, String url, TextStyle base) {
    // The palette is monochrome on purpose, so a link is marked by the
    // underline and the weight rather than by a colour that does not exist.
    final TextStyle linked = base.copyWith(
      color: LunaTheme.ink,
      fontWeight: FontWeight.w500,
      decoration: TextDecoration.underline,
      decorationColor: LunaTheme.ink3,
    );
    if (onLinkTap == null) {
      return TextSpan(text: label, style: linked);
    }
    return TextSpan(
      text: label,
      style: linked,
      recognizer: TapGestureRecognizer()..onTap = () => onLinkTap!(url),
    );
  }
}
