import 'package:flutter/material.dart';
import 'package:font_awesome_flutter/font_awesome_flutter.dart';

import '../core/luna_core.dart';
import '../theme.dart';
import '../widgets/common.dart';
import 'platform_pages.dart';

/// Settings — three groups, no more.
///
/// Agent decides how much rope Luna gets. Connections is anything that leaves
/// the device. Data is what is stored and how to get rid of it.
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, required this.core});

  final LunaCore core;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  int _tab = 0;

  /// The runtime pages live behind Settings rather than beside it. Null means
  /// the three tabs; anything else is one page in front of them.
  String? _page;

  late final TextEditingController _endpoint =
      TextEditingController(text: widget.core.endpoint);

  /// The address is editable on two screens. Without this the other screen
  /// keeps showing the old one until the app restarts.
  String _endpointSeen = '';

  void _syncEndpoint() {
    if (_endpointSeen == widget.core.endpoint) return;
    _endpointSeen = widget.core.endpoint;
    if (_endpoint.text != widget.core.endpoint) {
      _endpoint.text = widget.core.endpoint;
    }
  }

  @override
  void dispose() {
    _endpoint.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final LunaCore core = widget.core;
    _syncEndpoint();
    final String? page = _page;
    if (page != null) {
      return PlatformPage(
        core: core,
        page: page,
        onBack: () => setState(() => _page = null),
      );
    }
    return Column(
      children: <Widget>[
        const ScreenTop(title: 'Settings'),
        Segmented(
          items: const <String>['Agent', 'Connections', 'Data'],
          index: _tab,
          onChanged: (int index) => setState(() => _tab = index),
        ),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.only(bottom: 16),
            children: <Widget>[
              if (_tab == 0) ..._agent(core),
              if (_tab == 1) ..._connections(core),
              if (_tab == 2) ..._data(core),
            ],
          ),
        ),
      ],
    );
  }

  // --- agent ----------------------------------------------------------------

  List<Widget> _agent(LunaCore core) {
    return <Widget>[
      const SectionLabel('Before anything permanent'),
      Group(
        children: <Widget>[
          LunaRow(
            icon: FontAwesomeIcons.hand,
            title: 'Ask me first',
            subtitle: core.unattended
                ? 'Off — Luna acts without stopping'
                : 'On — Luna stops before it changes anything',
            trailing: LunaSwitch(
              value: !core.unattended,
              onChanged: (bool value) => core.setMode(value ? 'ask' : 'auto'),
            ),
          ),
        ],
      ),
      Note(icon: FontAwesomeIcons.handPointer, children: <InlineSpan>[
        TextSpan(
            text: core.unattended
                ? 'Luna works unsupervised. She will change files, delete them, open pages and '
                    'read whatever the job needs without stopping. Everything she does is still '
                    'written down in the job, and a deleted file is still backed up first.'
                : 'Luna stops and asks before she changes or deletes a file, before she opens or '
                    'reads a web page, before she fetches from GitHub, and before she reads '
                    'anything that looks like a key, a password or an account file. Listing and '
                    'reading ordinary files in your folder she does on her own.'),
      ]),

      const SectionLabel('Limits on one job'),
      Group(children: <Widget>[
        LunaRow(
          icon: FontAwesomeIcons.listCheck,
          title: 'Steps',
          subtitle: 'At most ${core.budgetSteps} tool calls before she stops and reports',
          trailing: _stepper(
            value: core.budgetSteps,
            onChanged: (int value) => core.setBudget(steps: value),
            min: 3,
            max: 30,
            step: 1,
          ),
        ),
        LunaRow(
          icon: FontAwesomeIcons.stopwatch,
          title: 'Time',
          subtitle: 'At most ${core.budgetSeconds}s of work in one job',
          trailing: _stepper(
            value: core.budgetSeconds,
            onChanged: (int value) => core.setBudget(seconds: value),
            min: 30,
            max: 900,
            step: 30,
          ),
        ),
        LunaRow(
          icon: FontAwesomeIcons.cloudArrowUp,
          title: 'Cloud calls',
          subtitle: 'The one that costs money, capped at ${core.budgetCloudCalls} per job',
          trailing: _stepper(
            value: core.budgetCloudCalls,
            onChanged: (int value) => core.setBudget(cloudCalls: value),
            min: 1,
            max: 20,
            step: 1,
          ),
        ),
      ]),
      const Note(icon: FontAwesomeIcons.rotate, children: <InlineSpan>[
        TextSpan(
            text: 'The same tool with the same arguments cannot run twice in one job. '
                'A repeated read is answered from what it returned the first time.'),
      ]),
      Note(icon: FontAwesomeIcons.lock, children: <InlineSpan>[
        const TextSpan(text: 'Files that hold secrets stay locked in both modes: keys, '),
        TextSpan(
            text: '.env',
            style: LunaTheme.monoStyle(size: 12, color: LunaTheme.ink, weight: 500)),
        const TextSpan(text: ', credentials and certificates. Luna cannot open them at all.'),
      ]),
      Note(icon: FontAwesomeIcons.fileShield, children: <InlineSpan>[
        TextSpan(text: 'Luna will not read a file larger than ${formatBytes(core.maxFileBytes)}.'),
      ]),

      const SectionLabel('The runtime'),
      Group(children: <Widget>[
        LunaRow(
          icon: FontAwesomeIcons.userAstronaut,
          title: 'Agents',
          subtitle: 'Who answers you, and what that one is allowed to touch',
          trailing: const _Chevron(),
          onTap: () => _open('agents'),
        ),
        LunaRow(
          icon: FontAwesomeIcons.bookOpen,
          title: 'Skills',
          subtitle: 'The knowledge Luna is given, and when',
          trailing: const _Chevron(),
          onTap: () => _open('skills'),
        ),
        LunaRow(
          icon: FontAwesomeIcons.puzzlePiece,
          title: 'Plugins',
          subtitle: 'Signed documents that add skills, agents and workflows',
          trailing: const _Chevron(),
          onTap: () => _open('plugins'),
        ),
        LunaRow(
          icon: FontAwesomeIcons.diagramProject,
          title: 'Workflows',
          subtitle: 'Jobs whose steps are known in advance',
          trailing: const _Chevron(),
          onTap: () => _open('workflows'),
        ),
      ]),
    ];
  }

  void _open(String page) => setState(() => _page = page);

  /// Two taps and a number. Smaller than a slider, and it says what it means.
  Widget _stepper({
    required int value,
    required ValueChanged<int> onChanged,
    required int min,
    required int max,
    required int step,
  }) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        IconButtonSoft(
          icon: FontAwesomeIcons.minus,
          label: 'Less',
          onTap: value <= min ? null : () => onChanged(value - step),
        ),
        SizedBox(
          width: 42,
          child: Text('$value',
              textAlign: TextAlign.center,
              style: LunaTheme.text(size: 13, weight: 600)),
        ),
        IconButtonSoft(
          icon: FontAwesomeIcons.plus,
          label: 'More',
          onTap: value >= max ? null : () => onChanged(value + step),
        ),
      ],
    );
  }

  // --- connections ----------------------------------------------------------

  List<Widget> _connections(LunaCore core) {
    return <Widget>[
      const SectionLabel('Your computer'),
      LunaField(
        label: 'Ollama address',
        controller: _endpoint,
        hint: 'http://192.168.1.20:11434',
        mono: true,
        onSubmitted: core.setEndpoint,
      ),
      const SectionLabel('When the on-device model cannot cope'),
      Group(
        children: <Widget>[
          LunaRow(
            icon: FontAwesomeIcons.cloudArrowUp,
            title: 'Fall back to a cloud key',
            subtitle: core.failover
                ? 'On — prompts may leave the device'
                : 'Off — everything stays on the device',
            trailing: LunaSwitch(value: core.failover, onChanged: core.setFailover),
          ),
        ],
      ),
      const SectionLabel('GitHub'),
      Group(
        children: <Widget>[
          LunaRow(
            icon: FontAwesomeIcons.github,
            title: core.hasToken ? 'Token saved' : 'No token',
            subtitle: 'Kept in the device keystore',
            trailing: core.hasToken
                ? PillButton(label: 'Remove', small: true, soft: true, onTap: core.clearToken)
                : PillButton(label: 'Add', small: true, onTap: () => _tokenSheet(core)),
          ),
        ],
      ),
      const SectionLabel('Downloads'),
      Group(children: <Widget>[
        LunaRow(
          icon: FontAwesomeIcons.wifi,
          title: 'Wi-Fi only',
          subtitle: core.wifiOnly
              ? 'On — a download waits rather than using your data'
              : 'Off — downloads use whatever connection is there',
          trailing: LunaSwitch(value: core.wifiOnly, onChanged: core.setWifiOnly),
        ),
        LunaRow(
          icon: FontAwesomeIcons.batteryHalf,
          title: 'Pause under 15%',
          subtitle: core.batteryGuard
              ? 'On — a download waits for charge instead of flattening the phone'
              : 'Off — downloads run at any battery level',
          trailing: LunaSwitch(value: core.batteryGuard, onChanged: core.setBatteryGuard),
        ),
      ]),
      const Note(icon: FontAwesomeIcons.download, children: <InlineSpan>[
        TextSpan(
            text: 'A download runs in a notification, so closing Luna does not stop it. '
                'It resumes on the byte it reached.'),
      ]),

      const SectionLabel('The model in memory'),
      Group(children: <Widget>[
        LunaRow(
          icon: FontAwesomeIcons.fire,
          title: 'Keep the model warm',
          subtitle: core.keepWarm
              ? 'On — the next message starts straight away, and the memory stays used'
              : 'Off — memory is handed back after every job, and each one reloads',
          trailing: LunaSwitch(value: core.keepWarm, onChanged: core.setKeepWarm),
        ),
      ]),

      const SectionLabel('Beyond this phone'),
      Group(children: <Widget>[
        LunaRow(
          icon: FontAwesomeIcons.server,
          title: 'Machines',
          subtitle: 'Where work can run, and what is wrong with each',
          trailing: const _Chevron(),
          onTap: () => _open('machines'),
        ),
        LunaRow(
          icon: FontAwesomeIcons.heartPulse,
          title: 'Provider health',
          subtitle: 'Which models have been answering and which are resting',
          trailing: const _Chevron(),
          onTap: () => _open('health'),
        ),
      ]),

      const Note(icon: FontAwesomeIcons.wifi, children: <InlineSpan>[
        TextSpan(
            text:
                'With no connections set up and a downloaded model, Luna works with the radio off.'),
      ]),
    ];
  }

  Future<void> _tokenSheet(LunaCore core) async {
    final TextEditingController token = TextEditingController();
    await showLunaSheet<void>(
      context: context,
      title: 'GitHub token',
      builder: (BuildContext sheetContext) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          LunaField(
            label: 'Token',
            controller: token,
            mono: true,
            autofocus: true,
            help: 'Stored encrypted by the device keystore. It never appears in a file.',
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 2, 20, 8),
            child: PillButton(
              label: 'Save',
              icon: FontAwesomeIcons.check,
              onTap: () {
                Navigator.of(sheetContext).pop();
                core.storeToken(token.text.trim());
              },
            ),
          ),
        ],
      ),
    );
    token.dispose();
  }

  // --- data -----------------------------------------------------------------

  List<Widget> _data(LunaCore core) {
    return <Widget>[
      const SectionLabel('Folders'),
      Group(
        children: <Widget>[
          LunaRow(
            icon: core.workspaceRevoked
                ? FontAwesomeIcons.folderMinus
                : FontAwesomeIcons.folderOpen,
            title: core.workspaceGranted
                ? (core.workspaceName.isEmpty ? 'Granted folder' : core.workspaceName)
                : 'No folder granted',
            subtitle: core.workspaceRevoked
                ? 'The permission was withdrawn — grant it again'
                : 'The only place Luna can see',
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
          for (final Map<String, dynamic> grant in core.grants)
            if ('${grant['name']}' != core.workspaceName)
              LunaRow(
                icon: FontAwesomeIcons.folder,
                title: '${grant['name']}',
                subtitle: 'Granted before — switch back without picking again',
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    PillButton(
                      label: 'Use',
                      small: true,
                      soft: true,
                      onTap: () async {
                        await core.useGrant('${grant['uri']}');
                        if (mounted) setState(() {});
                      },
                    ),
                    const SizedBox(width: 8),
                    IconButtonSoft(
                      icon: FontAwesomeIcons.xmark,
                      label: 'Forget this folder',
                      onTap: () => core.forgetGrant('${grant['uri']}'),
                    ),
                  ],
                ),
              ),
        ],
      ),

      const SectionLabel('Look and feel'),
      Group(children: <Widget>[
        LunaRow(
          icon: FontAwesomeIcons.circleHalfStroke,
          title: 'Theme',
          subtitle: core.theme == 'system'
              ? 'Follows the phone'
              : core.theme == 'dark'
                  ? 'Always dark'
                  : 'Always light',
          child: Padding(
            padding: const EdgeInsets.only(top: 8),
            child: Segmented(
              items: const <String>['System', 'Light', 'Dark'],
              index: core.theme == 'light' ? 1 : (core.theme == 'dark' ? 2 : 0),
              onChanged: (int index) =>
                  core.setTheme(<String>['system', 'light', 'dark'][index]),
            ),
          ),
        ),
        LunaRow(
          icon: FontAwesomeIcons.textHeight,
          title: 'Text size',
          subtitle: '${(core.textScale * 100).round()}% of the normal size',
          trailing: _stepper(
            value: (core.textScale * 100).round(),
            onChanged: (int value) => core.setTextScale(value / 100),
            min: 85,
            max: 150,
            step: 5,
          ),
        ),
      ]),
      const SectionLabel('Stored on this device'),
      Group(
        children: <Widget>[
          LunaRow(
            icon: FontAwesomeIcons.solidComment,
            title: 'One chat, kept until you clear it',
            subtitle: '${core.messages.length} messages',
            trailing: PillButton(label: 'Clear', small: true, soft: true, onTap: core.newChat),
          ),
          LunaRow(
            icon: FontAwesomeIcons.rotateLeft,
            title: 'Undo backups',
            subtitle: core.lastBackup == null
                ? 'Nothing to undo'
                : 'Last: ${core.lastBackup!['name'] ?? core.lastBackup!['path']}',
            trailing: core.lastBackup == null
                ? null
                : PillButton(
                    label: 'Undo',
                    small: true,
                    soft: true,
                    onTap: () async {
                      await core.undo();
                      if (mounted) setState(() {});
                    },
                  ),
          ),
          LunaRow(
            icon: FontAwesomeIcons.brain,
            title: 'What Luna remembers',
            subtitle: 'Kept between jobs, and how to make her forget it',
            trailing: const _Chevron(),
            onTap: () => _open('memory'),
          ),
          LunaRow(
            icon: FontAwesomeIcons.triangleExclamation,
            title: 'Error log',
            subtitle: core.errorCount == 0
                ? 'Nothing has failed'
                : '${core.errorCount} recorded, kept across restarts',
            onTap: () => _errorLog(core),
          ),
        ],
      ),

      const SectionLabel('Backup'),
      Group(children: <Widget>[
        LunaRow(
          icon: FontAwesomeIcons.fileArrowDown,
          title: 'Save your settings',
          subtitle: 'Written into the granted folder. Keys are not included.',
          onTap: () async {
            final String json = await core.exportSettings();
            final String name = 'luna-settings.json';
            final String saved = await core.writeFile(name, json);
            if (mounted) {
              ScaffoldMessenger.of(context)
                  .showSnackBar(SnackBar(content: Text('Saved $saved in the folder')));
            }
          },
        ),
        LunaRow(
          icon: FontAwesomeIcons.fileArrowUp,
          title: 'Restore them',
          subtitle: 'Pick a file you saved earlier',
          onTap: () async {
            final bool done = await core.restoreSettings();
            if (mounted && done) {
              setState(() {});
              ScaffoldMessenger.of(context)
                  .showSnackBar(const SnackBar(content: Text('Settings restored')));
            }
          },
        ),
      ]),
      const SectionLabel('Start over'),
      Group(
        children: <Widget>[
          LunaRow(
            icon: FontAwesomeIcons.arrowRotateLeft,
            title: 'Reset Luna',
            subtitle: 'Clears the chat, keys and settings. Downloads and your files stay.',
            trailing: PillButton(
              label: 'Reset',
              small: true,
              onTap: () => _confirmReset(core),
            ),
          ),
        ],
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 16, 20, 0),
        child: Column(
          children: <Widget>[
            const Mark(size: 28),
            const SizedBox(height: 8),
            Text('Luna', style: LunaTheme.displayStyle(size: 14, weight: 700)),
            const SizedBox(height: 2),
            Text('A local utility agent. No account, no telemetry.',
                textAlign: TextAlign.center,
                style: LunaTheme.text(size: 12, color: LunaTheme.ink3)),
          ],
        ),
      ),
    ];
  }

  /// What went wrong, in the order it went wrong.
  Future<void> _errorLog(LunaCore core) async {
    await core.loadErrors();
    if (!mounted) return;
    await showLunaSheet<void>(
      context: context,
      title: 'Error log',
      builder: (BuildContext sheetContext) => StatefulBuilder(
        builder: (BuildContext inner, StateSetter refresh) {
          if (core.errors.isEmpty) {
            return const Padding(
              padding: EdgeInsets.fromLTRB(20, 0, 20, 16),
              child: Text('Nothing has failed yet.'),
            );
          }
          return Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: <Widget>[
              PlainList(
                children: core.errors
                    .map((Map<String, dynamic> entry) => LunaRow(
                          icon: FontAwesomeIcons.triangleExclamation,
                          title: '${entry['where']}',
                          subtitle: '${entry['what']}',
                          trailing: Text(formatClock(entry['at'] as int?),
                              style: LunaTheme.text(size: 11.5, color: LunaTheme.ink3)),
                        ))
                    .toList(),
              ),
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 10),
                child: PillButton(
                  label: 'Clear the log',
                  soft: true,
                  small: true,
                  onTap: () async {
                    await core.clearErrors();
                    refresh(() {});
                    if (mounted) setState(() {});
                  },
                ),
              ),
            ],
          );
        },
      ),
    );
  }

  Future<void> _confirmReset(LunaCore core) async {
    await showLunaSheet<void>(
      context: context,
      title: 'Reset Luna?',
      builder: (BuildContext sheetContext) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 12),
            child: Text(
              'The chat, saved keys and settings go. Your files and downloaded models are untouched.',
              style: LunaTheme.text(size: 13.5, color: LunaTheme.ink2, height: 1.5),
            ),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
            child: Row(
              children: <Widget>[
                Expanded(
                  child: PillButton(
                    label: 'Keep everything',
                    soft: true,
                    onTap: () => Navigator.of(sheetContext).pop(),
                  ),
                ),
                const SizedBox(width: 8),
                Expanded(
                  child: PillButton(
                    label: 'Reset',
                    icon: FontAwesomeIcons.arrowRotateLeft,
                    onTap: () {
                      Navigator.of(sheetContext).pop();
                      core.resetAll();
                    },
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// The mark that says a row leads somewhere. Nothing else in the app drills
/// down, so it exists once, here.
class _Chevron extends StatelessWidget {
  const _Chevron();

  @override
  Widget build(BuildContext context) {
    return Glyph(FontAwesomeIcons.chevronRight, size: 11, color: LunaTheme.ink4);
  }
}
