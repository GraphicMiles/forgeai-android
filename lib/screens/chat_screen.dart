import 'dart:async';

import 'package:flutter/material.dart';
import 'package:font_awesome_flutter/font_awesome_flutter.dart';

import '../core/luna_core.dart';
import '../theme.dart';
import '../widgets/agent_response.dart';
import '../widgets/common.dart';

/// Chat — the working screen.
///
/// One 16.5px title (the job), and exactly one filled-black surface: the
/// approval card. Everything else is grey on white.
class ChatScreen extends StatefulWidget {
  const ChatScreen({super.key, required this.core, required this.onOpenModels});

  final LunaCore core;
  final VoidCallback onOpenModels;

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final TextEditingController _input = TextEditingController();
  final ScrollController _scroll = ScrollController();
  Timer? _clock;

  static const Map<String, String> _stepLabels = <String, String>{
    'list_files': 'Listed the folder',
    'read_file': 'Read a file',
    'search_code': 'Searched the folder',
    'write_file': 'Wrote a file',
    'create_file': 'Created a file',
    'create_folder': 'Created a folder',
    'delete_file': 'Deleted a file',
    'rename_file': 'Renamed a file',
    'open_page': 'Opened a page',
    'read_page': 'Read the page',
    'github_file': 'Fetched from GitHub',
    'ask_user': 'Asked you',
    'load_model': 'Loaded the model',
  };

  /// What the same tool is called while it is still happening.
  static const Map<String, String> _liveLabels = <String, String>{
    'list_files': 'Listing the folder',
    'read_file': 'Reading a file',
    'search_code': 'Searching the folder',
    'write_file': 'Writing a file',
    'create_file': 'Creating a file',
    'create_folder': 'Creating a folder',
    'delete_file': 'Deleting a file',
    'rename_file': 'Renaming a file',
    'open_page': 'Opening a page',
    'read_page': 'Reading the page',
    'github_file': 'Fetching from GitHub',
    'ask_user': 'Waiting on you',
    'load_model': 'Loading the model',
  };

  final TextEditingController _answer = TextEditingController();
  String _attached = '';

  @override
  void initState() {
    super.initState();
    // Fast enough for the tenth of a second the trace shows, slow enough not
    // to be a second animation of its own.
    _clock = Timer.periodic(const Duration(milliseconds: 200), (Timer _) {
      if (mounted && widget.core.running) setState(() {});
    });
  }

  @override
  void dispose() {
    _clock?.cancel();
    _answer.dispose();
    _input.dispose();
    _scroll.dispose();
    super.dispose();
  }

