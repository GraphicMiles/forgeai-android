import 'package:flutter/material.dart';
import 'package:font_awesome_flutter/font_awesome_flutter.dart';

import '../core/luna_core.dart';
import '../theme.dart';
import '../widgets/common.dart';

/// Model Zoo — where the work actually gets done from.
///
/// Three honest options: on the device, on your own computer, or a cloud key
/// you supply. The one filled-black surface is the model currently loaded.
class ModelsScreen extends StatefulWidget {
  const ModelsScreen({super.key, required this.core});

  final LunaCore core;

  @override
  State<ModelsScreen> createState() => _ModelsScreenState();
}

class _ModelsScreenState extends State<ModelsScreen> {
  int _tab = 0;

  // Controllers belong to the state, not to a build method. Rebuilding one on
  // every frame is how a text field loses your cursor mid-word.
  late final TextEditingController _endpoint =
      TextEditingController(text: widget.core.endpoint);

  @override
  void dispose() {
    _endpoint.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final LunaCore core = widget.core;
    return Column(
      children: <Widget>[
        const ScreenTop(title: 'Model Zoo'),
        Segmented(
          items: const <String>['On device', 'My computer', 'Cloud'],
          index: _tab,
          onChanged: (int index) => setState(() => _tab = index),
        ),
        Expanded(
          child: ListView(
            padding: const EdgeInsets.only(bottom: 16),
            children: <Widget>[
              if (_tab == 0) ..._onDevice(core),
              if (_tab == 1) ..._myComputer(core),
              if (_tab == 2) ..._cloud(core),
            ],
          ),
        ),
      ],
    );
  }

  // --- on device ------------------------------------------------------------

  List<Widget> _onDevice(LunaCore core) {
    final int ramMb = ((core.device['ramBytes'] as num?) ?? 0) ~/ (1024 * 1024);
    final List<Map<String, dynamic>> installed = core.catalog
        .where((Map<String, dynamic> model) => model['installed'] == true)
        .toList();
    final List<Map<String, dynamic>> available = core.catalog
        .where((Map<String, dynamic> model) => model['installed'] != true)
        .toList();
    final Map<String, dynamic>? active = core.activeCatalogModel;
    final List<String> inFlight = core.downloadState.keys.toList();

    return <Widget>[
      if (active != null) _activeHero(core, active),
      if (active == null && installed.isNotEmpty)
        const Note(icon: FontAwesomeIcons.circleInfo, children: <InlineSpan>[
          TextSpan(text: 'Pick one of your downloaded models to make it the active one.'),
        ]),

      const SectionLabel('Your own files'),
      Group(children: <Widget>[
        LunaRow(
          icon: FontAwesomeIcons.fileImport,
          title: 'Import a .gguf',
          subtitle: 'From this phone, an SD card or Drive',
          onTap: () => _import(core),
        ),
        for (final Map<String, dynamic> model in core.importedModels)
          LunaRow(
            icon: FontAwesomeIcons.cube,
            title: '${model['name']}',
            subtitle:
                'Imported · ${formatBytes(model['sizeBytes'] as num?)} · not verified',
            trailing: Row(
              mainAxisSize: MainAxisSize.min,
              children: <Widget>[
                if (model['id'] == core.activeModelId)
                  Text('Active',
                      style: LunaTheme.text(size: 12, weight: 600, color: LunaTheme.ink))
                else
                  PillButton(
                    label: 'Use',
                    small: true,
                    soft: true,
                    onTap: () => core.useModel('${model['id']}'),
                  ),
                const SizedBox(width: 8),
                IconButtonSoft(
                  icon: FontAwesomeIcons.trash,
                  label: 'Remove this imported model',
                  onTap: () => _confirm(
                    title: 'Remove ${model['name']}?',
                    body: 'The file is deleted from Luna\'s models folder. '
                        'Your original copy is untouched.',
                    confirmLabel: 'Remove',
                    onConfirm: () => core.deleteImportedModel('${model['id']}'),
                  ),
                ),
              ],
            ),
          ),
      ]),

      if (inFlight.isNotEmpty) ...<Widget>[
        const SectionLabel('Downloading'),
        Group(
          children: inFlight.map((String id) {
            final Map<String, dynamic> state =
                (core.downloadState[id] as Map<String, dynamic>?) ?? <String, dynamic>{};
            final Map<String, dynamic>? model = _catalogModel(core, id);
            return LunaRow(
              icon: FontAwesomeIcons.downLong,
              title: '${model?['name'] ?? id}',
              child: _downloadRow(core, id, state),
            );
          }).toList(),
        ),
      ],

      if (installed.isNotEmpty) ...<Widget>[
        const SectionLabel('Downloaded'),
        Group(
          children: installed
              .map((Map<String, dynamic> model) => LunaRow(
                    icon: FontAwesomeIcons.microchip,
                    title: '${model['name']}',
                    subtitle: '${model['params']} · ${formatBytes(model['sizeBytes'] as num?)}',
                    trailing: model['id'] == core.activeModelId
                        ? Text('Active',
                            style: LunaTheme.text(size: 12, weight: 600, color: LunaTheme.ink))
                        : PillButton(
                            label: 'Use',
                            small: true,
                            onTap: () => core.useModel('${model['id']}'),
                          ),
                    onTap: () => _modelSheet(core, model),
                  ))
              .toList(),
        ),
      ],

      const SectionLabel('Available to download'),
      Group(
        children: available
            .where((Map<String, dynamic> model) => !inFlight.contains('${model['id']}'))
            .map((Map<String, dynamic> model) {
          final int minRam = ((model['minRamBytes'] as num?) ?? 0) ~/ (1024 * 1024);
          final bool tooBig = ramMb > 0 && minRam > ramMb;
          return LunaRow(
            icon: FontAwesomeIcons.download,
            title: '${model['name']}',
            muted: tooBig,
            subtitle: tooBig
                ? 'Needs ${(minRam / 1024).toStringAsFixed(0)} GB of RAM — this device has ${(ramMb / 1024).toStringAsFixed(1)} GB'
                : '${model['params']} · ${formatBytes(model['sizeBytes'] as num?)}',
            trailing: tooBig
                ? Text('Will not run',
                    style: LunaTheme.text(size: 12, color: LunaTheme.ink4))
                : PillButton(
                    label: 'Get',
                    small: true,
                    soft: true,
                    onTap: () => core.downloadModel('${model['id']}'),
                  ),
          );
        }).toList(),
      ),
      _stats(core),
      if (core.lastChecksum != null) _checksumNote(core),
      const Note(icon: FontAwesomeIcons.shieldHalved, children: <InlineSpan>[
        TextSpan(
            text:
                'Downloaded models run entirely on this device. Nothing leaves it, and they keep working with the radio off.'),
      ]),
    ];
  }

