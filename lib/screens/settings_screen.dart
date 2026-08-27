import 'package:flutter/material.dart';
import 'package:font_awesome_flutter/font_awesome_flutter.dart';

import '../core/luna_core.dart';
import '../theme.dart';
import '../widgets/common.dart';

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

  static const Map<String, String> _toolNames = <String, String>{
    'list_files': 'List files',
    'read_file': 'Read a file',
    'search_code': 'Search',
    'write_file': 'Write a file',
    'create_file': 'Create a file',
    'create_folder': 'Create a folder',
    'delete_file': 'Delete a file',
    'rename_file': 'Rename a file',
    'respond': 'Reply',
    'ask_user': 'Ask you',
  };

  @override
  Widget build(BuildContext context) {
    final LunaCore core = widget.core;
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
      const SectionLabel('Luna does these on her own'),
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 0),
        child: Wrap(
          children: core.readOnlyTools
              .map((String tool) => LunaChip(_toolNames[tool] ?? tool))
              .toList(),
        ),
      ),
      const SectionLabel('These stop and wait for you'),
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 0),
        child: Wrap(
          children: core.mutatingTools
              .map((String tool) => LunaChip(_toolNames[tool] ?? tool, held: true))
              .toList(),
        ),
      ),
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
    ];
  }

  // --- connections ----------------------------------------------------------

  List<Widget> _connections(LunaCore core) {
    final TextEditingController endpoint = TextEditingController(text: core.endpoint);
    return <Widget>[
      const SectionLabel('Your computer'),
      LunaField(
        label: 'Ollama address',
        controller: endpoint,
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
      Note(icon: FontAwesomeIcons.wifi, children: const <InlineSpan>[
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
  }

  // --- data -----------------------------------------------------------------

  List<Widget> _data(LunaCore core) {
    return <Widget>[
      const SectionLabel('Folder'),
      Group(
        children: <Widget>[
          LunaRow(
            icon: FontAwesomeIcons.folderOpen,
            title: core.workspaceGranted
                ? (core.workspaceName.isEmpty ? 'Granted folder' : core.workspaceName)
                : 'No folder granted',
            subtitle: 'The only place Luna can see',
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
        ],
      ),
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
            icon: FontAwesomeIcons.triangleExclamation,
            title: 'Last error',
            subtitle: core.lastError ?? 'None',
          ),
        ],
      ),
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
