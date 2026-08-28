import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:font_awesome_flutter/font_awesome_flutter.dart';

import 'core/luna_core.dart';
import 'screens/chat_screen.dart';
import 'screens/files_screen.dart';
import 'screens/models_screen.dart';
import 'screens/settings_screen.dart';
import 'theme.dart';
import 'widgets/common.dart';
import 'widgets/debug_panel.dart';

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  runApp(const LunaApp());
}

/// The status and navigation bars have to follow the palette, or dark mode
/// ends with two white strips framing a black app.
void _paintSystemBars() {
  final Brightness icons = LunaTheme.isDark ? Brightness.light : Brightness.dark;
  SystemChrome.setSystemUIOverlayStyle(SystemUiOverlayStyle(
    statusBarColor: const Color(0x00000000),
    statusBarIconBrightness: icons,
    statusBarBrightness: LunaTheme.isDark ? Brightness.dark : Brightness.light,
    systemNavigationBarColor: LunaTheme.paper,
    systemNavigationBarIconBrightness: icons,
  ));
}

class LunaApp extends StatefulWidget {
  const LunaApp({super.key});

  @override
  State<LunaApp> createState() => _LunaAppState();
}

class _LunaAppState extends State<LunaApp> with WidgetsBindingObserver {
  final LunaCore _core = LunaCore();
  String _applied = '';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    // A widget that throws should say so where the user can read it, not only
    // in a console nobody has attached.
    final FlutterExceptionHandler? previous = FlutterError.onError;
    FlutterError.onError = (FlutterErrorDetails details) {
      _core.debug.fail('flutter', details.exceptionAsString());
      if (previous != null) previous(details);
    };
    WidgetsBinding.instance.platformDispatcher.onError = (Object error, StackTrace stack) {
      _core.debug.fail('dart', '$error');
      return false;
    };
    _core.addListener(_syncTheme);
    _core.load();
    _syncTheme();
  }

  /// The phone changed between light and dark while we were open.
  @override
  void didChangePlatformBrightness() {
    _syncTheme();
  }

  /// One place decides which palette is live: the setting, or the phone when
  /// the setting says to follow it.
  void _syncTheme() {
    final Brightness system =
        WidgetsBinding.instance.platformDispatcher.platformBrightness;
    final bool dark = _core.theme == 'dark' ||
        (_core.theme == 'system' && system == Brightness.dark);
    final String key = '$dark';
    if (key == _applied) return;
    _applied = key;
    LunaTheme.apply(dark: dark);
    _paintSystemBars();
    if (mounted) setState(() {});
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _core.removeListener(_syncTheme);
    _core.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Luna',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        useMaterial3: true,
        brightness: LunaTheme.isDark ? Brightness.dark : Brightness.light,
        scaffoldBackgroundColor: LunaTheme.paper,
        canvasColor: LunaTheme.paper,
        splashFactory: NoSplash.splashFactory,
        highlightColor: const Color(0x00000000),
        fontFamily: LunaTheme.sans,
        colorScheme: ColorScheme.fromSeed(
          seedColor: LunaTheme.ink,
          brightness: LunaTheme.isDark ? Brightness.dark : Brightness.light,
        ).copyWith(
          primary: LunaTheme.ink,
          secondary: LunaTheme.ink,
          surface: LunaTheme.paper,
        ),
        textSelectionTheme: TextSelectionThemeData(
          cursorColor: LunaTheme.ink,
          selectionColor: LunaTheme.fill2,
          selectionHandleColor: LunaTheme.ink2,
        ),
      ),
      home: AnimatedBuilder(
        animation: _core,
        builder: (BuildContext context, Widget? _) => MediaQuery.withClampedTextScaling(
          minScaleFactor: _core.textScale,
          maxScaleFactor: _core.textScale,
          child: Shell(core: _core),
        ),
      ),
    );
  }
}

/// The four screens, and nothing above them but a tab bar.
class Shell extends StatefulWidget {
  const Shell({super.key, required this.core});

