import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// The Dart side of the seam. Java owns the device; this class owns nothing but
/// a mirror of it, kept in sync by one method channel and one event stream.
class LunaCore extends ChangeNotifier {
  LunaCore() {
    _events.receiveBroadcastStream().listen(_onEvent, onError: (Object _) {});
  }

  static const MethodChannel _channel = MethodChannel('ai.luna.app/core');
  static const EventChannel _events = EventChannel('ai.luna.app/events');

  // --- mirrored state -------------------------------------------------------

  bool ready = false;
  String executionMode = 'ask';
  bool workspaceGranted = false;
  String workspaceName = '';
  String activeModelId = '';
  String endpoint = '';
  bool failover = false;
  bool hasToken = false;
  bool running = false;
  String workspaceState = 'none';
  List<Map<String, dynamic>> grants = <Map<String, dynamic>>[];
  Map<String, String> toolRules = <String, String>{};
  bool wifiOnly = false;
  bool batteryGuard = true;
  bool keepWarm = true;
  String theme = 'system';
  double textScale = 1.0;
  bool walkthroughDone = false;
  int budgetSteps = 12;
  int budgetSeconds = 300;
  int budgetCloudCalls = 8;
  List<Map<String, dynamic>> importedModels = <Map<String, dynamic>>[];
  Map<String, dynamic> downloadState = <String, dynamic>{};
  List<Map<String, dynamic>> chats = <Map<String, dynamic>>[];
  String activeChatId = '';
  List<Map<String, dynamic>> errors = <Map<String, dynamic>>[];
  int errorCount = 0;
  Map<String, dynamic>? pendingQuestion;
  String? lastChecksum;
  int maxFileBytes = 2 * 1024 * 1024;
  List<String> readOnlyTools = <String>[];
  List<String> mutatingTools = <String>[];
  List<Map<String, dynamic>> catalog = <Map<String, dynamic>>[];
  List<Map<String, dynamic>> cloudProviders = <Map<String, dynamic>>[];
  List<Map<String, dynamic>> messages = <Map<String, dynamic>>[];
  Map<String, dynamic> device = <String, dynamic>{};
  Map<String, dynamic>? lastBackup;
  Map<String, dynamic>? pendingApproval;
  Map<String, dynamic>? download;

  /// A file another app just shared into the folder.
  String sharedFile = '';

  /// Tool steps for the turn in flight.
  List<Map<String, String>> steps = <Map<String, String>>[];
  bool thinking = false;
  String streaming = '';
  double tokensPerSecond = 0;
  DateTime? runStartedAt;

  /// Time spent waiting on you is not time spent working. It is measured
  /// separately so "Thought for 11.5s" means eleven and a half seconds of
  /// thinking, not eleven and a half seconds of a card sitting on screen.
  Duration runElapsed = Duration.zero;
  DateTime? _gateSince;
  int _gateMillis = 0;

  bool get waitingOnYou => pendingApproval != null || pendingQuestion != null;

  /// Work done in the current run, or the frozen total once it has finished.
  Duration get workElapsed {
    if (runStartedAt == null) return runElapsed;
    if (!running) return runElapsed;
    int millis = DateTime.now().difference(runStartedAt!).inMilliseconds - _gateMillis;
    if (_gateSince != null) {
      millis -= DateTime.now().difference(_gateSince!).inMilliseconds;
    }
    return Duration(milliseconds: millis < 0 ? 0 : millis);
  }

  void _openGate() {
    _gateSince ??= DateTime.now();
  }

  void _closeGate() {
    if (_gateSince == null) return;
    _gateMillis += DateTime.now().difference(_gateSince!).inMilliseconds;
    _gateSince = null;
  }
  String? lastError;

  bool get unattended => executionMode == 'auto';

  /// ask, always or never. A tool with no rule follows the global switch.
  String ruleFor(String tool) => toolRules[tool] ?? 'ask';

  bool get workspaceRevoked => workspaceState == 'revoked';

  Map<String, dynamic>? get activeCatalogModel {
    for (final Map<String, dynamic> model in catalog) {
      if (model['id'] == activeModelId) return model;
    }
    return null;
  }

  Map<String, dynamic>? get activeCloudProvider {
    if (!activeModelId.startsWith('cloud:')) return null;
    final String id = activeModelId.substring(6);
    for (final Map<String, dynamic> provider in cloudProviders) {
      if (provider['id'] == id) return provider;
    }
    return null;
  }

