import 'package:font_awesome_flutter/font_awesome_flutter.dart';

/// A provider Luna already knows the shape of.
///
/// A preset is a starting point, never a cage: it fills in the wire format and
/// the address, and every one of those fields stays editable afterwards. That
/// is the difference between "we support OpenAI" and "we support your key".
class ProviderPreset {
  const ProviderPreset({
    required this.id,
    required this.name,
    required this.kind,
    required this.baseUrl,
    required this.note,
    this.icon = FontAwesomeIcons.cloud,
    this.keyHint = '',
  });

  final String id;
  final String name;

  /// openai, anthropic or gemini — the shape of the request on the wire.
  final String kind;
  final String baseUrl;

  /// One line about what this is, shown under the name.
  final String note;
  final FaIconData icon;
  final String keyHint;

  static const ProviderPreset custom = ProviderPreset(
    id: 'custom',
    name: 'Something else',
    kind: 'openai',
    baseUrl: '',
    note: 'Any endpoint. Pick the shape yourself',
    icon: FontAwesomeIcons.sliders,
  );

  static const List<ProviderPreset> all = <ProviderPreset>[
    ProviderPreset(
      id: 'openai',
      name: 'OpenAI',
      kind: 'openai',
      baseUrl: 'https://api.openai.com/v1',
      note: 'GPT models',
      icon: FontAwesomeIcons.brain,
      keyHint: 'sk-…',
    ),
    ProviderPreset(
      id: 'anthropic',
      name: 'Anthropic',
      kind: 'anthropic',
      baseUrl: 'https://api.anthropic.com/v1',
      note: 'Claude models',
      icon: FontAwesomeIcons.asterisk,
      keyHint: 'sk-ant-…',
    ),
    ProviderPreset(
      id: 'gemini',
      name: 'Google Gemini',
      kind: 'gemini',
      baseUrl: 'https://generativelanguage.googleapis.com/v1beta',
      note: 'Gemini models, free tier available',
      icon: FontAwesomeIcons.google,
      keyHint: 'AIza…',
    ),
    ProviderPreset(
      id: 'groq',
      name: 'Groq',
      kind: 'openai',
      baseUrl: 'https://api.groq.com/openai/v1',
      note: 'Very fast, free tier available',
      icon: FontAwesomeIcons.bolt,
      keyHint: 'gsk_…',
    ),
    ProviderPreset(
      id: 'openrouter',
      name: 'OpenRouter',
      kind: 'openai',
      baseUrl: 'https://openrouter.ai/api/v1',
      note: 'One key, most models, some free',
      icon: FontAwesomeIcons.shuffle,
      keyHint: 'sk-or-…',
    ),
    ProviderPreset(
      id: 'deepseek',
      name: 'DeepSeek',
      kind: 'openai',
      baseUrl: 'https://api.deepseek.com/v1',
      note: 'Cheap and strong at code',
      icon: FontAwesomeIcons.droplet,
    ),
    ProviderPreset(
      id: 'mistral',
      name: 'Mistral',
      kind: 'openai',
      baseUrl: 'https://api.mistral.ai/v1',
      note: 'European, small and fast models',
      icon: FontAwesomeIcons.wind,
    ),
    ProviderPreset(
      id: 'together',
      name: 'Together',
      kind: 'openai',
      baseUrl: 'https://api.together.xyz/v1',
      note: 'Open-weight models, hosted',
      icon: FontAwesomeIcons.users,
    ),
    ProviderPreset(
      id: 'xai',
      name: 'xAI',
      kind: 'openai',
      baseUrl: 'https://api.x.ai/v1',
      note: 'Grok models',
      icon: FontAwesomeIcons.rocket,
    ),
    ProviderPreset(
      id: 'lmstudio',
      name: 'LM Studio or llama.cpp',
      kind: 'openai',
      baseUrl: 'http://192.168.1.10:1234/v1',
      note: 'A server on your own network. No key needed',
      icon: FontAwesomeIcons.house,
    ),
    custom,
  ];

  static ProviderPreset byId(String id) {
    for (final ProviderPreset preset in all) {
      if (preset.id == id) return preset;
    }
    return custom;
  }

  /// The preset a saved row came from, matched on its address, so reopening a
  /// provider shows what it is rather than "Something else".
  static ProviderPreset match(String kind, String baseUrl) {
    final String address = baseUrl.trim().replaceAll(RegExp(r'/+$'), '');
    for (final ProviderPreset preset in all) {
      if (preset.baseUrl.isNotEmpty && preset.baseUrl == address) return preset;
    }
    for (final ProviderPreset preset in all) {
      if (preset.kind == kind && preset.kind != 'openai') return preset;
    }
    return custom;
  }
}

/// The three request shapes, in the order the toggle shows them.
const List<String> kProviderKinds = <String>['openai', 'anthropic', 'gemini'];

const List<String> kProviderKindNames = <String>['OpenAI style', 'Anthropic', 'Gemini'];

/// How the key is attached to the request.
const List<String> kAuthStyles = <String>['bearer', 'header', 'query', 'none'];

const List<String> kAuthStyleNames = <String>['Bearer', 'Header', 'In the URL', 'No key'];

String defaultAuthStyle(String kind) {
  if (kind == 'anthropic') return 'header';
  if (kind == 'gemini') return 'query';
  return 'bearer';
}

String defaultAuthName(String kind, String style) {
  if (style == 'query') return 'key';
  if (style == 'header') return kind == 'anthropic' ? 'x-api-key' : 'Authorization';
  return 'Authorization';
}

/// "Name: value" per line, which is how a person writes a header down.
Map<String, String> parseHeaders(String text) {
  final Map<String, String> out = <String, String>{};
  for (final String line in text.split('\n')) {
    final int split = line.indexOf(':');
    if (split <= 0) continue;
    final String name = line.substring(0, split).trim();
    final String value = line.substring(split + 1).trim();
    if (name.isNotEmpty && value.isNotEmpty) out[name] = value;
  }
  return out;
}

String writeHeaders(Map<String, dynamic> headers) {
  final List<String> lines = <String>[];
  headers.forEach((String name, dynamic value) => lines.add('$name: $value'));
  return lines.join('\n');
}

/// The shape's name for the Advanced summary line.
String kindName(String kind) {
  final int index = kProviderKinds.indexOf(kind);
  return index < 0 ? kProviderKindNames.first : kProviderKindNames[index];
}

String authStyleName(String style) {
  final int index = kAuthStyles.indexOf(style);
  return index < 0 ? kAuthStyleNames.first : kAuthStyleNames[index];
}

/// Which segment is lit. A shape or style the app does not know falls back to
/// the first, rather than to a broken -1.
int kindIndex(String kind) =>
    kProviderKinds.contains(kind) ? kProviderKinds.indexOf(kind) : 0;

int authStyleIndex(String style) =>
    kAuthStyles.contains(style) ? kAuthStyles.indexOf(style) : 0;
