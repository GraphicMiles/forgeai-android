import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show AssetManifest, rootBundle;
import 'package:font_awesome_flutter/font_awesome_flutter.dart';

import '../core/luna_core.dart';
import '../theme.dart';
import '../widgets/common.dart';

/// The runtime, as screens.
///
/// Everything Luna loads rather than contains — plugins, the agents and skills
/// they bring, workflows, what she remembers, and where work can run — lives
/// behind Settings as a drill-down. It is not a fifth tab: these are things you
/// set up once and then forget, which is exactly what Settings is for.
///
/// Each page reads its own data when it opens. None of it changes between one
/// message and the next, so none of it belongs in the snapshot the chat screen
/// rebuilds on.
class PlatformPage extends StatefulWidget {
  const PlatformPage({
    super.key,
    required this.core,
    required this.page,
    required this.onBack,
  });

  final LunaCore core;

  /// One of: plugins, agents, skills, workflows, memory, machines, health.
  final String page;

  final VoidCallback onBack;

  static String titleOf(String page) {
    switch (page) {
      case 'plugins':
        return 'Plugins';
      case 'agents':
        return 'Agents';
      case 'skills':
        return 'Skills';
      case 'workflows':
        return 'Workflows';
      case 'memory':
        return 'What Luna remembers';
      case 'machines':
        return 'Machines';
      case 'health':
        return 'Provider health';
    }
    return 'Runtime';
  }

  @override
  State<PlatformPage> createState() => _PlatformPageState();
}