  String get activeModelName {
    final Map<String, dynamic>? local = activeCatalogModel;
    if (local != null) return local['name'] as String;
    final Map<String, dynamic>? cloud = activeCloudProvider;
    if (cloud != null) return cloud['label'] as String;
    return '';
  }

  // --- lifecycle ------------------------------------------------------------

  Future<void> load() async {
    try {
      final Map<Object?, Object?>? snapshot =
          await _channel.invokeMethod<Map<Object?, Object?>>('snapshot');
      if (snapshot != null) _applySnapshot(snapshot);
      device = await _map('deviceCapacity');
    } catch (error) {
      lastError = '$error';
    }
    ready = true;
    notifyListeners();
  }

  void _applySnapshot(Map<Object?, Object?> snapshot) {
    executionMode = (snapshot['executionMode'] as String?) ?? 'ask';
    workspaceGranted = (snapshot['workspaceGranted'] as bool?) ?? false;
    workspaceName = (snapshot['workspaceName'] as String?) ?? '';
    activeModelId = (snapshot['activeModelId'] as String?) ?? '';
    endpoint = (snapshot['endpoint'] as String?) ?? '';
    failover = (snapshot['failover'] as bool?) ?? false;
    hasToken = (snapshot['hasToken'] as bool?) ?? false;
    running = (snapshot['running'] as bool?) ?? false;
    maxFileBytes = (snapshot['maxFileBytes'] as int?) ?? maxFileBytes;
    readOnlyTools = _stringList(snapshot['readOnlyTools']);
    mutatingTools = _stringList(snapshot['mutatingTools']);
    catalog = _decodeList(snapshot['catalog'] as String?);
    cloudProviders = _decodeList(snapshot['cloudProviders'] as String?);
    messages = _decodeList(snapshot['messages'] as String?);
    final String? backup = snapshot['lastBackup'] as String?;
    lastBackup = backup == null ? null : jsonDecode(backup) as Map<String, dynamic>;
    workspaceState = (snapshot['workspaceState'] as String?) ?? 'none';
    grants = _decodeList(snapshot['grants'] as String?);
    toolRules = _decodeStringMap(snapshot['toolRules'] as String?);
    wifiOnly = (snapshot['wifiOnly'] as bool?) ?? false;
    batteryGuard = (snapshot['batteryGuard'] as bool?) ?? true;
    keepWarm = (snapshot['keepWarm'] as bool?) ?? true;
    theme = (snapshot['theme'] as String?) ?? 'system';
    textScale = ((snapshot['textScale'] as num?) ?? 1).toDouble();
    walkthroughDone = (snapshot['walkthroughDone'] as bool?) ?? false;
    budgetSteps = (snapshot['budgetSteps'] as int?) ?? budgetSteps;
    budgetSeconds = (snapshot['budgetSeconds'] as int?) ?? budgetSeconds;
    budgetCloudCalls = (snapshot['budgetCloudCalls'] as int?) ?? budgetCloudCalls;
    importedModels = _decodeList(snapshot['importedModels'] as String?);
    downloadState = _decodeMap(snapshot['downloadState'] as String?);
    chats = _decodeList(snapshot['chats'] as String?);
    activeChatId = (snapshot['activeChatId'] as String?) ?? '';
    errorCount = (snapshot['errorCount'] as int?) ?? 0;
  }

  Future<void> refresh() async {
    final Map<Object?, Object?>? snapshot =
        await _channel.invokeMethod<Map<Object?, Object?>>('snapshot');
    if (snapshot != null) _applySnapshot(snapshot);
    notifyListeners();
  }

  // --- events ---------------------------------------------------------------

