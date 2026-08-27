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

void main() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
    statusBarColor: Color(0x00000000),
    statusBarIconBrightness: Brightness.dark,
    systemNavigationBarColor: LunaTheme.paper,
    systemNavigationBarIconBrightness: Brightness.dark,
  ));
  runApp(const LunaApp());
}

class LunaApp extends StatefulWidget {
  const LunaApp({super.key});

  @override
  State<LunaApp> createState() => _LunaAppState();
}

class _LunaAppState extends State<LunaApp> {
  final LunaCore _core = LunaCore();

  @override
  void initState() {
    super.initState();
    _core.load();
  }

  @override
  void dispose() {
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
        scaffoldBackgroundColor: LunaTheme.paper,
        canvasColor: LunaTheme.paper,
        splashFactory: NoSplash.splashFactory,
        highlightColor: const Color(0x00000000),
        fontFamily: LunaTheme.sans,
        colorScheme: const ColorScheme.light(
          primary: LunaTheme.ink,
          secondary: LunaTheme.ink,
          surface: LunaTheme.paper,
        ),
        textSelectionTheme: const TextSelectionThemeData(
          cursorColor: LunaTheme.ink,
          selectionColor: LunaTheme.fill2,
          selectionHandleColor: LunaTheme.ink2,
        ),
      ),
      home: AnimatedBuilder(
        animation: _core,
        builder: (BuildContext context, Widget? _) => Shell(core: _core),
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

    return Scaffold(
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
    );
  }

  Widget _tabBar() {
    return Container(
      decoration: const BoxDecoration(
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
