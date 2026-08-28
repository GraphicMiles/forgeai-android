package ai.luna.app;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.flutter.plugin.common.BinaryMessenger;
import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

/**
 * The single seam between Dart and Java.
 *
 * Dart calls methods and listens to one event stream. Nothing on this side
 * blocks the platform thread: every call that touches storage, the network or
 * the model runs on a worker and answers on the main looper.
 */
public final class LunaBridge implements MethodChannel.MethodCallHandler, EventChannel.StreamHandler {

    private static final String METHOD_CHANNEL = "ai.luna.app/core";
    private static final String EVENT_CHANNEL = "ai.luna.app/events";
    private static final int REQUEST_PICK_FOLDER = 4301;
    private static final int REQUEST_IMPORT_GGUF = 4302;
    private static final int REQUEST_BRING_IN = 4303;
    private static final int REQUEST_RESTORE = 4304;

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);

    private final Prefs prefs;
    private final WorkspaceStore workspace;
    private final ModelStore models;
    private final OnDeviceRuntime runtime;
    private final CredentialVault vault;
    private final ErrorLog errors;
    private final HeadlessBrowser browser;
    private final AgentEngine agent;

    private final MethodChannel methodChannel;
    private final EventChannel eventChannel;

    private EventChannel.EventSink sink;
    private MethodChannel.Result pendingFolderPick;
    private MethodChannel.Result pendingFilePick;

    public LunaBridge(Activity activity, BinaryMessenger messenger) {
        this.activity = activity;
        this.prefs = new Prefs(activity);
        // Whatever was mid-flight when the process died is not mid-flight now.
        this.prefs.settleDownloadsAfterRestart();
        this.workspace = new WorkspaceStore(activity, prefs);
        this.models = new ModelStore(activity);
        this.runtime = new OnDeviceRuntime();
        this.vault = new CredentialVault(activity);
        this.errors = new ErrorLog(activity);
        // Every line the app writes about itself also goes up the event stream,
        // so the debug panel fills in while the job is still running.
        this.errors.listen(new ErrorLog.Listener() {
            @Override
            public void onLine(JSONObject line) {
                JSONObject event = new JSONObject();
                try {
                    event.put("type", "log");
                    event.put("line", line);
                } catch (JSONException ignored) {
                    return;
                }
                push(event);
            }
        });
        this.browser = new HeadlessBrowser(activity, errors);
        this.agent = new AgentEngine(activity, prefs, workspace, models, runtime, vault, errors, browser,
            new AgentEngine.Events() {
                @Override
                public void emit(JSONObject event) {
                    push(event);
                }
            });

        // Downloads run in a service so they outlive this screen. It reports
        // back through here whenever the app happens to be open.
        DownloadService.bind(models, prefs, errors, new DownloadService.Listener() {
            @Override
            public void onDownloadEvent(JSONObject event) {
                push(event);
            }
        });

        this.methodChannel = new MethodChannel(messenger, METHOD_CHANNEL);
        this.methodChannel.setMethodCallHandler(this);
        this.eventChannel = new EventChannel(messenger, EVENT_CHANNEL);
        this.eventChannel.setStreamHandler(this);
    }

    public void dispose() {
        DownloadService.unbindListener();
        browser.close();
        methodChannel.setMethodCallHandler(null);
        eventChannel.setStreamHandler(null);
        worker.shutdownNow();
    }

    // --- events --------------------------------------------------------------

    @Override
    public void onListen(Object arguments, EventChannel.EventSink eventSink) {
        this.sink = eventSink;
    }

    @Override
    public void onCancel(Object arguments) {
        this.sink = null;
    }

    private void push(final JSONObject event) {
        traceEvent(event);
        main.post(new Runnable() {
            @Override
            public void run() {
                EventChannel.EventSink target = sink;
                if (target != null) {
                    target.success(event.toString());
                }
            }
        });
    }

    /**
     * The engine's own events, written into the log as one line each. Tokens are
     * skipped: a hundred lines saying "e" tells nobody anything, and log lines
     * about log lines would never end.
     */
    private void traceEvent(JSONObject event) {
        String type = event.optString("type", "");
        if (type.isEmpty() || "log".equals(type) || "token".equals(type) || "heartbeat".equals(type)) {
            return;
        }
        StringBuilder detail = new StringBuilder(type);
        String tool = event.optString("tool", "");
        String state = event.optString("state", "");
        String path = event.optString("path", "");
        String reason = event.optString("reason", event.optString("error", ""));
        if (!tool.isEmpty()) {
            detail.append("  ").append(tool);
        }
        if (!state.isEmpty()) {
            detail.append("  ").append(state);
        }
        if (!path.isEmpty()) {
            detail.append("  ").append(path);
        }
        if (!reason.isEmpty()) {
            detail.append("  ").append(reason);
        }
        boolean bad = !event.optString("error", "").isEmpty()
            || "failed".equals(state) || "blocked".equals(state) || "unfinished".equals(state);
        if (bad) {
            errors.warn("engine", detail.toString());
        } else {
            errors.note("engine", detail.toString());
        }
    }

    // --- methods -------------------------------------------------------------

    @Override
    public void onMethodCall(@NonNull final MethodCall call, @NonNull final MethodChannel.Result result) {
        // The method name only. Arguments carry keys and file contents, and a
        // debug panel that shows a key is a leak wearing a helpful face.
        if (!"snapshot".equals(call.method) && !"debugLog".equals(call.method)) {
            errors.note("call", call.method);
        }
        switch (call.method) {
            // Anything that only reads memory answers straight away.
            case "snapshot":
                result.success(snapshot());
                return;
            case "pickFolder":
                pendingFolderPick = result;
                activity.startActivityForResult(WorkspaceStore.pickFolderIntent(), REQUEST_PICK_FOLDER);
                return;
            case "setExecutionMode":
                prefs.setExecutionMode(call.argument("mode") == null ? Prefs.MODE_ASK : (String) call.argument("mode"));
                result.success(prefs.executionMode());
                return;
            case "setEndpoint":
                prefs.setEndpoint((String) call.argument("endpoint"));
                result.success(prefs.endpoint());
                return;
            case "setFailover":
                prefs.setFailoverEnabled(Boolean.TRUE.equals(call.argument("enabled")));
                result.success(prefs.failoverEnabled());
                return;
            case "setActiveModel":
                prefs.setActiveModelId((String) call.argument("id"));
                result.success(prefs.activeModelId());
                return;
            case "sendMessage":
                agent.send((String) call.argument("text"));
                result.success(null);
                return;
            case "answerQuestion":
                agent.answerQuestion(String.valueOf(call.argument("id")), (String) call.argument("text"));
                result.success(null);
                return;
            case "setBudget":
                prefs.setBudget(intArg(call, "steps", prefs.budgetSteps()),
                    intArg(call, "seconds", prefs.budgetSeconds()),
                    intArg(call, "cloudCalls", prefs.budgetCloudCalls()));
                result.success(null);
                return;
            case "setWifiOnly":
                prefs.setWifiOnly(Boolean.TRUE.equals(call.argument("enabled")));
                result.success(prefs.wifiOnly());
                return;
            case "setBatteryGuard":
                prefs.setBatteryGuard(Boolean.TRUE.equals(call.argument("enabled")));
                result.success(prefs.batteryGuard());
                return;
            case "setKeepWarm":
                prefs.setKeepWarm(Boolean.TRUE.equals(call.argument("enabled")));
                if (!prefs.keepWarm()) {
                    runtime.unload();
                }
                result.success(prefs.keepWarm());
                return;
            case "setTheme":
                prefs.setTheme(argString(call, "theme"));
                result.success(prefs.theme());
                return;
            case "setTextScale":
                prefs.setTextScale((float) doubleArg(call, "scale", 1.0));
                result.success((double) prefs.textScale());
                return;
            case "setWalkthroughDone":
                prefs.setWalkthroughDone(true);
                result.success(true);
                return;
            case "errors":
                result.success(errors.entries().toString());
                return;
            case "debugLog":
                result.success(errors.lines().toString());
                return;
            case "clearDebugLog":
                errors.clear();
                result.success(null);
                return;
            case "clearErrors":
                errors.clear();
                result.success(null);
                return;
            case "chats":
                result.success(agent.chatIndex().toString());
                return;
            case "searchChats":
                result.success(agent.searchChats(argString(call, "query")).toString());
                return;
            case "newChat":
                agent.newChat();
                result.success(agent.activeChatId());
                return;
            case "switchChat":
                agent.switchChat(argString(call, "id"));
                result.success(agent.messages().toString());
                return;
            case "deleteChat":
                agent.deleteChat(argString(call, "id"));
                result.success(agent.chatIndex().toString());
                return;
            case "workspaceState":
                result.success(workspace.rootState());
                return;
            case "grants":
                result.success(prefs.grants().toString());
                return;
            case "useGrant":
                result.success(workspace.useGrant(argString(call, "uri")));
                return;
            case "forgetGrant":
                prefs.forgetGrant(argString(call, "uri"));
                result.success(prefs.grants().toString());
                return;
            case "pauseDownload":
                DownloadService.pause(activity, argString(call, "id"));
                result.success(null);
                return;
            case "resumeDownload":
                DownloadService.start(activity, argString(call, "id"));
                result.success(null);
                return;
            case "downloadState":
                result.success(prefs.downloadState().toString());
                return;
            case "importModel":
                pendingFilePick = result;
                activity.startActivityForResult(pickFileIntent("*/*"), REQUEST_IMPORT_GGUF);
                return;
            case "bringInFile":
                pendingFilePick = result;
                activity.startActivityForResult(pickFileIntent("*/*"), REQUEST_BRING_IN);
                return;
            case "restoreSettings":
                pendingFilePick = result;
                activity.startActivityForResult(pickFileIntent("application/json"), REQUEST_RESTORE);
                return;
            case "resolveApproval":
                agent.resolveApproval(String.valueOf(call.argument("id")), Boolean.TRUE.equals(call.argument("approved")));
                result.success(null);
                return;
            case "resumeRun":
                agent.resume();
                result.success(null);
                return;
            case "stopAgent":
                agent.stop();
                result.success(null);
                return;
            case "clearChat":
                agent.clear();
                result.success(null);
                return;
            case "messages":
                result.success(agent.messages().toString());
                return;
            case "cancelDownload":
                DownloadService.cancel(activity, argString(call, "id"));
                models.cancelDownload();
                result.success(null);
                return;
            case "hasToken":
                result.success(vault.has("github"));
                return;
            case "resetAll":
                agent.clear();
                prefs.clearAll();
                vault.clear("github");
                errors.clear();
                result.success(null);
                return;
            default:
                break;
        }

        // Everything below can block: hand it to a worker.
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    Object value = handleBlocking(call);
                    reply(result, value, null, null);
                } catch (Exception error) {
                    String message = error.getMessage();
                    errors.record(call.method, error);
                    reply(result, null, "luna_error", message == null ? error.toString() : message);
                }
            }
        });
    }

    private Object handleBlocking(MethodCall call) throws Exception {
        switch (call.method) {
            case "deviceCapacity":
                return DeviceCapacity.read(activity);
            case "listFolder":
                return workspace.list(argString(call, "path")).toString();
            case "readFile":
                return workspace.readText(argString(call, "path"));
            case "writeFile":
                workspace.writeText(argString(call, "path"), argString(call, "content"));
                return null;
            case "createFile":
                workspace.createFile(argString(call, "path"));
                return null;
            case "createFolder":
                workspace.createFolder(argString(call, "path"));
                return null;
            case "renameFile":
                workspace.rename(argString(call, "path"), argString(call, "newName"));
                return null;
            case "deleteFile":
                workspace.delete(argString(call, "path"));
                return null;
            case "undo":
                return workspace.undo();
            case "catalog":
                return models.catalog().toString();
            case "downloadModel":
                DownloadService.start(activity, argString(call, "id"));
                return null;
            case "deleteModel":
                return models.delete(argString(call, "id"));
            case "unloadModel":
                runtime.unload();
                return null;
            case "ollamaModels":
                return CloudProvider.ollamaModels(prefs.endpoint()).toString();
            case "addCloudProvider":
                return addCloudProvider(call);
            case "removeCloudProvider": {
                String id = argString(call, "id");
                prefs.removeCloudProvider(id);
                vault.clear("cloud:" + id);
                return prefs.cloudProviders(vault).toString();
            }
            case "updateCloudProvider":
                return updateCloudProvider(call);
            case "checkEndpoint":
                return EndpointPolicy.reason(argString(call, "baseUrl"));
            case "providerModels":
                return providerModels(call);
            case "probeModel":
                return probeModel(call);
            case "exportChat":
                return agent.exportChat();
            case "exportSettings":
                return prefs.exportSettings().toString();
            case "deleteImportedModel": {
                JSONObject imported = prefs.importedModel(argString(call, "id"));
                if (imported != null) {
                    File file = new File(imported.optString("file"));
                    if (file.exists()) {
                        file.delete();
                    }
                }
                prefs.removeImportedModel(argString(call, "id"));
                return prefs.importedModels().toString();
            }
            case "importedModels":
                return prefs.importedModels().toString();
            case "storeToken":
                vault.store("github", argString(call, "token"));
                return true;
            case "clearToken":
                vault.clear("github");
                return true;
            default:
                throw new IllegalArgumentException("Unknown method: " + call.method);
        }
    }

    /**
     * Ask a provider what it serves. This is the fix for a list baked into the
     * app: a model that has been retired stops appearing here.
     */
    /**
     * The provider a call is talking about: what is on screen, filled in from
     * what was saved. What is on screen wins — someone editing a provider is
     * asking "does this new key work", not "does the old one still work".
     */
    private CloudProvider.Config configFrom(MethodCall call) throws Exception {
        String id = argString(call, "id");
        String baseUrl = argString(call, "baseUrl");
        String key = argString(call, "apiKey");
        String kind = argString(call, "kind");
        String authStyle = argString(call, "authStyle");
        String authName = argString(call, "authName");
        String model = argString(call, "model");
        JSONObject headers = headersOf(call);
        if (!id.isEmpty()) {
            JSONObject saved = prefs.cloudProvider(id);
            if (saved != null) {
                baseUrl = firstOf(baseUrl, saved.optString("baseUrl"));
                kind = firstOf(kind, saved.optString("kind"));
                authStyle = firstOf(authStyle, saved.optString("authStyle"));
                authName = firstOf(authName, saved.optString("authName"));
                model = firstOf(model, saved.optString("model"));
                if (headers.length() == 0 && saved.optJSONObject("headers") != null) {
                    headers = saved.optJSONObject("headers");
                }
                if (key.isEmpty()) {
                    key = vault.read("cloud:" + id);
                }
            }
        }
        return new CloudProvider.Config(kind, baseUrl, key, model, authStyle, authName, headers);
    }

    /**
     * Ask a provider what it serves. This is the fix for a list baked into the
     * app: a model that has been retired stops appearing here.
     */
    private String providerModels(MethodCall call) throws Exception {
        try {
            return CloudProvider.listModels(configFrom(call).withModel("")).toString();
        } catch (Exception error) {
            errors.record("provider models", error);
            throw error;
        }
    }

    /**
     * One real request to the model, right now. Empty when it answers,
     * otherwise the reason it cannot be used — so a model is never saved on the
     * strength of appearing in a list.
     */
    private String probeModel(MethodCall call) throws Exception {
        String verdict = CloudProvider.probe(configFrom(call));
        if (verdict != null) {
            errors.record("provider probe", verdict);
        }
        return verdict == null ? "" : verdict;
    }

    private static Intent pickFileIntent(String mime) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(mime);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private String addCloudProvider(MethodCall call) throws Exception {
        String id = Long.toString(System.currentTimeMillis());
        JSONObject provider = new JSONObject();
        provider.put("id", id);
        provider.put("label", argString(call, "label"));
        provider.put("baseUrl", EndpointPolicy.tidy(argString(call, "baseUrl")));
        provider.put("model", argString(call, "model"));
        provider.put("kind", argString(call, "kind"));
        provider.put("authStyle", argString(call, "authStyle"));
        provider.put("authName", argString(call, "authName"));
        provider.put("headers", headersOf(call));
        provider.put("checkedAt", System.currentTimeMillis());
        Prefs.withDefaults(provider);
        prefs.addCloudProvider(provider);
        // The key goes to the keystore, never to the preferences file.
        String key = argString(call, "apiKey");
        if (!key.isEmpty()) {
            vault.store("cloud:" + id, key);
        }
        return prefs.cloudProviders(vault).toString();
    }

    /** Edit a saved provider, including its key. */
    private String updateCloudProvider(MethodCall call) throws Exception {
        String id = argString(call, "id");
        prefs.updateCloudProvider(
            id,
            argString(call, "label"),
            argString(call, "model"),
            argString(call, "kind"),
            argString(call, "baseUrl"),
            argString(call, "authStyle"),
            argString(call, "authName"),
            call.hasArgument("headers") ? headersOf(call) : null);
        String key = argString(call, "apiKey");
        if (!key.isEmpty()) {
            vault.store("cloud:" + id, key);
        }
        return prefs.cloudProviders(vault).toString();
    }

    /** The typed value if there is one, otherwise the saved one. */
    private static String firstOf(String typed, String saved) {
        return typed == null || typed.trim().isEmpty() ? saved : typed;
    }

    /** Extra headers arrive as a plain map from Dart. */
    private JSONObject headersOf(MethodCall call) throws Exception {
        JSONObject out = new JSONObject();
        Object raw = call.argument("headers");
        if (raw instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) raw;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                String name = String.valueOf(entry.getKey()).trim();
                String value = String.valueOf(entry.getValue()).trim();
                if (!name.isEmpty() && !value.isEmpty()) {
                    out.put(name, value);
                }
            }
        }
        return out;
    }

    /** One read of everything the UI draws, so a screen can rebuild in one call. */
    private Map<String, Object> snapshot() {
        Map<String, Object> out = new HashMap<>();
        out.put("executionMode", prefs.executionMode());
        out.put("workspaceGranted", workspace.hasRoot());
        out.put("workspaceState", workspace.rootState());
        out.put("grants", prefs.grants().toString());
        out.put("workspaceName", workspace.rootName());
        out.put("activeModelId", prefs.activeModelId());
        out.put("endpoint", prefs.endpoint());
        out.put("failover", prefs.failoverEnabled());
        out.put("cloudProviders", prefs.cloudProviders(vault).toString());
        out.put("running", agent.isRunning());
        out.put("hasToken", vault.has("github"));
        out.put("readOnlyTools", new ArrayList<>(ToolPolicy.READ_ONLY));
        out.put("mutatingTools", new ArrayList<>(ToolPolicy.MUTATING));
        out.put("maxFileBytes", WorkspaceStore.MAX_BYTES);
        out.put("wifiOnly", prefs.wifiOnly());
        out.put("batteryGuard", prefs.batteryGuard());
        out.put("keepWarm", prefs.keepWarm());
        out.put("canResume", agent.canResume());
        out.put("theme", prefs.theme());
        out.put("textScale", (double) prefs.textScale());
        out.put("walkthroughDone", prefs.walkthroughDone());
        out.put("budgetSteps", prefs.budgetSteps());
        out.put("budgetSeconds", prefs.budgetSeconds());
        out.put("budgetCloudCalls", prefs.budgetCloudCalls());
        out.put("importedModels", prefs.importedModels().toString());
        out.put("downloadState", prefs.downloadState().toString());
        out.put("errorCount", errors.entries().length());
        out.put("chats", agent.chatIndex().toString());
        // Whatever Luna is waiting on, in the snapshot as well as the stream.
        org.json.JSONObject prompt = agent.pendingPrompt();
        out.put("pendingPrompt", prompt == null ? "" : prompt.toString());
        out.put("activeChatId", agent.activeChatId());
        JSONObject backup = workspace.lastBackup();
        out.put("lastBackup", backup == null ? null : backup.toString());
        try {
            out.put("catalog", models.catalog().toString());
        } catch (JSONException ignored) {
            out.put("catalog", "[]");
        }
        out.put("messages", agent.messages().toString());
        return out;
    }

    private static int intArg(MethodCall call, String name, int fallback) {
        Object value = call.argument(name);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return fallback;
    }

    private static double doubleArg(MethodCall call, String name, double fallback) {
        Object value = call.argument(name);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return fallback;
    }

    private static String argString(MethodCall call, String name) {
        Object value = call.argument(name);
        return value == null ? "" : String.valueOf(value);
    }

    private void reply(final MethodChannel.Result result, final Object value, final String code, final String message) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (code == null) {
                    result.success(value);
                } else {
                    result.error(code, message, null);
                }
            }
        });
    }

    // --- SAF result ----------------------------------------------------------

    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_PICK_FOLDER) {
            MethodChannel.Result result = pendingFolderPick;
            pendingFolderPick = null;
            if (result == null) {
                return;
            }
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
                result.success(null);
                return;
            }
            Uri uri = data.getData();
            workspace.persistGrant(uri);
            List<String> answer = new ArrayList<>();
            answer.add(uri.toString());
            answer.add(workspace.rootName());
            result.success(answer);
            return;
        }

        if (requestCode != REQUEST_IMPORT_GGUF && requestCode != REQUEST_BRING_IN
            && requestCode != REQUEST_RESTORE) {
            return;
        }
        final MethodChannel.Result result = pendingFilePick;
        pendingFilePick = null;
        if (result == null) {
            return;
        }
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            result.success(null);
            return;
        }
        final Uri uri = data.getData();
        final int kind = requestCode;
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (kind == REQUEST_IMPORT_GGUF) {
                        reply(result, importGguf(uri), null, null);
                    } else if (kind == REQUEST_BRING_IN) {
                        reply(result, workspace.bringIn(uri, workspace.nameOf(uri)), null, null);
                    } else {
                        reply(result, restoreSettings(uri), null, null);
                    }
                } catch (Exception error) {
                    errors.record("file pick", error);
                    String message = error.getMessage();
                    reply(result, null, "luna_error", message == null ? String.valueOf(error) : message);
                }
            }
        });
    }

    /**
     * Copy a model the user brought themselves into Luna's own models folder, so
     * it survives the permission being withdrawn. There is no published checksum
     * for a file like this, so it is marked as not verified rather than pretending.
     */
    private String importGguf(Uri uri) throws Exception {
        String name = workspace.nameOf(uri);
        if (!name.toLowerCase(java.util.Locale.US).endsWith(".gguf")) {
            throw new IllegalArgumentException("That is not a .gguf file.");
        }
        File target = new File(models.modelsDir(), name);
        java.io.InputStream input = null;
        java.io.OutputStream output = null;
        long copied = 0L;
        try {
            input = activity.getContentResolver().openInputStream(uri);
            if (input == null) {
                throw new java.io.IOException("Could not open that file.");
            }
            output = new java.io.FileOutputStream(target);
            byte[] chunk = new byte[256 * 1024];
            int read;
            long lastReport = 0L;
            while ((read = input.read(chunk)) > 0) {
                output.write(chunk, 0, read);
                copied += read;
                if (copied - lastReport > 8_000_000L) {
                    lastReport = copied;
                    JSONObject event = new JSONObject();
                    event.put("type", "import");
                    event.put("name", name);
                    event.put("completed", copied);
                    push(event);
                }
            }
            output.flush();
        } finally {
            WorkspaceStore.closeQuietly(input);
            WorkspaceStore.closeQuietly(output);
        }

        JSONObject model = new JSONObject();
        model.put("id", "imported:" + name);
        model.put("name", name.replaceAll("(?i)\\.gguf$", ""));
        model.put("file", target.getAbsolutePath());
        model.put("sizeBytes", copied);
        model.put("params", ModelStore.describeGguf(target));
        model.put("verified", false);
        model.put("at", System.currentTimeMillis());
        prefs.addImportedModel(model);
        return model.toString();
    }

    private String restoreSettings(Uri uri) throws Exception {
        java.io.InputStream input = activity.getContentResolver().openInputStream(uri);
        if (input == null) {
            throw new java.io.IOException("Could not open that file.");
        }
        try {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) > 0) {
                buffer.write(chunk, 0, read);
            }
            prefs.importSettings(new JSONObject(buffer.toString("UTF-8")));
            return "restored";
        } finally {
            WorkspaceStore.closeQuietly(input);
        }
    }

    /** A file shared into Luna from another app, copied into the granted folder. */
    public String acceptShared(Uri uri) {
        try {
            String name = workspace.bringIn(uri, workspace.nameOf(uri));
            JSONObject event = new JSONObject();
            event.put("type", "shared");
            event.put("name", name);
            push(event);
            return name;
        } catch (Exception error) {
            errors.record("shared file", error);
            return "";
        }
    }
}