  void _onEvent(dynamic raw) {
    if (raw is! String) return;
    final Map<String, dynamic> event = jsonDecode(raw) as Map<String, dynamic>;
    final String type = (event['type'] as String?) ?? '';
    switch (type) {
      case 'run_started':
        running = true;
        thinking = true;
        streaming = '';
        steps = <Map<String, String>>[];
        pendingApproval = null;
        runStartedAt = DateTime.now();
        runElapsed = Duration.zero;
        _gateMillis = 0;
        _gateSince = null;
        break;
      case 'thinking':
        thinking = true;
        streaming = '';
        break;
      case 'loading_model':
        thinking = true;
        steps.add(<String, String>{'tool': 'load_model', 'path': '', 'state': 'running'});
        break;
      case 'token':
        thinking = false;
        streaming += (event['text'] as String?) ?? '';
        break;
      case 'step':
        final String tool = (event['tool'] as String?) ?? '';
        final String state = (event['state'] as String?) ?? '';
        final int existing = steps.indexWhere((Map<String, String> step) =>
            step['tool'] == tool && step['path'] == (event['path'] as String? ?? ''));
        if (existing >= 0) {
          steps[existing]['state'] = state;
        } else {
          steps.add(<String, String>{
            'tool': tool,
            'path': (event['path'] as String?) ?? '',
            'state': state,
          });
        }
        break;
      case 'approval':
        thinking = false;
        _openGate();
        pendingApproval = event;
        break;
      case 'speed':
        tokensPerSecond = ((event['tokensPerSecond'] as num?) ?? 0).toDouble();
        break;
      case 'ask':
        thinking = false;
        _openGate();
        pendingQuestion = event;
        break;
      case 'download':
        final String status = (event['status'] as String?) ?? '';
        if (status.startsWith('checksum:')) {
          // The hash is shown, not just a pass or a fail.
          lastChecksum = status;
          break;
        }
        download = event;
        downloadState = Map<String, dynamic>.from(downloadState)
          ..[(event['id'] as String?) ?? ''] = event;
        if (status == 'done' || status == 'cancelled') {
          download = null;
          downloadState.remove((event['id'] as String?) ?? '');
          unawaited(refresh());
        }
        break;
      case 'import':
        download = <String, dynamic>{
          'id': 'import',
          'completed': event['completed'],
          'total': 0,
          'status': 'importing',
        };
        break;
      case 'shared':
        sharedFile = (event['name'] as String?) ?? '';
        unawaited(refresh());
        break;
      case 'run_done':
        _closeGate();
        runElapsed = workElapsed;
        running = false;
        thinking = false;
        streaming = '';
        pendingApproval = null;
        pendingQuestion = null;
        unawaited(_reloadMessages());
        break;
      default:
        break;
    }
    notifyListeners();
  }

  Future<void> _reloadMessages() async {
    final String? raw = await _channel.invokeMethod<String>('messages');
    messages = _decodeList(raw);
    notifyListeners();
  }

  // --- commands -------------------------------------------------------------

  Future<void> send(String text) async {
    if (text.trim().isEmpty || running) return;
    messages = List<Map<String, dynamic>>.from(messages)
      ..add(<String, dynamic>{
        'role': 'user',
        'content': text.trim(),
        'at': DateTime.now().millisecondsSinceEpoch,
      });
    running = true;
    thinking = true;
    notifyListeners();
    await _channel.invokeMethod<void>('sendMessage', <String, dynamic>{'text': text.trim()});
  }

  Future<void> stop() => _invoke('stopAgent');

  Future<void> approve(String id, bool approved) async {
    _closeGate();
    pendingApproval = null;
    thinking = true;
    notifyListeners();
    await _channel.invokeMethod<void>(
        'resolveApproval', <String, dynamic>{'id': id, 'approved': approved});
  }

  /// Answer an ask_user question. An empty answer means "carry on without me".
  Future<void> answerQuestion(String id, String text) async {
    _closeGate();
    pendingQuestion = null;
    thinking = true;
    notifyListeners();
    await _channel.invokeMethod<void>('answerQuestion', <String, dynamic>{'id': id, 'text': text});
  }

  // --- more than one chat ---------------------------------------------------

  Future<void> loadChats() async {
    chats = _decodeList(await _channel.invokeMethod<String>('chats'));
    notifyListeners();
  }

  Future<List<Map<String, dynamic>>> searchChats(String query) async {
    return _decodeList(
        await _channel.invokeMethod<String>('searchChats', <String, dynamic>{'query': query}));
  }

  Future<void> switchChat(String id) async {
    final String? raw = await _channel.invokeMethod<String>('switchChat', <String, dynamic>{'id': id});
    messages = _decodeList(raw);
    steps = <Map<String, String>>[];
    streaming = '';
    await refresh();
  }

  Future<void> deleteChat(String id) async {
    chats = _decodeList(
        await _channel.invokeMethod<String>('deleteChat', <String, dynamic>{'id': id}));
    await refresh();
  }

