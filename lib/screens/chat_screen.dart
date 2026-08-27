import 'dart:async';

import 'package:flutter/material.dart';
import 'package:font_awesome_flutter/font_awesome_flutter.dart';

import '../core/luna_core.dart';
import '../theme.dart';
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
    'load_model': 'Loaded the model',
  };

  @override
  void initState() {
    super.initState();
    _clock = Timer.periodic(const Duration(seconds: 1), (Timer _) {
      if (mounted && widget.core.running) setState(() {});
    });
  }

  @override
  void dispose() {
    _clock?.cancel();
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
    final String text = _input.text.trim();
    if (text.isEmpty || widget.core.running) return;
    _input.clear();
    await widget.core.send(text);
    _pinToBottom();
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
              icon: FontAwesomeIcons.penToSquare,
              label: 'New chat',
              onTap: core.running ? null : () => core.newChat(),
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
      decoration: const BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rPill),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: <Widget>[
          const Glyph(FontAwesomeIcons.microchip, size: 10.5, color: LunaTheme.ink4),
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
            decoration: const BoxDecoration(color: LunaTheme.ink4, shape: BoxShape.circle),
          ),
          const SizedBox(width: 7),
          const Glyph(FontAwesomeIcons.folderOpen, size: 10.5, color: LunaTheme.ink4),
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
      decoration: const BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rPill),
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
        child: _lunaBubble(core.streaming),
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
            decoration: const BoxDecoration(
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

  Widget _lunaBubble(String text) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: <Widget>[
        const Padding(padding: EdgeInsets.only(top: 2), child: Mark(size: 22)),
        const SizedBox(width: 10),
        Expanded(child: Text(text, style: LunaTheme.body)),
      ],
    );
  }

  Widget _steps(LunaCore core) {
    final List<Widget> rows = <Widget>[];
    for (int index = 0; index < core.steps.length; index++) {
      final Map<String, String> step = core.steps[index];
      final bool done = step['state'] == 'done';
      final String tool = step['tool'] ?? '';
      rows.add(Padding(
        padding: const EdgeInsets.symmetric(vertical: 8),
        child: Row(
          children: <Widget>[
            Container(
              width: 19,
              height: 19,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: done ? LunaTheme.ink : LunaTheme.paper,
                shape: BoxShape.circle,
              ),
              child: Glyph(
                done ? FontAwesomeIcons.check : FontAwesomeIcons.hourglassHalf,
                size: 8.5,
                color: done ? LunaTheme.onInk : LunaTheme.ink3,
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                _stepLabels[tool] ?? tool,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: LunaTheme.text(
                  size: 13,
                  weight: done ? 550 : 500,
                  color: done ? LunaTheme.ink : LunaTheme.ink3,
                ),
              ),
            ),
            if ((step['path'] ?? '').isNotEmpty)
              Text(step['path']!.split('/').last,
                  style: LunaTheme.text(size: 12, color: LunaTheme.ink3)),
          ],
        ),
      ));
      if (index != core.steps.length - 1) {
        rows.add(Container(height: 1, color: const Color(0x0D000000)));
      }
    }
    return Container(
      margin: const EdgeInsets.fromLTRB(52, 10, 20, 0),
      padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 3),
      decoration: const BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rStep),
      child: Column(crossAxisAlignment: CrossAxisAlignment.stretch, children: rows),
    );
  }

  /// The one filled-black surface on this screen.
  Widget _approval(LunaCore core) {
    final Map<String, dynamic> approval = core.pendingApproval!;
    final String preview = (approval['preview'] as String?) ?? '';
    return Container(
      margin: const EdgeInsets.fromLTRB(20, 12, 20, 0),
      padding: const EdgeInsets.fromLTRB(16, 16, 16, 13),
      decoration: const BoxDecoration(color: LunaTheme.ink, borderRadius: LunaTheme.rCard),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              const Glyph(FontAwesomeIcons.hand, size: 11, color: LunaTheme.onInkDim),
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
    return Container(
      margin: const EdgeInsets.fromLTRB(20, 10, 20, 0),
      padding: const EdgeInsets.symmetric(horizontal: 15, vertical: 8),
      decoration: const BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rPill),
      child: Row(
        children: <Widget>[
          const SizedBox(
            width: 11,
            height: 11,
            child: CircularProgressIndicator(strokeWidth: 1.6, color: LunaTheme.ink3),
          ),
          const SizedBox(width: 9),
          Text(
            core.thinking ? 'Thinking' : 'Working',
            style: LunaTheme.text(size: 12.5, weight: 500, color: LunaTheme.ink2),
          ),
          Text(
            core.runStartedAt == null ? '' : ' · ${formatElapsed(core.runStartedAt)}',
            style: LunaTheme.text(size: 12.5, weight: 500, color: LunaTheme.ink3),
          ),
          const Spacer(),
          GestureDetector(
            behavior: HitTestBehavior.opaque,
            onTap: core.stop,
            child: Row(
              children: <Widget>[
                const Glyph(FontAwesomeIcons.stop, size: 10.5, color: LunaTheme.ink),
                const SizedBox(width: 6),
                Text('Stop', style: LunaTheme.text(size: 12.5, weight: 600)),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _composer(LunaCore core) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 8, 20, 6),
      child: Container(
        padding: const EdgeInsets.fromLTRB(15, 5, 5, 5),
        decoration: const BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rPill),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.end,
          children: <Widget>[
            const Padding(
              padding: EdgeInsets.only(bottom: 9),
              child: Glyph(FontAwesomeIcons.paperclip, size: 13.5, color: LunaTheme.ink3),
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
                decoration: const BoxDecoration(color: LunaTheme.ink, shape: BoxShape.circle),
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