  Map<String, dynamic>? _catalogModel(LunaCore core, String id) {
    for (final Map<String, dynamic> model in core.catalog) {
      if ('${model['id']}' == id) return model;
    }
    return null;
  }

  /// The bar, the two controls, and one honest line about what is happening.
  Widget _downloadRow(LunaCore core, String id, Map<String, dynamic> state) {
    final num completed = (state['completed'] as num?) ?? 0;
    final num total = (state['total'] as num?) ?? 0;
    final String status = '${state['status'] ?? 'downloading'}';
    final double fraction = total > 0 ? completed / total : 0;
    final bool running = status == 'downloading';

    final String line;
    if (status == 'paused') {
      line = 'Paused · ${formatBytes(completed)} of ${formatBytes(total)} · resumes here';
    } else if (status == 'waiting') {
      line = '${state['detail'] ?? 'Waiting'} · ${formatBytes(completed)} kept';
    } else if (status == 'verifying') {
      line = 'Checking the file against its SHA-256…';
    } else if (status == 'failed') {
      line = '${state['detail'] ?? 'It failed'}';
    } else if (total > 0) {
      line = '${formatBytes(completed)} of ${formatBytes(total)} · ${(fraction * 100).round()}%';
    } else {
      line = 'Starting…';
    }

    return Padding(
      padding: const EdgeInsets.only(top: 2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          ProgressBar(value: fraction),
          const SizedBox(height: 7),
          Row(
            children: <Widget>[
              IconButtonSoft(
                icon: running ? FontAwesomeIcons.pause : FontAwesomeIcons.play,
                label: running ? 'Pause' : 'Resume',
                active: true,
                onTap: () =>
                    running ? core.pauseDownload(id) : core.resumeDownload(id),
              ),
              const SizedBox(width: 8),
              IconButtonSoft(
                icon: FontAwesomeIcons.xmark,
                label: 'Cancel and delete what arrived',
                onTap: () => core.cancelDownload(id),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: Text(line,
                    maxLines: 2,
                    style: LunaTheme.text(size: 11.5, color: LunaTheme.ink3, height: 1.35)),
              ),
            ],
          ),
        ],
      ),
    );
  }

  Widget _checksumNote(LunaCore core) {
    final List<String> parts = core.lastChecksum!.split(':');
    final bool ok = parts.length > 1 && parts[1] == 'ok';
    final String digest = parts.length > 2 ? parts[2] : '';
    final String shortened =
        digest.length > 16 ? '${digest.substring(0, 8)}…${digest.substring(digest.length - 8)}' : digest;
    return Note(
      icon: ok ? FontAwesomeIcons.circleCheck : FontAwesomeIcons.triangleExclamation,
      children: <InlineSpan>[
        TextSpan(
            text: ok
                ? 'The last download hashed to $shortened, which matches what the publisher listed.'
                : 'The last download hashed to $shortened, which did not match. The file was deleted.'),
      ],
    );
  }

  Future<void> _import(LunaCore core) async {
    try {
      final Map<String, dynamic>? model = await core.importModel();
      if (!mounted || model == null) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        backgroundColor: LunaTheme.ink,
        content: Text(
          '${model['name']} imported. There is no published checksum for a file you '
          'brought yourself, so it is marked not verified.',
          style: LunaTheme.text(size: 13, color: LunaTheme.onInk),
        ),
      ));
    } catch (error) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(
        backgroundColor: LunaTheme.ink,
        content: Text('$error', style: LunaTheme.text(size: 13, color: LunaTheme.onInk)),
      ));
    }
  }

  /// The one filled-black surface on this screen: what is loaded right now.
  Widget _activeHero(LunaCore core, Map<String, dynamic> model) {
    return Container(
      margin: const EdgeInsets.fromLTRB(20, 14, 20, 0),
      padding: const EdgeInsets.fromLTRB(16, 15, 16, 14),
      decoration: BoxDecoration(color: LunaTheme.ink, borderRadius: LunaTheme.rCard),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              Glyph(FontAwesomeIcons.bolt, size: 11, color: LunaTheme.onInkDim),
              const SizedBox(width: 7),
              Text('Running now',
                  style: LunaTheme.text(size: 11.5, weight: 600, color: LunaTheme.onInkDim)),
              const Spacer(),
              if (core.keepWarm)
                Text('kept warm',
                    style: LunaTheme.text(size: 11, color: LunaTheme.onInkFaint)),
            ],
          ),
          const SizedBox(height: 6),
          Text('${model['name']}',
              style: LunaTheme.displayStyle(
                  size: 17, weight: 700, color: LunaTheme.onInk, letterSpacing: -0.03)),
          const SizedBox(height: 12),
          Row(
            children: <Widget>[
              _heroCell('Size', formatBytes(model['sizeBytes'] as num?)),
              const SizedBox(width: 7),
              _heroCell('Params', '${model['params']}'),
              const SizedBox(width: 7),
              _heroCell(
                  'Speed',
                  core.tokensPerSecond > 0
                      ? '${core.tokensPerSecond.toStringAsFixed(1)} t/s'
                      : '—'),
            ],
          ),
        ],
      ),
    );
  }

  Widget _heroCell(String label, String value) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 11, vertical: 9),
        decoration: BoxDecoration(
            color: LunaTheme.inkCell, borderRadius: BorderRadius.all(Radius.circular(15))),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(label, style: LunaTheme.text(size: 11, color: LunaTheme.onInkFaint)),
            const SizedBox(height: 2),
            Text(value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: LunaTheme.text(size: 13.5, weight: 600, color: LunaTheme.onInk)),
          ],
        ),
      ),
    );
  }

  Widget _stats(LunaCore core) {
    final num ram = (core.device['ramBytes'] as num?) ?? 0;
    final num freeRam = (core.device['availableRamBytes'] as num?) ?? 0;
    final num freeDisk = (core.device['availableStorageBytes'] as num?) ?? 0;
    final int threads = ((core.device['suggestedThreads'] as num?) ?? 0).toInt();
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 0),
      child: Row(
        children: <Widget>[
          _stat('Memory free', '${formatBytes(freeRam)} of ${formatBytes(ram)}'),
          const SizedBox(width: 8),
          _stat('Storage free', formatBytes(freeDisk)),
          const SizedBox(width: 8),
          _stat('Threads', threads == 0 ? '—' : '$threads'),
        ],
      ),
    );
  }

  Widget _stat(String label, String value) {
    return Expanded(
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 11),
        decoration: BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rNote),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: <Widget>[
            Text(label, style: LunaTheme.text(size: 11, color: LunaTheme.ink3)),
            const SizedBox(height: 3),
            Text(value,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: LunaTheme.displayStyle(size: 14, weight: 700, letterSpacing: -0.02)),
          ],
        ),
      ),
    );
  }

  Future<void> _modelSheet(LunaCore core, Map<String, dynamic> model) async {
    await showLunaSheet<void>(
      context: context,
      title: '${model['name']}',
      builder: (BuildContext sheetContext) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Group(
            children: <Widget>[
              LunaRow(
                icon: FontAwesomeIcons.play,
                title: 'Use this model',
                onTap: () {
                  Navigator.of(sheetContext).pop();
                  core.useModel('${model['id']}');
                },
              ),
              LunaRow(
                icon: FontAwesomeIcons.trash,
                title: 'Delete the download',
                subtitle: formatBytes(model['sizeBytes'] as num?),
                onTap: () {
                  Navigator.of(sheetContext).pop();
                  _confirm(
                    title: 'Delete ${model['name']}?',
                    body: 'It leaves the phone. You can download it again, '
                        'but that is ${formatBytes(model['sizeBytes'] as num?)} over the network.',
                    confirmLabel: 'Delete',
                    onConfirm: () => core.deleteModel('${model['id']}'),
                  );
                },
              ),
            ],
          ),
          const SizedBox(height: 8),
        ],
      ),
    );
  }

  // --- my computer ----------------------------------------------------------

  List<Widget> _myComputer(LunaCore core) {
    return <Widget>[
      const SizedBox(height: 12),
      LunaField(
        label: 'Ollama address',
        controller: _endpoint,
        hint: 'http://192.168.1.20:11434',
        mono: true,
        help: 'Your phone and your computer have to be on the same network.',
        onSubmitted: core.setEndpoint,
      ),
      Padding(
        padding: const EdgeInsets.fromLTRB(20, 0, 20, 4),
        child: Row(
          children: <Widget>[
            PillButton(
              label: 'Save and check',
              icon: FontAwesomeIcons.plug,
              onTap: () async {
                await core.setEndpoint(_endpoint.text.trim());
                await _probeOllama(core);
              },
            ),
          ],
        ),
      ),
      const Note(icon: FontAwesomeIcons.houseLaptop, children: <InlineSpan>[
        TextSpan(
            text:
                'Your computer does the thinking, your phone does the work. Prompts stay on your own network.'),
      ]),
    ];
  }

  Future<void> _probeOllama(LunaCore core) async {
    List<Map<String, dynamic>> models = <Map<String, dynamic>>[];
    String? failure;
    try {
      models = await core.ollamaModels();
      if (models.isEmpty) {
        failure = 'Nothing answered at ${core.endpoint}. Check the address, and that Ollama '
            'is running with OLLAMA_HOST=0.0.0.0 so it accepts calls from the phone.';
      }
    } catch (error) {
      failure = '$error';
    }
    if (!mounted) return;
    await showLunaSheet<void>(
      context: context,
      title: failure == null ? 'Found ${models.length} models' : 'Could not connect',
      builder: (BuildContext sheetContext) {
        if (failure != null) {
          return Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 12),
            child: Text(failure,
                style: LunaTheme.text(size: 13, color: LunaTheme.ink2, height: 1.5)),
          );
        }
        return Group(
          children: models
              .map((Map<String, dynamic> model) => LunaRow(
                    icon: FontAwesomeIcons.server,
                    title: '${model['name']}',
                    subtitle: formatBytes(model['size'] as num?),
                    trailing: PillButton(
                      label: 'Use',
                      small: true,
                      onTap: () async {
                        Navigator.of(sheetContext).pop();
                        await core.addCloudProvider(
                          label: '${model['name']} (my computer)',
                          baseUrl: '${core.endpoint}/v1',
                          apiKey: 'ollama',
                          model: '${model['name']}',
                        );
                        if (core.cloudProviders.isNotEmpty) {
                          await core.useModel('cloud:${core.cloudProviders.last['id']}');
                        }
                      },
                    ),
                  ))
              .toList(),
        );
      },
    );
  }

  // --- cloud ----------------------------------------------------------------

  List<Widget> _cloud(LunaCore core) {
    return <Widget>[
      const SizedBox(height: 4),
      if (core.cloudProviders.isEmpty)
        EmptyState(
          icon: FontAwesomeIcons.key,
          title: 'No keys yet',
          body:
              'Add an OpenAI-compatible endpoint and key. Luna stores it in the device keystore, not in a file.',
          action: PillButton(
            label: 'Add a key',
            icon: FontAwesomeIcons.plus,
            small: true,
            onTap: () => _keySheet(core),
          ),
        )
      else ...<Widget>[
        SectionLabel('${core.cloudProviders.length} connected',
            action: GestureDetector(
              onTap: () => _keySheet(core),
              child: Text('Add', style: LunaTheme.text(size: 12.5, weight: 600)),
            )),
        Group(
          children: core.cloudProviders.map((Map<String, dynamic> provider) {
            final String id = 'cloud:${provider['id']}';
            return LunaRow(
              icon: FontAwesomeIcons.cloud,
              title: '${provider['label']}',
              subtitle: '${provider['model']}',
              trailing: Row(
                mainAxisSize: MainAxisSize.min,
                children: <Widget>[
                  if (id == core.activeModelId)
                    Text('Active',
                        style: LunaTheme.text(size: 12, weight: 600, color: LunaTheme.ink))
                  else
                    PillButton(label: 'Use', small: true, soft: true, onTap: () => core.useModel(id)),
                  const SizedBox(width: 8),
                  IconButtonSoft(
                    icon: FontAwesomeIcons.ellipsis,
                    label: 'Provider options',
                    onTap: () => _providerSheet(core, provider),
                  ),
                ],
              ),
            );
          }).toList(),
        ),
      ],
      const Note(icon: FontAwesomeIcons.circleExclamation, children: <InlineSpan>[
        TextSpan(
            text:
                'A cloud model sends your prompt and the file contents it reads to that provider. On-device models never do.'),
      ]),
    ];
  }

  Future<void> _providerSheet(LunaCore core, Map<String, dynamic> provider) async {
    await showLunaSheet<void>(
      context: context,
      title: '${provider['label']}',
      builder: (BuildContext sheetContext) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Group(children: <Widget>[
            LunaRow(
              icon: FontAwesomeIcons.arrowsRotate,
              title: 'Check models',
              subtitle: 'Ask ${provider['baseUrl']} what it serves today',
              onTap: () {
                Navigator.of(sheetContext).pop();
                _pickModel(core, provider);
              },
            ),
            LunaRow(
              icon: FontAwesomeIcons.trash,
              title: 'Remove this key',
              subtitle: 'The key is wiped from the keystore',
              onTap: () {
                Navigator.of(sheetContext).pop();
                _confirm(
                  title: 'Remove ${provider['label']}?',
                  body: 'The key is deleted from the keystore. Any job set to use it '
                      'will need another model.',
                  confirmLabel: 'Remove',
                  onConfirm: () => core.removeCloudProvider('${provider['id']}'),
                );
              },
            ),
          ]),
          const SizedBox(height: 8),
        ],
      ),
    );
  }

  /// The provider's own list, not one baked into the app.
  Future<void> _pickModel(LunaCore core, Map<String, dynamic> provider) async {
    List<String> models = <String>[];
    String? failure;
    try {
      models = await core.providerModels(id: '${provider['id']}');
    } catch (error) {
      failure = '$error';
    }
    if (!mounted) return;
    await showLunaSheet<void>(
      context: context,
      title: failure == null ? '${provider['label']} offers ${models.length}' : 'Could not ask',
      builder: (BuildContext sheetContext) {
        if (failure != null) {
          return Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 12),
            child: Text(failure,
                style: LunaTheme.text(size: 13, color: LunaTheme.ink2, height: 1.5)),
          );
        }
        return Group(
          children: models
              .map((String name) => LunaRow(
                    icon: FontAwesomeIcons.microchip,
                    title: name,
                    subtitle: name == '${provider['model']}' ? 'In use' : null,
                    trailing: name == '${provider['model']}'
                        ? null
                        : PillButton(
                            label: 'Pick',
                            small: true,
                            soft: true,
                            onTap: () async {
                              Navigator.of(sheetContext).pop();
                              await core.updateCloudProvider(
                                  id: '${provider['id']}', model: name);
                            },
                          ),
                  ))
              .toList(),
        );
      },
    );
  }

  Future<void> _keySheet(LunaCore core) async {
    final TextEditingController label = TextEditingController();
    final TextEditingController baseUrl =
        TextEditingController(text: 'https://api.openai.com/v1');
    final TextEditingController apiKey = TextEditingController();
    final TextEditingController model = TextEditingController();
    await showLunaSheet<void>(
      context: context,
      title: 'Add a key',
      builder: (BuildContext sheetContext) => StatefulBuilder(
        builder: (BuildContext inner, StateSetter refresh) => Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: <Widget>[
            LunaField(label: 'Name', controller: label, hint: 'Work key', autofocus: true),
            LunaField(label: 'Base address', controller: baseUrl, mono: true),
            LunaField(
                label: 'Key',
                controller: apiKey,
                mono: true,
                obscure: true,
                help: 'Kept in the device keystore. It never appears in a file.'),
            LunaField(
                label: 'Model',
                controller: model,
                mono: true,
                hint: 'Check the list rather than typing it'),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 2, 20, 8),
              child: Row(
                children: <Widget>[
                  PillButton(
                    label: 'Check models',
                    icon: FontAwesomeIcons.arrowsRotate,
                    soft: true,
                    onTap: () async {
                      List<String> found = <String>[];
                      String? failure;
                      try {
                        found = await core.providerModels(
                          baseUrl: baseUrl.text.trim(),
                          apiKey: apiKey.text.trim(),
                        );
                      } catch (error) {
                        failure = '$error';
                      }
                      if (!inner.mounted) return;
                      if (failure != null) {
                        ScaffoldMessenger.of(inner).showSnackBar(SnackBar(
                          backgroundColor: LunaTheme.ink,
                          content: Text(failure,
                              style: LunaTheme.text(size: 13, color: LunaTheme.onInk)),
                        ));
                        return;
                      }
                      if (found.isNotEmpty) {
                        refresh(() => model.text = found.first);
                      }
                      await showLunaSheet<void>(
                        context: inner,
                        title: 'It serves ${found.length}',
                        builder: (BuildContext listContext) => Group(
                          children: found
                              .map((String name) => LunaRow(
                                    icon: FontAwesomeIcons.microchip,
                                    title: name,
                                    onTap: () {
                                      refresh(() => model.text = name);
                                      Navigator.of(listContext).pop();
                                    },
                                  ))
                              .toList(),
                        ),
                      );
                    },
                  ),
                  const SizedBox(width: 8),
                  PillButton(
                    label: 'Save',
                    icon: FontAwesomeIcons.check,
                    onTap: () async {
                      Navigator.of(sheetContext).pop();
                      await core.addCloudProvider(
                        label: label.text.trim().isEmpty ? 'Cloud model' : label.text.trim(),
                        baseUrl: baseUrl.text.trim(),
                        apiKey: apiKey.text.trim(),
                        model: model.text.trim(),
                      );
                    },
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  /// Nothing that cannot be undone happens without one of these.
  Future<void> _confirm({
    required String title,
    required String body,
    required String confirmLabel,
    required Future<void> Function() onConfirm,
  }) async {
    await showLunaSheet<void>(
      context: context,
      title: title,
      builder: (BuildContext sheetContext) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 14),
            child: Text(body,
                style: LunaTheme.text(size: 13.5, color: LunaTheme.ink2, height: 1.5)),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 0, 20, 10),
            child: Row(
              children: <Widget>[
                PillButton(
                  label: 'Keep it',
                  soft: true,
                  onTap: () => Navigator.of(sheetContext).pop(),
                ),
                const SizedBox(width: 8),
                PillButton(
                  label: confirmLabel,
                  icon: FontAwesomeIcons.check,
                  onTap: () async {
                    Navigator.of(sheetContext).pop();
                    await onConfirm();
                  },
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
