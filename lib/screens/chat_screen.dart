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
    'failover': 'Fell back to the cloud',
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
    'failover': 'Falling back to the cloud',
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

  /// Follow the newest words, unless you have scrolled up to reread something.
  /// Being dragged back to the bottom mid-sentence is worse than missing a
  /// line, so this only acts when you were already near the end.
  void _pinToBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!_scroll.hasClients) return;
      final double end = _scroll.position.maxScrollExtent;
      if (end - _scroll.offset <= 160) {
        _scroll.jumpTo(end);
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
    search.dispose();
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
          // A chat reads downward: the first thing said is at the top, and
          // each new turn is added under the last one.
          child: SingleChildScrollView(
            controller: _scroll,
            padding: const EdgeInsets.only(bottom: 8),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                if (core.messages.isEmpty && !core.running) _empty(core),
                ..._thread(core),
                if (core.pendingApproval != null) _approval(core),
                if (core.pendingQuestion != null) _question(core),
                if (core.canCarryOn) _carryOn(core),
              ],
            ),
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

  /// Everything Luna says shares one column: indented 24 from the gutter,
  /// 80% of the width, and no card behind it. The user's words are the only
  /// filled shape in the thread, so the two sides never look symmetrical.
  static const double _agentIndent = 24;
  static const double _agentWidth = 0.8;

  Widget _agentColumn(Widget child, {double bottom = 11}) {
    return Padding(
      padding: EdgeInsets.fromLTRB(_agentIndent, 0, 20, bottom),
      child: FractionallySizedBox(
        widthFactor: _agentWidth,
        alignment: Alignment.topLeft,
        child: child,
      ),
    );
  }

  List<Widget> _thread(LunaCore core) {
    final List<Widget> out = <Widget>[];
    // Everything after the last thing you said is this turn: the record of
    // the work comes first, then the answer it produced. Reading it the other
    // way round is reading the receipt before the shop.
    int lastUser = -1;
    for (int i = 0; i < core.messages.length; i++) {
      if (core.messages[i]['role'] == 'user') lastUser = i;
    }
    // The trace is part of every answer, not only the ones that went wrong:
    // a plain answer renders as one quiet "Thought for Xs" line, and a turn
    // that used tools folds open to the record of the work. But a chat with
    // nothing sent and nothing running has no answer to attach it to.
    bool tracePlaced = false;
    for (int i = 0; i < core.messages.length; i++) {
      final Map<String, dynamic> message = core.messages[i];
      final String role = (message['role'] as String?) ?? '';
      final String content = (message['content'] as String?) ?? '';
      if (role == 'observation' || content.isEmpty) continue;
      if (role == 'user') {
        // 24 under a question, 11 under anything else: the answer belongs to
        // the question above it, and the spacing should say so.
        out.add(Padding(
          padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
          child: _userBubble(content),
        ));
        continue;
      }
      if (!tracePlaced && i > lastUser) {
        out.add(_steps(core));
        tracePlaced = true;
      }
      out.add(_agentColumn(Text(content, style: LunaTheme.body)));
    }
    // Nothing was asked and nothing is running: there is no answer to attach
    // the trace to, so it stays out. This is what keeps an empty chat from
    // showing a stray "Thought for Xs" under the "Pick a model" card.
    if (!tracePlaced && (lastUser != -1 || core.running || core.waitingOnYou)) {
      out.add(_steps(core));
      tracePlaced = true;
    }
    if (core.streaming.isNotEmpty) {
      out.add(_agentColumn(StreamedAnswer(text: core.streaming)));
    }
    return out;
  }

  Widget _userBubble(String text) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.end,
      children: <Widget>[
        Flexible(
          child: Align(
            alignment: Alignment.centerRight,
            child: ConstrainedBox(
              // Hugs its words; 80% is only the point at which it wraps.
              constraints: BoxConstraints(
                  maxWidth: MediaQuery.sizeOf(context).width * _agentWidth),
              child: Container(
                padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 7),
                decoration: BoxDecoration(
                  color: LunaTheme.ink,
                  borderRadius: const BorderRadius.only(
                    topLeft: Radius.circular(15),
                    topRight: Radius.circular(15),
                    bottomLeft: Radius.circular(15),
                    bottomRight: Radius.circular(5),
                  ),
                ),
                child: Text(text, style: LunaTheme.bubble),
              ),
            ),
          ),
        ),
      ],
    );
  }

  /// What a tool is called once it has been refused. Past tense, negative,
  /// so the words and the cross beside them say the same thing.
  static const Map<String, String> _refusedLabels = <String, String>{
    'list_files': 'Did not list the folder',
    'read_file': 'Did not read a file',
    'search_code': 'Did not search the folder',
    'write_file': 'Did not write a file',
    'create_file': 'Did not create a file',
    'create_folder': 'Did not create a folder',
    'delete_file': 'Did not delete a file',
    'rename_file': 'Did not rename a file',
    'open_page': 'Did not open a page',
    'read_page': 'Did not read the page',
    'github_file': 'Did not fetch from GitHub',
    'ask_user': 'Did not ask you',
    'load_model': 'Could not load the model',
  };

  Widget _steps(LunaCore core) {
    final List<TraceStep> steps = <TraceStep>[];
    for (final Map<String, String> step in core.steps) {
      final String tool = step['tool'] ?? '';
      final String state = step['state'] ?? '';
      final String path = step['path'] ?? '';
      // Loading the model is how Luna gets to work, not work. Once it has
      // loaded, the row is noise; if it fails, it is the whole story.
      if (tool == 'load_model' && (state == 'done' || state == 'replayed')) {
        continue;
      }
      final bool refused = state == 'denied' ||
          state == 'declined' ||
          state == 'blocked' ||
          state == 'no_folder' ||
          state == 'invented' ||
          state == 'failed' ||
          state == 'unfinished';
      final String name = path.isEmpty ? '' : path.split('/').last;
      final Map<String, String> reasons = <String, String>{
        'held': 'waiting for you to allow it',
        'declined': 'you skipped this one',
        'replayed': 'already read this run',
        'blocked': 'over the limit for one job',
        'no_folder': 'there is no folder to work in',
        'invented': 'that address is not a real site',
        'unfinished': 'took too long and was dropped',
        'failed': 'it did not work',
      };
      final String detail = state == 'denied'
          ? (tool == 'load_model' ? 'the model would not load' : 'not allowed')
          : (reasons[state] ?? name);
      final String label = refused
          ? (_refusedLabels[tool] ?? _stepLabels[tool] ?? tool)
          : (state == 'running' || state == 'held')
              ? (_liveLabels[tool] ?? tool)
              : (_stepLabels[tool] ?? tool);
      steps.add(TraceStep(label: label, state: state, detail: detail));
    }

    return _agentColumn(AgentTrace(
      steps: steps,
      running: core.running,
      waiting: core.waitingOnYou,
      label: _activeLabel(core),
      elapsed: core.running ? core.workElapsed : core.runElapsed,
    ));
  }

  /// One name for what is happening, used in one place.
  String _activeLabel(LunaCore core) {
    for (final Map<String, String> step in core.steps.reversed) {
      if (step['state'] == 'running') {
        return _liveLabels[step['tool']] ?? 'Working';
      }
    }
    return core.thinking ? 'Thinking' : 'Working';
  }

  /// The one filled-black surface on this screen. It fits its words: a short
  /// question is a short card, and Skip is legible rather than ghosted.
  Widget _approval(LunaCore core) {
    final Map<String, dynamic> approval = core.pendingApproval!;
    final String preview = ((approval['preview'] as String?) ?? '').trim();
    // An empty preview used to render as a lone ellipsis chip: a box with
    // nothing in it, above the two buttons that matter.
    final bool showPreview = preview.isNotEmpty && preview != '…' && preview != '...';
    return Padding(
      padding: const EdgeInsets.fromLTRB(_agentIndent, 2, 20, 11),
      child: Align(
        alignment: Alignment.topLeft,
        child: ConstrainedBox(
          constraints: BoxConstraints(
              maxWidth: MediaQuery.sizeOf(context).width * _agentWidth),
          child: Container(
            padding: const EdgeInsets.fromLTRB(13, 12, 13, 11),
            decoration: BoxDecoration(color: LunaTheme.ink, borderRadius: LunaTheme.rCard),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    Glyph(FontAwesomeIcons.hand, size: 10, color: LunaTheme.onInkDim),
                    const SizedBox(width: 6),
                    Text('Needs your approval',
                        style: LunaTheme.text(
                            size: 10.5, weight: 600, color: LunaTheme.onInkDim)),
                  ],
                ),
                const SizedBox(height: 4),
                Text((approval['headline'] as String?) ?? 'Allow this?',
                    style: LunaTheme.decision),
                if (((approval['consequence'] as String?) ?? '').isNotEmpty) ...<Widget>[
                  const SizedBox(height: 3),
                  Text((approval['consequence'] as String?) ?? '',
                      style: LunaTheme.text(
                          size: 11.5, color: LunaTheme.onInkFaint, height: 1.4)),
                ],
                if (showPreview)
                  Container(
                    margin: const EdgeInsets.only(top: 9),
                    padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 8),
                    constraints: const BoxConstraints(maxHeight: 120),
                    decoration: BoxDecoration(
                        color: LunaTheme.inkCell, borderRadius: LunaTheme.rField),
                    child: SingleChildScrollView(
                      child: Text(preview,
                          style: LunaTheme.monoStyle(size: 11, color: LunaTheme.onInk)),
                    ),
                  ),
                const SizedBox(height: 11),
                Row(
                  mainAxisSize: MainAxisSize.min,
                  children: <Widget>[
                    _approvalButton(
                      label: 'Allow',
                      icon: FontAwesomeIcons.check,
                      background: LunaTheme.onInk,
                      foreground: LunaTheme.ink,
                      onTap: () => core.approve('${approval['id']}', true),
                    ),
                    const SizedBox(width: 7),
                    _approvalButton(
                      label: 'Skip',
                      icon: FontAwesomeIcons.xmark,
                      background: LunaTheme.inkButton,
                      foreground: LunaTheme.onInk,
                      onTap: () => core.approve('${approval['id']}', false),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// Luna asking you something. The job is stopped until this is answered.
  Widget _question(LunaCore core) {
    final Map<String, dynamic> question = core.pendingQuestion!;
    return Padding(
      padding: const EdgeInsets.fromLTRB(_agentIndent, 2, 20, 11),
      child: Container(
      padding: const EdgeInsets.fromLTRB(13, 12, 13, 11),
      decoration: BoxDecoration(color: LunaTheme.ink, borderRadius: LunaTheme.rCard),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          Row(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              Glyph(FontAwesomeIcons.circleQuestion, size: 10, color: LunaTheme.onInkDim),
              const SizedBox(width: 6),
              Text('Luna needs to know',
                  style: LunaTheme.text(size: 10.5, weight: 600, color: LunaTheme.onInkDim)),
            ],
          ),
          const SizedBox(height: 4),
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
          const SizedBox(height: 10),
          Row(
            mainAxisSize: MainAxisSize.min,
            children: <Widget>[
              _approvalButton(
                label: 'Answer',
                icon: FontAwesomeIcons.check,
                background: LunaTheme.onInk,
                foreground: LunaTheme.ink,
                onTap: () {
                  core.answerQuestion('${question['id']}', _answer.text);
                  _answer.clear();
                },
              ),
              const SizedBox(width: 7),
              _approvalButton(
                label: 'Skip',
                icon: FontAwesomeIcons.xmark,
                background: LunaTheme.inkButton,
                foreground: LunaTheme.onInk,
                onTap: () {
                  core.answerQuestion('${question['id']}', '');
                  _answer.clear();
                },
              ),
            ],
          ),
        ],
      ),
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
        padding: const EdgeInsets.symmetric(vertical: 7, horizontal: 13),
        decoration: BoxDecoration(color: background, borderRadius: LunaTheme.rPill),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: <Widget>[
            Glyph(icon, size: 10.5, color: foreground),
            const SizedBox(width: 6),
            Text(label, style: LunaTheme.text(size: 12.5, weight: 600, color: foreground)),
          ],
        ),
      ),
    );
  }

  /// The way back into a job that stopped. Nothing is lost: the steps that
  /// already worked are in the transcript, and Luna is told not to redo them.
  Widget _carryOn(LunaCore core) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 4, 20, 0),
      child: Row(
        children: <Widget>[
          PillButton(
            label: 'Carry on',
            icon: FontAwesomeIcons.play,
            small: true,
            onTap: core.carryOn,
          ),
          const SizedBox(width: 10),
          Flexible(
            child: Text(
              'Picks up from the last step that worked.',
              style: LunaTheme.text(size: 12, color: LunaTheme.ink3),
            ),
          ),
        ],
      ),
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