class _PlatformPageState extends State<PlatformPage> {
  List<Map<String, dynamic>> _rows = <Map<String, dynamic>>[];
  String _activeAgent = '';
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final LunaCore core = widget.core;
    List<Map<String, dynamic>> rows;
    switch (widget.page) {
      case 'plugins':
        rows = await core.plugins();
        break;
      case 'agents':
        rows = await core.agents();
        _activeAgent = await core.activeAgent();
        break;
      case 'skills':
        rows = await core.skills();
        break;
      case 'workflows':
        rows = await core.workflows();
        break;
      case 'memory':
        rows = await core.memory();
        break;
      case 'machines':
        rows = await core.environments();
        break;
      case 'health':
        rows = await core.inferenceHealth();
        break;
      default:
        rows = <Map<String, dynamic>>[];
    }
    if (!mounted) return;
    setState(() {
      _rows = rows;
      _loading = false;
    });
  }

  /// One place says what happened, and it says it once.
  void _say(String text) {
    if (!mounted || text.isEmpty) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(text)));
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: <Widget>[
        ScreenTop(
          title: PlatformPage.titleOf(widget.page),
          small: true,
          leading: IconButtonSoft(
            icon: FontAwesomeIcons.arrowLeft,
            label: 'Back to settings',
            onTap: widget.onBack,
          ),
        ),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.only(bottom: 16),
            children: _loading
                ? <Widget>[
                    const SizedBox(height: 40),
                    Center(
                      child: Text('Reading…',
                          style: LunaTheme.text(size: 13, color: LunaTheme.ink3)),
                    ),
                  ]
                : _body(),
          ),
        ),
      ],
    );
  }

  List<Widget> _body() {
    switch (widget.page) {
      case 'plugins':
        return _plugins();
      case 'agents':
        return _agents();
      case 'skills':
        return _skills();
      case 'workflows':
        return _workflows();
      case 'memory':
        return _memory();
      case 'machines':
        return _machines();
      case 'health':
        return _health();
    }
    return <Widget>[];
  }

  // --- plugins ----------------------------------------------------------------

  List<Widget> _plugins() {
    return <Widget>[
      if (_rows.isEmpty)
        const EmptyState(
          icon: FontAwesomeIcons.puzzlePiece,
          title: 'Nothing installed',
          body: 'A plugin is a signed document: skills, agents and workflows, and no code. '
              'Install the ones Luna ships with to see what one looks like.',
        )
      else ...<Widget>[
        const SectionLabel('Installed'),
        Group(
          children: <Widget>[
            for (final Map<String, dynamic> row in _rows)
              LunaRow(
                icon: row['signed'] == true
                    ? FontAwesomeIcons.puzzlePiece
                    : FontAwesomeIcons.triangleExclamation,
                title: '${row['name']}',
                subtitle: _pluginLine(row),
                trailing: PillButton(
                  label: 'Remove',
                  small: true,
                  soft: true,
                  onTap: () async {
                    await widget.core.removePlugin('${row['id']}');
                    await _load();
                    _say('${row['name']} removed');
                  },
                ),
                child: _capabilityLine(row),
              ),
          ],
        ),
      ],
      const SectionLabel('Add one'),
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 4),
        child: Wrap(
          spacing: 8,
          runSpacing: 8,
          children: <Widget>[
            PillButton(
              label: 'Install the examples',
              icon: FontAwesomeIcons.download,
              small: true,
              soft: _rows.isNotEmpty,
              onTap: _installExamples,
            ),
            PillButton(
              label: 'From your folder',
              icon: FontAwesomeIcons.folderOpen,
              small: true,
              soft: true,
              onTap: _installFromFolder,
            ),
          ],
        ),
      ),
      const Note(icon: FontAwesomeIcons.lock, children: <InlineSpan>[
        TextSpan(
            text: 'A plugin cannot run code inside Luna. It carries knowledge, agents and '
                'workflows, and each one is checked against its own signature before it '
                'loads — every time the app starts, not just when you install it.'),
      ]),
    ];
  }

  String _pluginLine(Map<String, dynamic> row) {
    final List<String> parts = <String>['${row['version']}'];
    if ('${row['author']}'.isNotEmpty) parts.add('${row['author']}');
    final int skills = (row['skills'] as int?) ?? 0;
    final int agents = (row['agents'] as int?) ?? 0;
    final int workflows = (row['workflows'] as int?) ?? 0;
    if (skills > 0) parts.add(skills == 1 ? '1 skill' : '$skills skills');
    if (agents > 0) parts.add(agents == 1 ? '1 agent' : '$agents agents');
    if (workflows > 0) {
      parts.add(workflows == 1 ? '1 workflow' : '$workflows workflows');
    }
    parts.add(row['signed'] == true ? 'signed' : 'unsigned');
    return parts.join(' · ');
  }

  Widget? _capabilityLine(Map<String, dynamic> row) {
    final List<Object?> capabilities = (row['capabilities'] as List<Object?>?) ?? <Object?>[];
    if (capabilities.isEmpty) return null;
    return Padding(
      padding: const EdgeInsets.only(top: 6),
      child: Text(
        'May ask to: ${capabilities.join(', ')}',
        style: LunaTheme.monoStyle(size: 11.5, color: LunaTheme.ink3),
      ),
    );
  }

  /// The three that ship in the app, signed at build time.
  Future<void> _installExamples() async {
    final AssetManifest manifest = await AssetManifest.loadFromAssetBundle(rootBundle);
    final List<String> packages = manifest
        .listAssets()
        .where((String path) => path.endsWith('.lunapkg.json'))
        .toList()
      ..sort();
    int installed = 0;
    final List<String> refused = <String>[];
    for (final String path in packages) {
      final String source = await rootBundle.loadString(path);
      final String refusal = await widget.core.installPlugin(source);
      if (refusal.isEmpty) {
        installed++;
      } else if (!refusal.contains('already installed')) {
        refused.add(refusal);
      }
    }
    await _load();
    if (installed == 0 && refused.isEmpty) {
      _say('The examples are already installed.');
    } else if (refused.isEmpty) {
      _say(installed == 1 ? 'One plugin installed' : '$installed plugins installed');
    } else {
      _say(refused.first);
    }
  }

  /// Anything named *.lunapkg.json in the folder you granted.
  Future<void> _installFromFolder() async {
    if (!widget.core.workspaceGranted) {
      _say('Grant a folder first, in Settings under Data.');
      return;
    }
    final List<Map<String, dynamic>> entries = await widget.core.listFolder('');
    final List<Map<String, dynamic>> packages = entries
        .where((Map<String, dynamic> entry) =>
            '${entry['type']}' == 'file' && '${entry['name']}'.endsWith('.lunapkg.json'))
        .toList();
    if (!mounted) return;
    if (packages.isEmpty) {
      _say('No .lunapkg.json file at the top of that folder.');
      return;
    }
    await showLunaSheet<void>(
      context: context,
      title: 'Install from your folder',
      builder: (BuildContext sheetContext) => PlainList(
        children: <Widget>[
          for (final Map<String, dynamic> entry in packages)
            LunaRow(
              icon: FontAwesomeIcons.fileCode,
              title: '${entry['name']}',
              subtitle: formatBytes((entry['size'] as num?)?.toInt() ?? 0),
              onTap: () async {
                Navigator.of(sheetContext).pop();
                final String source = await widget.core.readFile('${entry['path']}');
                final String refusal = await widget.core.installPlugin(source);
                await _load();
                _say(refusal.isEmpty ? '${entry['name']} installed' : refusal);
              },
            ),
        ],
      ),
    );
  }

  // --- agents -----------------------------------------------------------------

  List<Widget> _agents() {
    return <Widget>[
      const SectionLabel('Who answers you'),
      Group(
        children: <Widget>[
          for (final Map<String, dynamic> row in _rows)
            LunaRow(
              icon: '${row['id']}' == _activeAgent
                  ? FontAwesomeIcons.solidCircleCheck
                  : FontAwesomeIcons.userAstronaut,
              title: '${row['name']}',
              subtitle: _agentLine(row),
              subtitleLines: 2,
              trailing: '${row['id']}' == _activeAgent
                  ? Text('Active', style: LunaTheme.text(size: 12, color: LunaTheme.ink3))
                  : PillButton(
                      label: 'Use',
                      small: true,
                      soft: true,
                      onTap: () async {
                        final bool switched =
                            await widget.core.activateAgent('${row['id']}');
                        await _load();
                        _say(switched
                            ? '${row['name']} answers from now on'
                            : 'Not while a job is running.');
                      },
                    ),
            ),
        ],
      ),
      const Note(icon: FontAwesomeIcons.userGear, children: <InlineSpan>[
        TextSpan(
            text: 'An agent is a name, some instructions, the skills it knows and the tools it '
                'is allowed to touch. It can only ever have fewer tools than Luna, never more, '
                'and switching does not change your limits or the "Ask me first" switch.'),
      ]),
    ];
  }

  String _agentLine(Map<String, dynamic> row) {
    final List<Object?> tools = (row['tools'] as List<Object?>?) ?? <Object?>[];
    final List<Object?> skills = (row['skills'] as List<Object?>?) ?? <Object?>[];
    final String reach = tools.isEmpty || tools.contains('*')
        ? 'every tool'
        : (tools.length == 1 ? '1 tool' : '${tools.length} tools');
    final String knows = skills.isEmpty
        ? 'no extra knowledge'
        : (skills.length == 1 ? '1 skill' : '${skills.length} skills');
    final String origin = row['builtIn'] == true ? 'ships with Luna' : '${row['author']}';
    final String description = '${row['description']}';
    return description.isEmpty
        ? '$reach · $knows · $origin'
        : '$description\n$reach · $knows · $origin';
  }

  // --- skills -----------------------------------------------------------------

  List<Widget> _skills() {
    return <Widget>[
      const SectionLabel('What Luna knows how to do'),
      Group(
        children: <Widget>[
          for (final Map<String, dynamic> row in _rows)
            LunaRow(
              icon: FontAwesomeIcons.bookOpen,
              title: '${row['name']}',
              subtitle: '${row['description']}'.isEmpty
                  ? 'From ${row['provider']}'
                  : '${row['description']}\nFrom ${row['provider']}',
              subtitleLines: 2,
              trailing: LunaSwitch(
                value: row['enabled'] != false,
                onChanged: (bool value) async {
                  final List<String> off = <String>[
                    for (final Map<String, dynamic> skill in _rows)
                      if (skill['enabled'] == false && '${skill['id']}' != '${row['id']}')
                        '${skill['id']}',
                    if (!value) '${row['id']}',
                  ];
                  final List<Map<String, dynamic>> updated =
                      await widget.core.setSkillsDisabled(off);
                  if (mounted) setState(() => _rows = updated);
                },
              ),
            ),
        ],
      ),
      const Note(icon: FontAwesomeIcons.bookOpen, children: <InlineSpan>[
        TextSpan(
            text: 'A skill is a paragraph of instruction that is only included when it is '
                'relevant — when the job mentions it, or when the tools it needs are the ones '
                'in play. Turning one off removes it from what Luna is told, nothing else.'),
      ]),
    ];
  }

  // --- workflows --------------------------------------------------------------

  List<Widget> _workflows() {
    return <Widget>[
      if (_rows.isEmpty)
        const EmptyState(
          icon: FontAwesomeIcons.diagramProject,
          title: 'No workflows',
          body: 'A workflow is a job whose steps are known in advance. They arrive in plugins — '
              'install the examples under Plugins to get two of them.',
        )
      else ...<Widget>[
        const SectionLabel('Jobs that know their own steps'),
        Group(
          children: <Widget>[
            for (final Map<String, dynamic> row in _rows)
              LunaRow(
                icon: FontAwesomeIcons.diagramProject,
                title: '${row['name']}',
                subtitle: '${row['description']}\n'
                    '${row['steps']} steps · at most ${row['maxSteps']} taken · ${row['version']}',
                subtitleLines: 2,
                trailing: PillButton(
                  label: 'Run',
                  small: true,
                  onTap: () async {
                    final bool started = await widget.core.runWorkflow('${row['id']}');
                    _say(started
                        ? 'Running — watch it in the chat'
                        : 'Not while another job is running.');
                  },
                ),
              ),
          ],
        ),
      ],
      const Note(icon: FontAwesomeIcons.listCheck, children: <InlineSpan>[
        TextSpan(
            text: 'A workflow runs through the same approvals, the same limits and the same '
                'trace as a chat. It appears in the chat as it goes, and stops on the same '
                'button.'),
      ]),
    ];
  }

  // --- memory -----------------------------------------------------------------

  List<Widget> _memory() {
    return <Widget>[
      const SectionLabel('Kept for the active agent'),
      Group(
        children: <Widget>[
          for (final Map<String, dynamic> row in _rows)
            LunaRow(
              icon: _memoryIcon('${row['kind']}'),
              title: _memoryTitle('${row['kind']}'),
              subtitle: '${row['description']}\n'
                  '${row['count']} kept · ${row['held'] == true ? row['provider'] : 'not stored'}',
              subtitleLines: 2,
              trailing: ((row['count'] as int?) ?? 0) == 0
                  ? null
                  : PillButton(
                      label: 'Forget',
                      small: true,
                      soft: true,
                      onTap: () async {
                        final int gone = await widget.core.forgetMemory('${row['kind']}');
                        await _load();
                        _say(gone == 1 ? 'One thing forgotten' : '$gone things forgotten');
                      },
                    ),
            ),
        ],
      ),
      const Note(icon: FontAwesomeIcons.brain, children: <InlineSpan>[
        TextSpan(
            text: 'Working memory is thrown away at the end of every job. The rest sits in a '
                'file on this device, never leaves it unless a cloud model is answering, and '
                'only the few lines that match what you asked are ever put in front of a '
                'model.'),
      ]),
    ];
  }

  FaIconData _memoryIcon(String kind) {
    switch (kind) {
      case 'conversation':
        return FontAwesomeIcons.solidComment;
      case 'working':
        return FontAwesomeIcons.hourglassHalf;
      case 'long_term':
        return FontAwesomeIcons.brain;
      case 'knowledge':
        return FontAwesomeIcons.bookOpen;
    }
    return FontAwesomeIcons.clockRotateLeft;
  }

  String _memoryTitle(String kind) {
    switch (kind) {
      case 'conversation':
        return 'This conversation';
      case 'working':
        return 'While a job runs';
      case 'long_term':
        return 'About you';
      case 'knowledge':
        return 'Things learned';
      case 'execution':
        return 'What happened before';
    }
    return kind;
  }

  // --- machines ---------------------------------------------------------------

  List<Widget> _machines() {
    return <Widget>[
      const SectionLabel('Where work can run'),
      Group(
        children: <Widget>[
          for (final Map<String, dynamic> row in _rows)
            LunaRow(
              icon: row['local'] == true
                  ? FontAwesomeIcons.mobileScreen
                  : FontAwesomeIcons.server,
              title: '${row['name']}',
              subtitle: _machineLine(row),
              trailing: row['active'] == true
                  ? Text('In use', style: LunaTheme.text(size: 12, color: LunaTheme.ink3))
                  : null,
            ),
        ],
      ),
      const Note(icon: FontAwesomeIcons.server, children: <InlineSpan>[
        TextSpan(
            text: 'This phone is the only machine that can actually run anything today. The '
                'others are declared so Luna can say "that needs the laptop" instead of '
                'pretending a job failed — nothing is sent anywhere until there is a '
                'transport for it.'),
      ]),
    ];
  }

  String _machineLine(Map<String, dynamic> row) {
    final String problem = '${row['problem']}';
    final String where = row['local'] == true ? 'this device' : '${row['platform']}';
    if (problem.isNotEmpty) return '$where · $problem';
    return row['available'] == true ? '$where · reachable' : '$where · not reachable';
  }

  // --- provider health --------------------------------------------------------

  List<Widget> _health() {
    return <Widget>[
      if (_rows.isEmpty)
        const EmptyState(
          icon: FontAwesomeIcons.heartPulse,
          title: 'Nothing has answered yet',
          body: 'Once a model has been asked something, how it behaved shows up here.',
        )
      else ...<Widget>[
        const SectionLabel('How each model has behaved'),
        Group(
          children: <Widget>[
            for (final Map<String, dynamic> row in _rows)
              LunaRow(
                icon: row['resting'] == true
                    ? FontAwesomeIcons.bed
                    : FontAwesomeIcons.heartPulse,
                title: '${row['id']}',
                subtitle: '${row['worked']} answered · ${row['failed']} failed'
                    '${row['resting'] == true ? ' · resting after a failure' : ''}',
              ),
          ],
        ),
      ],
      const Note(icon: FontAwesomeIcons.arrowRightArrowLeft, children: <InlineSpan>[
        TextSpan(
            text: 'A model that fails is rested for two minutes and the next request goes '
                'somewhere else. Luna says which one answered, and why, in the job trace.'),
      ]),
    ];
  }
}