  void _pinToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) {
        _scroll.jumpTo(_scroll.position.maxScrollExtent);
      }
    });
  }

  Future<void> _send() async {
    String text = _input.text.trim();
    if (text.isEmpty || widget.core.running) return;
    if (_attached.isNotEmpty) {
      // An attachment is a path, not an upload: it goes in as a plain instruction.
      text = '$text\n\n(The file $_attached is in the folder. Read it first.)';
      setState(() => _attached = '');
    }
    _input.clear();
    await widget.core.send(text);
    _pinToBottom();
  }

  /// The paperclip does real work: pick a file in the folder, or bring one in.
  Future<void> _attach() async {
    final LunaCore core = widget.core;
    if (!core.workspaceGranted) {
      await core.pickFolder();
      return;
    }
    final List<Map<String, dynamic>> entries = await core.listFolder('');
    if (!mounted) return;
    await showLunaSheet<void>(
      context: context,
      title: 'Attach',
      builder: (BuildContext sheetContext) {
        return Column(
          children: <Widget>[
            Group(children: <Widget>[
              for (final Map<String, dynamic> entry in entries)
                if (entry['type'] != 'folder' && entry['locked'] != true)
                  LunaRow(
                    icon: FontAwesomeIcons.fileLines,
                    title: '${entry['name']}',
                    subtitle: formatBytes(entry['size'] as num?),
                    onTap: () {
                      setState(() => _attached = '${entry['name']}');
                      Navigator.of(sheetContext).pop();
                    },
                  ),
              LunaRow(
                icon: FontAwesomeIcons.mobileScreen,
                title: 'A file from the phone',
                subtitle: 'Copied into the folder first, so she can read it',
                onTap: () async {
                  Navigator.of(sheetContext).pop();
                  final String? name = await core.bringInFile();
                  if (name != null && name.isNotEmpty && mounted) {
                    setState(() => _attached = name);
                  }
                },
              ),
            ]),
            const Note(icon: FontAwesomeIcons.circleInfo, children: <InlineSpan>[
              TextSpan(
                  text: 'An attachment is a path, not an upload. Luna opens it with read_file '
                      'when she needs it.'),
            ]),
          ],
        );
      },
    );
  }

  Future<void> _openChats() async {
    final LunaCore core = widget.core;
    await core.loadChats();
    if (!mounted) return;
    final TextEditingController search = TextEditingController();
    List<Map<String, dynamic>> shown = core.chats;
    await showLunaSheet<void>(
      context: context,
      title: 'Chats',
      builder: (BuildContext sheetContext) {
        return StatefulBuilder(
          builder: (BuildContext inner, StateSetter refresh) {
            return Column(
              children: <Widget>[
                LunaField(
                  label: 'Search',
                  controller: search,
                  hint: 'A word from an old job',
                  onChanged: (String value) async {
                    final List<Map<String, dynamic>> found = await core.searchChats(value);
                    refresh(() => shown = found);
                  },
                ),
                Group(children: <Widget>[
                  for (final Map<String, dynamic> chat in shown)
                    LunaRow(
                      icon: chat['active'] == true
                          ? FontAwesomeIcons.solidComment
                          : FontAwesomeIcons.comment,
                      title: '${chat['title']}',
                      subtitle: formatClock(chat['at'] as int?),
                      trailing: GestureDetector(
                        behavior: HitTestBehavior.opaque,
                        onTap: () async {
                          await core.deleteChat('${chat['id']}');
                          refresh(() => shown = core.chats);
                        },
                        child: const Glyph(FontAwesomeIcons.trash, size: 12),
                      ),
                      onTap: () async {
                        await core.switchChat('${chat['id']}');
                        if (inner.mounted) Navigator.of(sheetContext).pop();
                      },
                    ),
                ]),
                Padding(
                  padding: const EdgeInsets.fromLTRB(20, 4, 20, 8),
                  child: Row(children: <Widget>[
                    PillButton(
                      label: 'New chat',
                      icon: FontAwesomeIcons.penToSquare,
                      small: true,
                      onTap: () async {
                        await core.startNewChat();
                        if (inner.mounted) Navigator.of(sheetContext).pop();
                      },
                    ),
                    const SizedBox(width: 8),
                    PillButton(
                      label: 'Export this one',
                      icon: FontAwesomeIcons.fileArrowDown,
                      small: true,
                      soft: true,
                      onTap: () async {
                        final String markdown = await core.exportChat();
                        final String name = 'luna-job-${DateTime.now().millisecondsSinceEpoch}.md';
                        await core.writeFile(name, markdown);
                        if (inner.mounted) Navigator.of(sheetContext).pop();
                        if (mounted) {
                          ScaffoldMessenger.of(context).showSnackBar(
                            SnackBar(content: Text('Saved $name in the folder')),
                          );
                        }
                      },
                    ),
                  ]),
                ),
              ],
            );
          },
        );
      },
    );
  }

  @override
  Widget build(BuildContext context) {
    final LunaCore core = widget.core;
    _pinToBottom();

    return Column(
      children: <Widget>[
        ScreenTop(
          title: core.messages.isEmpty ? 'New chat' : 'This job',
          small: true,
          leading: const Mark(size: 32),
          actions: <Widget>[
            IconButtonSoft(
              icon: FontAwesomeIcons.clockRotateLeft,
              label: 'Chats',
              onTap: core.running ? null : _openChats,
            ),
            IconButtonSoft(
              icon: FontAwesomeIcons.penToSquare,
              label: 'New chat',
              onTap: core.running ? null : () => core.startNewChat(),
            ),
          ],
        ),
        Padding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 10),
          child: Row(
            children: <Widget>[
              Flexible(
                child: GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: widget.onOpenModels,
                  child: _contextPill(core),
                ),
              ),
              if (core.unattended) ...<Widget>[
                const SizedBox(width: 7),
                _pill(FontAwesomeIcons.bolt, 'Unattended'),
              ],
            ],
          ),
        ),
        Expanded(
          child: ListView(
            controller: _scroll,
            padding: const EdgeInsets.only(bottom: 8),
            children: <Widget>[
              if (core.messages.isEmpty && !core.running) _empty(core),
              ..._thread(core),
              if (core.steps.isNotEmpty) _steps(core),
              if (core.pendingApproval != null) _approval(core),
              if (core.pendingQuestion != null) _question(core),
              if (core.running) _running(core),
            ],
          ),
        ),
        _composer(core),
      ],
    );
  }

  Widget _contextPill(LunaCore core) {
    final String model = core.activeModelName.isEmpty ? 'Pick a model' : core.activeModelName;
    final String folder = core.workspaceGranted
        ? (core.workspaceName.isEmpty ? 'Granted folder' : core.workspaceName)
        : 'No folder';
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 6),
      decoration: BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rPill),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Glyph(FontAwesomeIcons.microchip, size: 10.5, color: LunaTheme.ink4),
          const SizedBox(width: 7),
          Flexible(
            child: Text(model,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: LunaTheme.text(size: 12, weight: 500, color: LunaTheme.ink2)),
          ),
          const SizedBox(width: 7),
          Container(
            width: 3,
            height: 3,
            decoration: BoxDecoration(color: LunaTheme.ink4, shape: BoxShape.circle),
          ),
          const SizedBox(width: 7),
          Glyph(FontAwesomeIcons.folderOpen, size: 10.5, color: LunaTheme.ink4),
          const SizedBox(width: 7),
          Flexible(
            child: Text(folder,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: LunaTheme.text(size: 12, weight: 500, color: LunaTheme.ink2)),
          ),
        ],
      ),
    );
  }

  Widget _pill(FaIconData icon, String label) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 6),
      decoration: BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rPill),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Glyph(icon, size: 10.5, color: LunaTheme.ink4),
          const SizedBox(width: 7),
          Text(label, style: LunaTheme.text(size: 12, weight: 500, color: LunaTheme.ink2)),
        ],
      ),
    );
  }

  Widget _empty(LunaCore core) {
    final bool hasModel = core.activeModelName.isNotEmpty;
    return EmptyState(
      mascot: true,
      icon: FontAwesomeIcons.solidComment,
      title: hasModel ? 'Give Luna a job' : 'Pick a model first',
      body: hasModel
          ? 'Grant a folder, then describe the outcome. She reads before she writes, and stops before anything permanent.'
          : 'Models live on the Models tab. Download one for offline work, or connect your computer or a cloud key.',
      action: hasModel
          ? null
          : PillButton(
              label: 'Open Models',
              icon: FontAwesomeIcons.cube,
              small: true,
              onTap: widget.onOpenModels,
            ),
    );
  }

  List<Widget> _thread(LunaCore core) {
    final List<Widget> out = <Widget>[];
    for (final Map<String, dynamic> message in core.messages) {
      final String role = (message['role'] as String?) ?? '';
      final String content = (message['content'] as String?) ?? '';
      if (role == 'observation' || content.isEmpty) continue;
      out.add(Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 11),
        child: role == 'user' ? _userBubble(content) : _lunaBubble(content),
      ));
    }
    if (core.streaming.isNotEmpty) {
      out.add(Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 11),
        child: _lunaBubble(core.streaming, streaming: true),
      ));
    }
    return out;
  }

  Widget _userBubble(String text) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.end,
      children: <Widget>[
        Flexible(
          child: Container(
            constraints: const BoxConstraints(maxWidth: 280),
            padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
            decoration: BoxDecoration(
              color: LunaTheme.ink,
              borderRadius: BorderRadius.only(
                topLeft: Radius.circular(20),
                topRight: Radius.circular(20),
                bottomLeft: Radius.circular(20),
                bottomRight: Radius.circular(7),
              ),
            ),
            child: Text(text, style: LunaTheme.bubble),
          ),
        ),
      ],
    );
  }

  Widget _lunaBubble(String text, {bool streaming = false}) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        const Padding(padding: EdgeInsets.only(top: 2), child: Mark(size: 22)),
        const SizedBox(width: 10),
        Expanded(
          child: streaming
              ? StreamedAnswer(text: text)
              : Text(text, style: LunaTheme.body),
        ),
      ],
    );
  }

  Widget _steps(LunaCore core) {
    final List<TraceStep> steps = core.steps.map((Map<String, String> step) {
      final String tool = step['tool'] ?? '';
      final String state = step['state'] ?? '';
      final String path = step['path'] ?? '';
      final String detail = state == 'replayed'
          ? 'already read'
          : state == 'blocked'
              ? 'over the limit'
              : state == 'unfinished'
                  ? 'did not finish'
                  : state == 'denied'
                      ? (tool == 'load_model' ? 'would not load' : 'not allowed')
                      : path.isEmpty
                      ? ''
                      : path.split('/').last;
      final String label = state == 'running' || state == 'unfinished' ||
              (state == 'denied' && tool == 'load_model')
          ? (_liveLabels[tool] ?? tool)
          : (_stepLabels[tool] ?? tool);
      return TraceStep(label: label, state: state, detail: detail);
    }).toList();

    return AgentTrace(
      steps: steps,
      running: core.running,
      elapsed: core.running ? core.workElapsed : core.runElapsed,
    );
  }

  /// The one filled-black surface on this screen.
  Widget _approval(LunaCore core) {
    final Map<String, dynamic> approval = core.pendingApproval!;
    final String preview = (approval['preview'] as String?) ?? '';
    return Container(
      margin: const EdgeInsets.fromLTRB(20, 12, 20, 0),
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 13),
      decoration: BoxDecoration(color: LunaTheme.ink, borderRadius: LunaTheme.rCard),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              Glyph(FontAwesomeIcons.hand, size: 11, color: LunaTheme.onInkDim),
              const SizedBox(width: 7),
              Text('Needs your approval',
                  style: LunaTheme.text(size: 11.5, weight: 600, color: LunaTheme.onInkDim)),
            ],
          ),
          const SizedBox(height: 5),
          Text((approval['headline'] as String?) ?? 'Allow this?', style: LunaTheme.decision),
          const SizedBox(height: 4),
          Text((approval['consequence'] as String?) ?? '',
              style: LunaTheme.text(size: 12.5, color: LunaTheme.onInkFaint, height: 1.4)),
          if (preview.isNotEmpty)
            Container(
              margin: const EdgeInsets.only(top: 10),
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
              constraints: const BoxConstraints(maxHeight: 132),
              decoration: const BoxDecoration(
                  color: Color(0xFF17171A), borderRadius: LunaTheme.rField),
              child: SingleChildScrollView(
                child: Text(preview,
                    style: LunaTheme.monoStyle(size: 11.5, color: Color(0xFFD9D9DE))),
              ),
            ),
          const SizedBox(height: 13),
          Row(
            children: <Widget>[
              Expanded(
                child: _approvalButton(
                  label: 'Skip',
                  icon: FontAwesomeIcons.xmark,
                  background: LunaTheme.inkButton,
                  foreground: const Color(0xFFD9D9DE),
                  onTap: () => core.approve('${approval['id']}', false),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _approvalButton(
                  label: 'Allow',
                  icon: FontAwesomeIcons.check,
                  background: LunaTheme.onInk,
                  foreground: LunaTheme.ink,
                  onTap: () => core.approve('${approval['id']}', true),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  /// Luna asking you something. The job is stopped until this is answered.
  Widget _question(LunaCore core) {
    final Map<String, dynamic> question = core.pendingQuestion!;
    return Container(
      margin: const EdgeInsets.fromLTRB(20, 12, 20, 0),
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 13),
      decoration: BoxDecoration(color: LunaTheme.ink, borderRadius: LunaTheme.rCard),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              Glyph(FontAwesomeIcons.circleQuestion, size: 11, color: LunaTheme.onInkDim),
              const SizedBox(width: 7),
              Text('Luna needs to know',
                  style: LunaTheme.text(size: 11.5, weight: 600, color: LunaTheme.onInkDim)),
            ],
          ),
          const SizedBox(height: 5),
          Text('${question['question']}', style: LunaTheme.decision),
          const SizedBox(height: 11),
          Container(
            padding: const EdgeInsets.symmetric(horizontal: 13, vertical: 4),
            decoration: BoxDecoration(
                color: LunaTheme.inkButton, borderRadius: LunaTheme.rPill),
            child: TextField(
              controller: _answer,
              cursorColor: LunaTheme.onInk,
              style: LunaTheme.text(size: 13.5, color: LunaTheme.onInk),
              textInputAction: TextInputAction.send,
              onSubmitted: (String value) {
                core.answerQuestion('${question['id']}', value);
                _answer.clear();
              },
              decoration: InputDecoration(
                isDense: true,
                border: InputBorder.none,
                hintText: 'Your answer',
                hintStyle: LunaTheme.text(size: 13.5, color: LunaTheme.onInkFaint),
                contentPadding: const EdgeInsets.symmetric(vertical: 9),
              ),
            ),
          ),
          const SizedBox(height: 11),
          Row(
            children: <Widget>[
              Expanded(
                child: _approvalButton(
                  label: 'Skip',
                  icon: FontAwesomeIcons.xmark,
                  background: LunaTheme.inkButton,
                  foreground: const Color(0xFFD9D9DE),
                  onTap: () {
                    core.answerQuestion('${question['id']}', '');
                    _answer.clear();
                  },
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: _approvalButton(
                  label: 'Answer',
                  icon: FontAwesomeIcons.check,
                  background: LunaTheme.onInk,
                  foreground: LunaTheme.ink,
                  onTap: () {
                    core.answerQuestion('${question['id']}', _answer.text);
                    _answer.clear();
                  },
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _approvalButton({
    required String label,
    required FaIconData icon,
    required Color background,
    required Color foreground,
    required VoidCallback onTap,
  }) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 10),
        alignment: Alignment.center,
        decoration: BoxDecoration(color: background, borderRadius: LunaTheme.rPill),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            Glyph(icon, size: 12, color: foreground),
            const SizedBox(width: 7),
            Text(label, style: LunaTheme.text(size: 13.5, weight: 600, color: foreground)),
          ],
        ),
      ),
    );
  }

  Widget _running(LunaCore core) {
    final Map<String, String>? live = core.steps.cast<Map<String, String>?>().lastWhere(
          (Map<String, String>? step) => step?['state'] == 'running',
          orElse: () => null,
        );
    final String label = core.waitingOnYou
        ? 'Waiting on you'
        : live != null
            ? (_liveLabels[live['tool']] ?? 'Working')
            : core.thinking
                ? 'Thinking'
                : 'Working';
    return AgentWorkingLine(
      label: label,
      elapsed: core.workElapsed,
      waiting: core.waitingOnYou,
      onStop: core.stop,
    );
  }

  Widget _composer(LunaCore core) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: <Widget>[
        if (_attached.isNotEmpty)
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 0),
            child: Row(
              children: <Widget>[
                Glyph(FontAwesomeIcons.paperclip, size: 11, color: LunaTheme.ink3),
                const SizedBox(width: 7),
                Expanded(
                  child: Text(_attached,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: LunaTheme.text(size: 12, weight: 500, color: LunaTheme.ink2)),
                ),
                GestureDetector(
                  behavior: HitTestBehavior.opaque,
                  onTap: () => setState(() => _attached = ''),
                  child: Glyph(FontAwesomeIcons.xmark, size: 11, color: LunaTheme.ink3),
                ),
              ],
            ),
          ),
        _composerBar(core),
      ],
    );
  }

  Widget _composerBar(LunaCore core) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 6),
      child: Container(
        padding: const EdgeInsets.fromLTRB(15, 5, 5, 5),
        decoration: BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rPill),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: <Widget>[
            Padding(
              padding: const EdgeInsets.only(bottom: 9),
              child: GestureDetector(
                behavior: HitTestBehavior.opaque,
                onTap: core.running ? null : _attach,
                child: Semantics(
                  button: true,
                  label: 'Attach a file',
                  child: Glyph(FontAwesomeIcons.paperclip, size: 13.5, color: LunaTheme.ink3),
                ),
              ),
            ),
            const SizedBox(width: 9),
            Expanded(
              child: ConstrainedBox(
                constraints: const BoxConstraints(maxHeight: 96),
                child: TextField(
                  controller: _input,
                  minLines: 1,
                  maxLines: 4,
                  cursorColor: LunaTheme.ink,
                  cursorWidth: 1.6,
                  style: LunaTheme.text(size: 13.5),
                  textInputAction: TextInputAction.send,
                  onSubmitted: (String _) => _send(),
                  decoration: InputDecoration(
                    isDense: true,
                    border: InputBorder.none,
                    hintText: core.running ? 'Add to the job…' : 'Tell Luna what to do…',
                    hintStyle: LunaTheme.text(size: 13.5, color: LunaTheme.ink3),
                    contentPadding: const EdgeInsets.symmetric(vertical: 9),
                  ),
                ),
              ),
            ),
            const SizedBox(width: 9),
            GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: core.running ? core.stop : _send,
              child: Container(
                width: 34,
                height: 34,
                alignment: Alignment.center,
                decoration: BoxDecoration(color: LunaTheme.ink, shape: BoxShape.circle),
                child: Glyph(
                  core.running ? FontAwesomeIcons.stop : FontAwesomeIcons.arrowUp,
                  size: 12.5,
                  color: LunaTheme.onInk,
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
