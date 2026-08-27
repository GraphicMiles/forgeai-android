import 'package:flutter/widgets.dart';

/// The Luna design system, ported one-for-one from docs/design/luna-screens.html.
///
/// Six monochrome values, soft radii, no shadows, no outlines on surfaces.
/// State reads through fill and weight, never hue. The mascot is the only
/// object in the app allowed to carry colour.
class LunaTheme {
  const LunaTheme._();

  // Ink
  static const Color ink = Color(0xFF0B0B0C);
  static const Color ink2 = Color(0xFF3D3D42);
  static const Color ink3 = Color(0xFF76767E);
  static const Color ink4 = Color(0xFFA3A3AB);

  // Surfaces
  static const Color line = Color(0xFFEDEDF0);
  static const Color fill = Color(0xFFF4F4F6);
  static const Color fill2 = Color(0xFFEAEAEE);
  static const Color paper = Color(0xFFFFFFFF);

  // Inside the one filled-black surface per screen
  static const Color onInk = Color(0xFFFFFFFF);
  static const Color onInkDim = Color(0xFF9A9AA2);
  static const Color onInkFaint = Color(0xFF8F8F98);
  static const Color inkCell = Color(0xFF1A1A1E);
  static const Color inkButton = Color(0xFF232327);

  // The mascot, and nothing else.
  static const Color mascot = Color(0xFF7C5CFF);
  static const Color mascotChip = Color(0xFF00C46A);

  static const String display = 'Manrope';
  static const String sans = 'Inter';
  static const String mono = 'JetBrainsMono';

  // Radii: 44 frame, 24 cards, 22 groups, 20 steps, 18 notes, 15 tiles, pills.
  static const BorderRadius rCard = BorderRadius.all(Radius.circular(24));
  static const BorderRadius rGroup = BorderRadius.all(Radius.circular(22));
  static const BorderRadius rStep = BorderRadius.all(Radius.circular(20));
  static const BorderRadius rNote = BorderRadius.all(Radius.circular(18));
  static const BorderRadius rField = BorderRadius.all(Radius.circular(15));
  static const BorderRadius rTile = BorderRadius.all(Radius.circular(12));
  static const BorderRadius rPill = BorderRadius.all(Radius.circular(999));
  static const BorderRadius rSheet =
      BorderRadius.only(topLeft: Radius.circular(28), topRight: Radius.circular(28));

  /// Manrope, weighted through the variable axis so the display face is real.
  static TextStyle displayStyle({
    required double size,
    required double weight,
    Color color = ink,
    double letterSpacing = -0.03,
    double height = 1.15,
  }) {
    return TextStyle(
      fontFamily: display,
      fontSize: size,
      color: color,
      height: height,
      letterSpacing: letterSpacing * size,
      fontVariations: <FontVariation>[FontVariation('wght', weight)],
      fontWeight: _nearest(weight),
    );
  }

  static TextStyle text({
    required double size,
    double weight = 400,
    Color color = ink,
    double letterSpacing = -0.012,
    double height = 1.4,
  }) {
    return TextStyle(
      fontFamily: sans,
      fontSize: size,
      color: color,
      height: height,
      letterSpacing: letterSpacing * size,
      fontVariations: <FontVariation>[FontVariation('wght', weight)],
      fontWeight: _nearest(weight),
    );
  }

  static TextStyle monoStyle({
    double size = 12.5,
    Color color = ink2,
    double weight = 400,
  }) {
    return TextStyle(
      fontFamily: mono,
      fontSize: size,
      color: color,
      height: 1.4,
      fontVariations: <FontVariation>[FontVariation('wght', weight)],
      fontWeight: _nearest(weight),
    );
  }

  // --- the type scale, named after what it labels ---------------------------

  /// 27px screen title. One per screen.
  static TextStyle get screenTitle =>
      displayStyle(size: 27, weight: 800, letterSpacing: -0.038, height: 1.1);

  /// 16.5px title for a screen that names a thing (a chat, a file).
  static TextStyle get screenTitleSmall =>
      displayStyle(size: 16.5, weight: 700, letterSpacing: -0.028, height: 1.2);

  /// 19px decision headline, on the black card.
  static TextStyle get decision =>
      displayStyle(size: 19, weight: 700, color: onInk, letterSpacing: -0.03, height: 1.22);

  static TextStyle get sectionLabel =>
      text(size: 12.5, weight: 600, color: ink3, letterSpacing: -0.005);

  static TextStyle get rowTitle => text(size: 14.5, weight: 600, color: ink, letterSpacing: -0.018, height: 1.3);

  static TextStyle get rowSub => text(size: 12, weight: 400, color: ink3, height: 1.35);

  static TextStyle get rowEnd => text(size: 12.5, weight: 400, color: ink3);

  static TextStyle get body => text(size: 14.5, weight: 400, height: 1.5);

  static TextStyle get bubble =>
      text(size: 14, weight: 400, color: onInk, letterSpacing: -0.01, height: 1.4);

  static TextStyle get button => text(size: 13.5, weight: 600, color: onInk, letterSpacing: -0.015);

  static TextStyle get tab => text(size: 10.5, weight: 500, color: ink4, letterSpacing: -0.01);

  static FontWeight _nearest(double weight) {
    if (weight >= 800) return FontWeight.w800;
    if (weight >= 700) return FontWeight.w700;
    if (weight >= 600) return FontWeight.w600;
    if (weight >= 500) return FontWeight.w500;
    return FontWeight.w400;
  }
}