  final LunaCore core;

  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> {
  int _tab = 0;

  static const List<_Tab> _tabs = <_Tab>[
    _Tab('Chat', FontAwesomeIcons.solidComment, FontAwesomeIcons.comment),
    _Tab('Files', FontAwesomeIcons.solidFolder, FontAwesomeIcons.folder),
    _Tab('Models', FontAwesomeIcons.cube, FontAwesomeIcons.cube),
    _Tab('Settings', FontAwesomeIcons.gear, FontAwesomeIcons.gear),
  ];

  void _ask(String prompt) {
    setState(() => _tab = 0);
    widget.core.send(prompt);
  }

  @override
  Widget build(BuildContext context) {
    final LunaCore core = widget.core;
    if (!core.ready) {
      return const Scaffold(
        body: Center(child: Mark(size: 44)),
      );
    }

    final List<Widget> screens = <Widget>[
      ChatScreen(
        core: core,
        onOpenModels: () => setState(() => _tab = 2),
      ),
      FilesScreen(core: core, onAsk: _ask),
      ModelsScreen(core: core),
      SettingsScreen(core: core),
    ];

    if (!core.walkthroughDone) {
      return Scaffold(
        backgroundColor: LunaTheme.paper,
        body: SafeArea(child: _Walkthrough(core: core)),
      );
    }

    return DebugOverlay(
      core: core,
      child: Scaffold(
        backgroundColor: LunaTheme.paper,
        resizeToAvoidBottomInset: true,
        body: SafeArea(
          bottom: false,
          child: Column(
            children: <Widget>[
              Expanded(child: IndexedStack(index: _tab, children: screens)),
              _tabBar(),
            ],
          ),
        ),
      ),
    );
  }

  Widget _tabBar() {
    return Container(
      decoration: BoxDecoration(
        color: LunaTheme.paper,
        border: Border(top: BorderSide(color: LunaTheme.line, width: 1)),
      ),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(10, 6, 10, 2),
          child: Row(
            children: List<Widget>.generate(_tabs.length, (int index) {
              final _Tab tab = _tabs[index];
              final bool selected = index == _tab;
              return Expanded(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => setState(() => _tab = index),
                  child: Padding(
                    padding: const EdgeInsets.symmetric(vertical: 6),
                    child: Column(
                      mainAxisSize: MainAxisSize.min,
                      children: <Widget>[
                        Glyph(
                          selected ? tab.active : tab.idle,
                          size: 15,
                          color: selected ? LunaTheme.ink : LunaTheme.ink4,
                        ),
                        const SizedBox(height: 5),
                        Text(
                          tab.label,
                          style: LunaTheme.tab.copyWith(
                            color: selected ? LunaTheme.ink : LunaTheme.ink4,
                            fontWeight: selected ? FontWeight.w600 : FontWeight.w500,
                          ),
                        ),
                      ],
                    ),
                  ),
                ),
              );
            }),
          ),
        ),
      ),
    );
  }
}

class _Tab {
  const _Tab(this.label, this.active, this.idle);

  final String label;
  final FaIconData active;
  final FaIconData idle;
}


/// Shown once. Three plain facts and a folder, because the app is useless
/// until Luna has somewhere to look and a model to think with.
class _Walkthrough extends StatefulWidget {
  const _Walkthrough({required this.core});

  final LunaCore core;

  @override
  State<_Walkthrough> createState() => _WalkthroughState();
}

class _WalkthroughState extends State<_Walkthrough> {
  @override
  Widget build(BuildContext context) {
    final LunaCore core = widget.core;
    return ListView(
      padding: const EdgeInsets.fromLTRB(0, 40, 0, 24),
      children: <Widget>[
        const Center(child: Mark(size: 52)),
        const SizedBox(height: 18),
        Center(child: Text('Luna', style: LunaTheme.displayStyle(size: 28, weight: 700))),
        const SizedBox(height: 6),
        Padding(
          padding: const EdgeInsets.symmetric(horizontal: 40),
          child: Text(
            'A local agent. It runs on this phone, and it only touches what you hand it.',
            textAlign: TextAlign.center,
            style: LunaTheme.text(size: 13.5, color: LunaTheme.ink3, height: 1.5),
          ),
        ),
        const SizedBox(height: 26),
        Group(children: <Widget>[
          LunaRow(
            icon: FontAwesomeIcons.folderOpen,
            title: 'One folder',
            subtitle: core.workspaceGranted
                ? 'Granted: ${core.workspaceName}'
                : 'Choose the folder Luna is allowed to see. Nothing outside it exists to her.',
            trailing: PillButton(
              label: core.workspaceGranted ? 'Change' : 'Choose',
              small: true,
              soft: core.workspaceGranted,
              onTap: () async {
                await core.pickFolder();
                if (mounted) setState(() {});
              },
            ),
          ),
          const LunaRow(
            icon: FontAwesomeIcons.hand,
            title: 'It stops before it changes anything',
            subtitle: 'Writing, renaming and deleting wait for you to say yes.',
          ),
          const LunaRow(
            icon: FontAwesomeIcons.cube,
            title: 'It needs a model',
            subtitle: 'Download one in the Model Zoo, point at Ollama, or add a cloud key.',
          ),
        ]),
        const SizedBox(height: 22),
        Center(
          child: PillButton(
            label: 'Start',
            icon: FontAwesomeIcons.arrowRight,
            onTap: core.finishWalkthrough,
          ),
        ),
        const SizedBox(height: 10),
        Center(
          child: Text(
            'No account. No telemetry.',
            style: LunaTheme.text(size: 12, color: LunaTheme.ink4),
          ),
        ),
      ],
    );
  }
}