  Future<String> exportChat() async {
    return await _channel.invokeMethod<String>('exportChat') ?? '';
  }

  Future<void> startNewChat() async {
    await _channel.invokeMethod<String>('newChat');
    messages = <Map<String, dynamic>>[];
    steps = <Map<String, String>>[];
    streaming = '';
    await refresh();
  }

  Future<void> newChat() async {
    await _invoke('clearChat');
    messages = <Map<String, dynamic>>[];
    steps = <Map<String, String>>[];
    streaming = '';
    notifyListeners();
  }

  Future<bool> pickFolder() async {
    final List<Object?>? answer = await _channel.invokeMethod<List<Object?>>('pickFolder');
    if (answer == null) return false;
    workspaceGranted = true;
    workspaceName = answer.length > 1 ? (answer[1] as String? ?? '') : '';
    notifyListeners();
    return true;
  }

  Future<List<Map<String, dynamic>>> listFolder(String path) async {
    final String? raw = await _channel.invokeMethod<String>('listFolder', <String, dynamic>{'path': path});
    return _decodeList(raw);
  }

  Future<String> readFile(String path) async {
    return await _channel.invokeMethod<String>('readFile', <String, dynamic>{'path': path}) ?? '';
  }

  Future<void> writeFile(String path, String content) =>
      _invoke('writeFile', <String, dynamic>{'path': path, 'content': content});

  Future<void> createFile(String path) => _invoke('createFile', <String, dynamic>{'path': path});

  Future<void> createFolder(String path) => _invoke('createFolder', <String, dynamic>{'path': path});

  Future<void> renameFile(String path, String newName) =>
      _invoke('renameFile', <String, dynamic>{'path': path, 'newName': newName});

  Future<void> deleteFile(String path) => _invoke('deleteFile', <String, dynamic>{'path': path});

  Future<String> undo() async {
    final String path = await _channel.invokeMethod<String>('undo') ?? '';
    await refresh();
    return path;
  }

  /// Starts the service. The download outlives this screen, and the app.
  Future<String?> downloadModel(String id) async {
    download = <String, dynamic>{'id': id, 'completed': 0, 'total': 0, 'status': 'downloading'};
    downloadState = Map<String, dynamic>.from(downloadState)..[id] = download!;
    notifyListeners();
    await _invoke('downloadModel', <String, dynamic>{'id': id});
    return null;
  }

  Future<void> pauseDownload(String id) async {
    await _invoke('pauseDownload', <String, dynamic>{'id': id});
    final Map<String, dynamic> entry =
        Map<String, dynamic>.from(downloadState[id] as Map<String, dynamic>? ?? <String, dynamic>{});
    entry['status'] = 'paused';
    downloadState = Map<String, dynamic>.from(downloadState)..[id] = entry;
    download = entry;
    notifyListeners();
  }

  Future<void> resumeDownload(String id) async {
    await _invoke('resumeDownload', <String, dynamic>{'id': id});
  }

  Future<void> cancelDownload([String id = '']) async {
    await _invoke('cancelDownload', <String, dynamic>{'id': id});
    downloadState = Map<String, dynamic>.from(downloadState)..remove(id);
    download = null;
    notifyListeners();
  }

  /// Pick a .gguf off the phone. Returns the imported model, or null if cancelled.
  Future<Map<String, dynamic>?> importModel() async {
    final String? raw = await _channel.invokeMethod<String>('importModel');
    await refresh();
    if (raw == null || raw.isEmpty) return null;
    return jsonDecode(raw) as Map<String, dynamic>;
  }

  Future<void> deleteImportedModel(String id) async {
    await _invoke('deleteImportedModel', <String, dynamic>{'id': id});
    await refresh();
  }

  /// Copy a file from elsewhere on the phone into the granted folder.
  Future<String?> bringInFile() async {
    final String? name = await _channel.invokeMethod<String>('bringInFile');
    await refresh();
    return name;
  }

  Future<void> deleteModel(String id) async {
    await _invoke('deleteModel', <String, dynamic>{'id': id});
    await refresh();
  }

  Future<void> useModel(String id) async {
    activeModelId = id;
    notifyListeners();
    await _invoke('setActiveModel', <String, dynamic>{'id': id});
  }

  Future<void> setMode(String mode) async {
    executionMode = mode;
    notifyListeners();
    await _invoke('setExecutionMode', <String, dynamic>{'mode': mode});
  }

