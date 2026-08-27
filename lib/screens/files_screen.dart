import 'package:flutter/material.dart';
import 'package:font_awesome_flutter/font_awesome_flutter.dart';

import '../core/luna_core.dart';
import '../theme.dart';
import '../widgets/common.dart';

/// Files — the granted folder, exactly as the device sees it.
///
/// Nothing here is a copy: every row is live SAF. Sensitive files are shown
/// but greyed and locked, because hiding them would be a lie.
class FilesScreen extends StatefulWidget {
  const FilesScreen({super.key, required this.core, required this.onAsk});

  final LunaCore core;
  final ValueChanged<String> onAsk;

  @override
  State<FilesScreen> createState() => _FilesScreenState();
}

class _FilesScreenState extends State<FilesScreen> {
  String _path = '';
  bool _loading = false;
  String? _error;
  List<Map<String, dynamic>> _entries = <Map<String, dynamic>>[];

  @override
  void initState() {
    super.initState();
    _reload();
  }

  Future<void> _reload() async {
    if (!widget.core.workspaceGranted) {
      setState(() => _entries = <Map<String, dynamic>>[]);
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final List<Map<String, dynamic>> entries = await widget.core.listFolder(_path);
      entries.sort((Map<String, dynamic> a, Map<String, dynamic> b) {
        final bool aFolder = a['type'] == 'folder';
        final bool bFolder = b['type'] == 'folder';
        if (aFolder != bFolder) return aFolder ? -1 : 1;
        return '${a['name']}'.toLowerCase().compareTo('${b['name']}'.toLowerCase());
      });
      if (!mounted) return;
      setState(() {
        _entries = entries;
        _loading = false;
      });
    } catch (error) {
      if (!mounted) return;
      setState(() {
        _error = '$error';
        _loading = false;
      });
    }
  }

  void _open(String name) {
    setState(() => _path = _path.isEmpty ? name : '$_path/$name');
    _reload();
  }

  void _upTo(int segmentIndex) {
    final List<String> parts = _path.isEmpty ? <String>[] : _path.split('/');
    setState(() => _path = segmentIndex < 0 ? '' : parts.sublist(0, segmentIndex + 1).join('/'));
    _reload();
  }

  String _child(String name) => _path.isEmpty ? name : '$_path/$name';

  @override
  Widget build(BuildContext context) {
    final LunaCore core = widget.core;
    final List<Map<String, dynamic>> folders =
        _entries.where((Map<String, dynamic> e) => e['type'] == 'folder').toList();
    final List<Map<String, dynamic>> files =
        _entries.where((Map<String, dynamic> e) => e['type'] != 'folder').toList();

    return Column(
      children: <Widget>[
        ScreenTop(
          title: 'Files',
          actions: <Widget>[
            IconButtonSoft(
              icon: FontAwesomeIcons.folderPlus,
              label: 'New folder',
              onTap: core.workspaceGranted ? () => _createSheet(folder: true) : null,
            ),
            IconButtonSoft(
              icon: FontAwesomeIcons.filePen,
              label: 'New file',
              onTap: core.workspaceGranted ? () => _createSheet(folder: false) : null,
            ),
          ],
        ),
        if (core.workspaceGranted) _breadcrumbs(core),
        Expanded(
          child: !core.workspaceGranted
              ? ListView(children: <Widget>[_grantPrompt(core)])
              : RefreshIndicator(
                  color: LunaTheme.ink,
                  onRefresh: _reload,
                  child: ListView(
                    padding: const EdgeInsets.only(bottom: 14),
                    children: <Widget>[
                      if (_error != null) _errorNote(_error!),
                      if (_loading && _entries.isEmpty)
                        const Padding(
                          padding: EdgeInsets.only(top: 40),
                          child: Center(
                            child: SizedBox(
                              width: 16,
                              height: 16,
                              child: CircularProgressIndicator(
                                  strokeWidth: 1.6, color: LunaTheme.ink3),
                            ),
                          ),
                        ),
                      if (!_loading && _entries.isEmpty && _error == null)
                        EmptyState(
                          icon: FontAwesomeIcons.folderOpen,
                          title: 'Nothing here',
                          body: 'This folder is empty. Create a file, or ask Luna to fill it.',
                          action: PillButton(
                            label: 'New file',
                            icon: FontAwesomeIcons.filePen,
                            small: true,
                            onTap: () => _createSheet(folder: false),
                          ),
                        ),
                      if (folders.isNotEmpty) ...<Widget>[
                        SectionLabel('${folders.length} folders'),
                        Group(
                          children: folders
                              .map((Map<String, dynamic> entry) => LunaRow(
                                    icon: FontAwesomeIcons.folder,
                                    title: '${entry['name']}',
                                    trailing: const Glyph(FontAwesomeIcons.chevronRight,
                                        size: 10.5, color: LunaTheme.ink4),
                                    onTap: () => _open('${entry['name']}'),
                                  ))
                              .toList(),
                        ),
                      ],
                      if (files.isNotEmpty) ...<Widget>[
                        SectionLabel('${files.length} files'),
                        PlainList(
                          children: files
                              .map((Map<String, dynamic> entry) => _fileRow(entry))
                              .toList(),
                        ),
                      ],
                      if (core.lastBackup != null) _undoNote(core),
                      _askNote(),
                    ],
                  ),
                ),
        ),
      ],
    );
  }

