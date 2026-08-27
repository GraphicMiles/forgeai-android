import 'package:flutter/material.dart';
import 'package:font_awesome_flutter/font_awesome_flutter.dart';

import '../core/luna_core.dart';
import '../theme.dart';
import '../widgets/common.dart';

/// Models — where the work actually gets done from.
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

  @override
  Widget build(BuildContext context) {
    final LunaCore core = widget.core;
    return Column(
      children: <Widget>[
        const ScreenTop(title: 'Models'),
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

    return <Widget>[
      if (active != null) _activeHero(core, active),
      if (active == null && installed.isNotEmpty)
        Note(icon: FontAwesomeIcons.circleInfo, children: const <InlineSpan>[
          TextSpan(text: 'Pick one of your downloaded models to make it the active one.'),
        ]),
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
        children: available.map((Map<String, dynamic> model) {
          final int minRam = ((model['minRamBytes'] as num?) ?? 0) ~/ (1024 * 1024);
          final bool tooBig = ramMb > 0 && minRam > ramMb;
          final bool downloading =
              core.download != null && core.download!['id'] == model['id'];
          return LunaRow(
            icon: FontAwesomeIcons.download,
            title: '${model['name']}',
            muted: tooBig,
            subtitle: tooBig
                ? 'Needs ${(minRam / 1024).toStringAsFixed(0)} GB of RAM — this device has ${(ramMb / 1024).toStringAsFixed(1)} GB'
                : '${model['params']} · ${formatBytes(model['sizeBytes'] as num?)}',
            trailing: downloading
                ? IconButtonSoft(
                    icon: FontAwesomeIcons.xmark,
                    label: 'Cancel download',
                    onTap: core.cancelDownload,
                  )
                : PillButton(
                    label: 'Get',
                    small: true,
                    soft: true,
                    enabled: !tooBig,
                    onTap: () => _download(core, model),
                  ),
            child: downloading ? _downloadProgress(core) : null,
          );
        }).toList(),
      ),
      _stats(core),
      Note(icon: FontAwesomeIcons.shieldHalved, children: const <InlineSpan>[
        TextSpan(
            text:
                'Downloaded models run entirely on this device. Nothing leaves it, and they keep working with the radio off.'),
      ]),
    ];
  }

  Widget _downloadProgress(LunaCore core) {
    final Map<String, dynamic> download = core.download!;
    final num completed = (download['completed'] as num?) ?? 0;
    final num total = (download['total'] as num?) ?? 0;
    final double fraction = total > 0 ? completed / total : 0;
    return Padding(
      padding: const EdgeInsets.only(top: 2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          ProgressBar(value: fraction),
          Padding(
            padding: const EdgeInsets.only(top: 5),
            child: Text(
              total > 0
                  ? '${formatBytes(completed)} of ${formatBytes(total)} · ${(fraction * 100).round()}%'
                  : 'Starting…',
              style: LunaTheme.text(size: 11.5, color: LunaTheme.ink3),
            ),
          ),
        ],
      ),
    );
  }

  Future<void> _download(LunaCore core, Map<String, dynamic> model) async {
    final String? failure = await core.downloadModel('${model['id']}');
    if (!mounted || failure == null || failure.isEmpty) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      backgroundColor: LunaTheme.ink,
      content: Text(failure, style: LunaTheme.text(size: 13, color: LunaTheme.onInk)),
    ));
  }

  /// The one filled-black surface on this screen: what is loaded right now.
  Widget _activeHero(LunaCore core, Map<String, dynamic> model) {
    return Container(
      margin: const EdgeInsets.fromLTRB(20, 14, 20, 0),
      padding: const EdgeInsets.fromLTRB(16, 15, 16, 14),
      decoration: const BoxDecoration(color: LunaTheme.ink, borderRadius: LunaTheme.rCard),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: <Widget>[
          Row(
            children: <Widget>[
              const Glyph(FontAwesomeIcons.bolt, size: 11, color: LunaTheme.onInkDim),
              const SizedBox(width: 7),
              Text('Running now',
                  style: LunaTheme.text(size: 11.5, weight: 600, color: LunaTheme.onInkDim)),
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
        decoration: const BoxDecoration(
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
        decoration: const BoxDecoration(color: LunaTheme.fill, borderRadius: LunaTheme.rNote),
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
                  core.deleteModel('${model['id']}');
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
    final TextEditingController endpoint = TextEditingController(text: core.endpoint);
    return <Widget>[
      const SizedBox(height: 12),
      LunaField(
        label: 'Ollama address',
        controller: endpoint,
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
                await core.setEndpoint(endpoint.text.trim());
                await _probeOllama(core);
              },
            ),
          ],
        ),
      ),
      Note(icon: FontAwesomeIcons.houseLaptop, children: const <InlineSpan>[
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
            child: Text(failure!,
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
              trailing: id == core.activeModelId
                  ? Text('Active',
                      style: LunaTheme.text(size: 12, weight: 600, color: LunaTheme.ink))
                  : PillButton(label: 'Use', small: true, onTap: () => core.useModel(id)),
              onTap: () => core.removeCloudProvider('${provider['id']}'),
            );
          }).toList(),
        ),
      ],
      Note(icon: FontAwesomeIcons.circleExclamation, children: const <InlineSpan>[
        TextSpan(
            text:
                'A cloud model sends your prompt and the file contents it reads to that provider. On-device models never do.'),
      ]),
    ];
  }

  Future<void> _keySheet(LunaCore core) async {
    final TextEditingController label = TextEditingController();
    final TextEditingController baseUrl =
        TextEditingController(text: 'https://api.openai.com/v1');
    final TextEditingController apiKey = TextEditingController();
    final TextEditingController model = TextEditingController(text: 'gpt-4o-mini');
    await showLunaSheet<void>(
      context: context,
      title: 'Add a key',
      builder: (BuildContext sheetContext) => Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: <Widget>[
          LunaField(label: 'Name', controller: label, hint: 'Work key', autofocus: true),
          LunaField(label: 'Base address', controller: baseUrl, mono: true),
          LunaField(label: 'Key', controller: apiKey, mono: true, help: 'Kept in the device keystore.'),
          LunaField(label: 'Model', controller: model, mono: true),
          Padding(
            padding: const EdgeInsets.fromLTRB(20, 2, 20, 8),
            child: PillButton(
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
          ),
        ],
      ),
    );
  }
}