  Future<void> setEndpoint(String value) async {
    endpoint = value;
    await _invoke('setEndpoint', <String, dynamic>{'endpoint': value});
  }

  Future<void> setFailover(bool enabled) async {
    failover = enabled;
    notifyListeners();
    await _invoke('setFailover', <String, dynamic>{'enabled': enabled});
  }

  Future<void> addCloudProvider({
    required String label,
    required String baseUrl,
    required String apiKey,
    required String model,
  }) async {
    await _invoke('addCloudProvider', <String, dynamic>{
      'label': label,
      'baseUrl': baseUrl,
      'apiKey': apiKey,
      'model': model,
    });
    await refresh();
  }

  Future<void> updateCloudProvider({
    required String id,
    String label = '',
    String model = '',
    String apiKey = '',
  }) async {
    await _invoke('updateCloudProvider', <String, dynamic>{
      'id': id,
      'label': label,
      'model': model,
      'apiKey': apiKey,
    });
    await refresh();
  }

  /// What the provider says it serves today. Throws with the provider's own words.
  Future<List<String>> providerModels({
    String id = '',
    String baseUrl = '',
    String apiKey = '',
  }) async {
    final String? raw = await _channel.invokeMethod<String>('providerModels', <String, dynamic>{
      'id': id,
      'baseUrl': baseUrl,
      'apiKey': apiKey,
    });
    if (raw == null || raw.isEmpty) return <String>[];
    final Object? parsed = jsonDecode(raw);
    if (parsed is! List) return <String>[];
    return parsed.map((Object? item) => '$item').toList();
  }

  Future<void> removeCloudProvider(String id) async {
    await _invoke('removeCloudProvider', <String, dynamic>{'id': id});
    await refresh();
  }

  Future<List<Map<String, dynamic>>> ollamaModels() async {
    final String? raw = await _channel.invokeMethod<String>('ollamaModels');
    return _decodeList(raw);
  }

  Future<void> storeToken(String token) async {
    await _invoke('storeToken', <String, dynamic>{'token': token});
    hasToken = true;
    notifyListeners();
  }

  Future<void> clearToken() async {
    await _invoke('clearToken');
    hasToken = false;
    notifyListeners();
  }

  // --- rules, limits and comfort ---------------------------------------------

  Future<void> setToolRule(String tool, String rule) async {
    toolRules = Map<String, String>.from(toolRules);
    if (rule == 'ask') {
      toolRules.remove(tool);
    } else {
      toolRules[tool] = rule;
    }
    notifyListeners();
    await _invoke('setToolRule', <String, dynamic>{'tool': tool, 'rule': rule});
  }

  Future<void> cycleToolRule(String tool) {
    const List<String> order = <String>['ask', 'always', 'never'];
    final int next = (order.indexOf(ruleFor(tool)) + 1) % order.length;
    return setToolRule(tool, order[next]);
  }

  Future<void> setBudget({int? steps, int? seconds, int? cloudCalls}) async {
    budgetSteps = steps ?? budgetSteps;
    budgetSeconds = seconds ?? budgetSeconds;
    budgetCloudCalls = cloudCalls ?? budgetCloudCalls;
    notifyListeners();
    await _invoke('setBudget', <String, dynamic>{
      'steps': budgetSteps,
      'seconds': budgetSeconds,
      'cloudCalls': budgetCloudCalls,
    });
  }

  Future<void> setWifiOnly(bool value) async {
    wifiOnly = value;
    notifyListeners();
    await _invoke('setWifiOnly', <String, dynamic>{'enabled': value});
  }

  Future<void> setBatteryGuard(bool value) async {
    batteryGuard = value;
    notifyListeners();
    await _invoke('setBatteryGuard', <String, dynamic>{'enabled': value});
  }

  Future<void> setKeepWarm(bool value) async {
    keepWarm = value;
    notifyListeners();
    await _invoke('setKeepWarm', <String, dynamic>{'enabled': value});
  }

  Future<void> setTheme(String value) async {
    theme = value;
    notifyListeners();
    await _invoke('setTheme', <String, dynamic>{'theme': value});
  }

  Future<void> setTextScale(double value) async {
    textScale = value;
    notifyListeners();
    await _invoke('setTextScale', <String, dynamic>{'scale': value});
  }