  Widget _breadcrumbs(LunaCore core) {
    final List<String> parts = _path.isEmpty ? <String>[] : _path.split('/');
    final List<Widget> crumbs = <Widget>[
      _crumb(core.workspaceName.isEmpty ? 'Workspace' : core.workspaceName, () => _upTo(-1),
          last: parts.isEmpty),
    ];
    for (int index = 0; index < parts.length; index++) {
      crumbs.add(const Padding(
        padding: EdgeInsets.symmetric(horizontal: 6),
        child: Glyph(FontAwesomeIcons.chevronRight, size: 8.5, color: LunaTheme.ink4),
      ));
      crumbs.add(_crumb(parts[index], () => _upTo(index), last: index == parts.length - 1));
    }
    return Container(
      height: 34,
      margin: const EdgeInsets.only(bottom: 4),
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 20),
        children: <Widget>[Row(children: crumbs)],
      ),
    );
  }

  Widget _crumb(String label, VoidCallback onTap, {required bool last}) {
    return GestureDetector(
      behavior: HitTestBehavior.opaque,
      onTap: onTap,
      child: Text(
        label,
        style: LunaTheme.text(
          size: 12.5,
          weight: last ? 600 : 500,
          color: last ? LunaTheme.ink : LunaTheme.ink3,
        ),
      ),
    );
  }

  Widget _fileRow(Map<String, dynamic> entry) {
    final bool locked = entry['locked'] == true;
    final String name = '${entry['name']}';
    return LunaRow(
      icon: locked ? FontAwesomeIcons.lock : _iconFor(name),
      title: name,
      muted: locked,
      subtitle: locked ? 'Protected — Luna cannot open this' : formatBytes(entry['size'] as num?),
      tileOnFill: true,
      trailing: locked
          ? null
          : const Glyph(FontAwesomeIcons.ellipsis, size: 13, color: LunaTheme.ink4),
      onTap: locked ? null : () => _fileSheet(entry),
    );
  }

  FaIconData _iconFor(String name) {
    final String lower = name.toLowerCase();
    if (lower.endsWith('.md') || lower.endsWith('.txt')) return FontAwesomeIcons.fileLines;
    if (lower.endsWith('.json') || lower.endsWith('.yaml') || lower.endsWith('.yml')) {
      return FontAwesomeIcons.fileCode;
    }
    if (lower.endsWith('.png') || lower.endsWith('.jpg') || lower.endsWith('.jpeg')) {
      return FontAwesomeIcons.fileImage;
    }
    if (lower.endsWith('.pdf')) return FontAwesomeIcons.filePdf;
    if (lower.endsWith('.zip') || lower.endsWith('.gz')) return FontAwesomeIcons.fileZipper;
    if (lower.endsWith('.dart') ||
        lower.endsWith('.java') ||
        lower.endsWith('.py') ||
        lower.endsWith('.js') ||
        lower.endsWith('.ts')) {
      return FontAwesomeIcons.code;
    }
    return FontAwesomeIcons.file;
  }

  Widget _grantPrompt(LunaCore core) {
    return EmptyState(
      icon: FontAwesomeIcons.folderOpen,
      title: 'Grant Luna a folder',
      body:
          'Luna only ever sees the one folder you choose. Everything else on the device stays out of reach.',
      action: PillButton(
        label: 'Choose folder',
        icon: FontAwesomeIcons.folderOpen,
        onTap: () async {
          await core.pickFolder();
          if (mounted) _reload();
        },
      ),
    );
  }

  Widget _errorNote(String message) {
    return Note(
      icon: FontAwesomeIcons.triangleExclamation,
      children: <InlineSpan>[TextSpan(text: message)],
    );
  }

  Widget _undoNote(LunaCore core) {
    final Map<String, dynamic> backup = core.lastBackup!;
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Note(
        icon: FontAwesomeIcons.rotateLeft,
        children: <InlineSpan>[
          const TextSpan(text: 'Luna last changed '),
          TextSpan(
              text: '${backup['name'] ?? backup['path'] ?? 'a file'}',
              style: LunaTheme.text(size: 12.5, weight: 600, color: LunaTheme.ink)),
          const TextSpan(text: '. '),
          WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: GestureDetector(
              onTap: () async {
                await core.undo();
                if (mounted) _reload();
              },
              child: Text('Undo that',
                  style: LunaTheme.text(size: 12.5, weight: 600, color: LunaTheme.ink)
                      .copyWith(decoration: TextDecoration.underline)),
            ),
          ),
        ],
      ),
    );
  }

  Widget _askNote() {
    return Padding(
      padding: const EdgeInsets.only(top: 4),
      child: Note(
        icon: FontAwesomeIcons.solidComment,
        children: <InlineSpan>[
          const TextSpan(text: 'Want a summary of this folder? '),
          WidgetSpan(
            alignment: PlaceholderAlignment.middle,
            child: GestureDetector(
              onTap: () => widget.onAsk(
                  'Summarise what is in ${_path.isEmpty ? 'the workspace root' : _path} and tell me what stands out.'),
              child: Text('Ask Luna',
                  style: LunaTheme.text(size: 12.5, weight: 600, color: LunaTheme.ink)
                      .copyWith(decoration: TextDecoration.underline)),
            ),
          ),
        ],
      ),
    );
  }

  // --- sheets ---------------------------------------------------------------

  Future<void> _fileSheet(Map<String, dynamic> entry) async {
    final String name = '${entry['name']}';
    final String path = _child(name);
    await showLunaSheet<void>(
      context: context,
      title: name,
      builder: (BuildContext sheetContext) {
        return Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            Group(
              children: <Widget>[
                LunaRow(
                  icon: FontAwesomeIcons.eye,
                  title: 'View',
                  subtitle: formatBytes(entry['size'] as num?),
                  tileOnFill: false,
                  onTap: () {
                    Navigator.of(sheetContext).pop();
                    _viewSheet(path, name);
                  },
                ),
                LunaRow(
                  icon: FontAwesomeIcons.pen,
                  title: 'Rename',
                  onTap: () {
                    Navigator.of(sheetContext).pop();
                    _renameSheet(path, name);
                  },
                ),
                LunaRow(
                  icon: FontAwesomeIcons.solidComment,
                  title: 'Ask Luna about this file',
                  onTap: () {
                    Navigator.of(sheetContext).pop();
                    widget.onAsk('Read $path and tell me what it does.');
                  },
                ),
                LunaRow(
                  icon: FontAwesomeIcons.trash,
                  title: 'Delete',
                  subtitle: 'A backup is kept so you can undo it',
                  onTap: () async {
                    Navigator.of(sheetContext).pop();
                    await widget.core.deleteFile(path);
                    await widget.core.refresh();
                    if (mounted) _reload();
                  },
                ),
              ],
            ),
            const SizedBox(height: 8),
          ],
        );
      },
    );
  }

  Future<void> _viewSheet(String path, String name) async {
    String content;
    try {
      content = await widget.core.readFile(path);
    } catch (error) {
      content = '$error';
    }
    if (!mounted) return;
    await showLunaSheet<void>(
      context: context,
      title: name,
      builder: (BuildContext _) => Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 8),
        child: Container(
          padding: const EdgeInsets.all(14),
          decoration: const BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rNote),
          child: SelectableText(content, style: LunaTheme.monoStyle(size: 12, color: LunaTheme.ink2)),
        ),
      ),
    );
  }

  Future<void> _renameSheet(String path, String name) async {
    final TextEditingController controller = TextEditingController(text: name);
    await showLunaSheet<void>(
      context: context,
      title: 'Rename',
      builder: (BuildContext sheetContext) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          LunaField(label: 'New name', controller: controller, autofocus: true),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 2, 20, 8),
            child: PillButton(
              label: 'Rename',
              icon: FontAwesomeIcons.check,
              onTap: () async {
                Navigator.of(sheetContext).pop();
                await widget.core.renameFile(path, controller.text.trim());
                if (mounted) _reload();
              },
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _createSheet({required bool folder}) async {
    final TextEditingController controller = TextEditingController();
    await showLunaSheet<void>(
      context: context,
      title: folder ? 'New folder' : 'New file',
      builder: (BuildContext sheetContext) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          LunaField(
            label: 'Name',
            controller: controller,
            autofocus: true,
            hint: folder ? 'notes' : 'notes.md',
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 2, 20, 8),
            child: PillButton(
              label: 'Create',
              icon: FontAwesomeIcons.plus,
              onTap: () async {
                final String name = controller.text.trim();
                Navigator.of(sheetContext).pop();
                if (name.isEmpty) return;
                if (folder) {
                  await widget.core.createFolder(_child(name));
                } else {
                  await widget.core.createFile(_child(name));
                }
                if (mounted) _reload();
              },
            ),
          ),
        ],
      ),
    );
  }
}
