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

  /// Tool steps for the turn in flight.
  List<Map<String, String>> steps = <Map<String, String>>[];
  bool thinking = false;
  String streaming = '';
  double tokensPerSecond = 0;
  DateTime? runStartedAt;
  String? lastError;

  bool get unattended => executionMode == 'auto';

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
        pendingApproval = event;
        break;
      case 'speed':
        tokensPerSecond = ((event['tokensPerSecond'] as num?) ?? 0).toDouble();
        break;
      case 'download':
        download = event;
        if (event['status'] == 'done') {
          download = null;
          unawaited(refresh());
        }
        break;
      case 'run_done':
        running = false;
        thinking = false;
        streaming = '';
        pendingApproval = null;
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
    pendingApproval = null;
    thinking = true;
    notifyListeners();
    await _channel.invokeMethod<void>(
        'resolveApproval', <String, dynamic>{'id': id, 'approved': approved});
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

  Future<String?> downloadModel(String id) async {
    download = <String, dynamic>{'id': id, 'completed': 0, 'total': 0, 'status': 'downloading'};
    notifyListeners();
    final String? failure =
        await _channel.invokeMethod<String>('downloadModel', <String, dynamic>{'id': id});
    download = null;
    await refresh();
    return failure;
  }

  Future<void> cancelDownload() => _invoke('cancelDownload');

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