  Future<void> finishWalkthrough() async {
    walkthroughDone = true;
    notifyListeners();
    await _invoke('setWalkthroughDone');
  }

  // --- folders ----------------------------------------------------------------

  Future<void> useGrant(String uri) async {
    await _invoke('useGrant', <String, dynamic>{'uri': uri});
    await refresh();
  }

  Future<void> forgetGrant(String uri) async {
    await _invoke('forgetGrant', <String, dynamic>{'uri': uri});
    await refresh();
  }

  // --- what went wrong ---------------------------------------------------------

  Future<List<Map<String, dynamic>>> loadErrors() async {
    errors = _decodeList(await _channel.invokeMethod<String>('errors'));
    errorCount = errors.length;
    notifyListeners();
    return errors;
  }

  Future<void> clearErrors() async {
    await _invoke('clearErrors');
    errors = <Map<String, dynamic>>[];
    errorCount = 0;
    notifyListeners();
  }

  // --- backup -------------------------------------------------------------------

  Future<String> exportSettings() async {
    return await _channel.invokeMethod<String>('exportSettings') ?? '{}';
  }

  Future<bool> restoreSettings() async {
    final String? answer = await _channel.invokeMethod<String>('restoreSettings');
    await refresh();
    return answer == 'restored';
  }

  Future<void> resetAll() async {
    await _invoke('resetAll');
    await load();
  }

  // --- plumbing -------------------------------------------------------------

  Future<void> _invoke(String method, [Map<String, dynamic>? args]) async {
    try {
      await _channel.invokeMethod<void>(method, args);
      lastError = null;
    } on PlatformException catch (error) {
      lastError = error.message ?? error.code;
      notifyListeners();
      rethrow;
    }
  }

  Future<Map<String, dynamic>> _map(String method) async {
    final Map<Object?, Object?>? raw = await _channel.invokeMethod<Map<Object?, Object?>>(method);
    if (raw == null) return <String, dynamic>{};
    return raw.map((Object? key, Object? value) => MapEntry<String, dynamic>('$key', value));
  }

  static Map<String, dynamic> _decodeMap(String? raw) {
    if (raw == null || raw.isEmpty) return <String, dynamic>{};
    final Object? parsed = jsonDecode(raw);
    if (parsed is! Map) return <String, dynamic>{};
    return parsed.map((Object? key, Object? value) => MapEntry<String, dynamic>('$key', value));
  }

  static Map<String, String> _decodeStringMap(String? raw) {
    return _decodeMap(raw).map((String key, dynamic value) => MapEntry<String, String>(key, '$value'));
  }

  static List<String> _stringList(Object? raw) {
    if (raw is List) return raw.map((Object? item) => '$item').toList();
    return <String>[];
  }

  static List<Map<String, dynamic>> _decodeList(String? raw) {
    if (raw == null || raw.isEmpty) return <Map<String, dynamic>>[];
    final Object? parsed = jsonDecode(raw);
    if (parsed is! List) return <Map<String, dynamic>>[];
    return parsed
        .whereType<Map<Object?, Object?>>()
        .map((Map<Object?, Object?> item) =>
            item.map((Object? key, Object? value) => MapEntry<String, dynamic>('$key', value)))
        .toList();
  }
}

/// Human byte sizes. One decimal only where it earns its place.
String formatBytes(num? value) {
  final double n = (value ?? 0).toDouble();
  if (n <= 0) return '—';
  if (n < 1024) return '${n.toInt()} B';
  if (n < 1024 * 1024) return '${(n / 1024).round()} KB';
  if (n < 1024 * 1024 * 1024) {
    final double mb = n / (1024 * 1024);
    return '${mb < 10 ? mb.toStringAsFixed(1) : mb.round()} MB';
  }
  return '${(n / (1024 * 1024 * 1024)).toStringAsFixed(1)} GB';
}

String formatClock(int? millis) {
  if (millis == null || millis == 0) return '';
  final DateTime time = DateTime.fromMillisecondsSinceEpoch(millis);
  final String hour = time.hour.toString().padLeft(2, '0');
  final String minute = time.minute.toString().padLeft(2, '0');
  return '$hour:$minute';
}

String formatElapsed(DateTime? from) {
  if (from == null) return '';
  final int seconds = DateTime.now().difference(from).inSeconds;
  if (seconds < 60) return '${seconds}s';
  return '${seconds ~/ 60}m ${seconds % 60}s';
}
