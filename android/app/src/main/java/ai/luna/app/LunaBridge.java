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

    private final Activity activity;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newFixedThreadPool(2);

    private final Prefs prefs;
    private final WorkspaceStore workspace;
    private final ModelStore models;
    private final OnDeviceRuntime runtime;
    private final CredentialVault vault;
    private final AgentEngine agent;

    private final MethodChannel methodChannel;
    private final EventChannel eventChannel;

    private EventChannel.EventSink sink;
    private MethodChannel.Result pendingFolderPick;

    public LunaBridge(Activity activity, BinaryMessenger messenger) {
        this.activity = activity;
        this.prefs = new Prefs(activity);
        this.workspace = new WorkspaceStore(activity, prefs);
        this.models = new ModelStore(activity);
        this.runtime = new OnDeviceRuntime();
        this.vault = new CredentialVault(activity);
        this.agent = new AgentEngine(activity, prefs, workspace, models, runtime, new AgentEngine.Events() {
            @Override
            public void emit(JSONObject event) {
                push(event);
            }
        });

        this.methodChannel = new MethodChannel(messenger, METHOD_CHANNEL);
        this.methodChannel.setMethodCallHandler(this);
        this.eventChannel = new EventChannel(messenger, EVENT_CHANNEL);
        this.eventChannel.setStreamHandler(this);
    }

    public void dispose() {
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

    // --- methods -------------------------------------------------------------

    @Override
    public void onMethodCall(@NonNull final MethodCall call, @NonNull final MethodChannel.Result result) {
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
            case "resolveApproval":
                agent.resolveApproval(String.valueOf(call.argument("id")), Boolean.TRUE.equals(call.argument("approved")));
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
                models.cancelDownload();
                result.success(null);
                return;
            case "hasToken":
                result.success(vault.has("github"));
                return;
            case "resetAll":
                agent.clear();
                prefs.clearAll();
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
                return downloadModel(argString(call, "id"));
            case "deleteModel":
                return models.delete(argString(call, "id"));
            case "unloadModel":
                runtime.unload();
                return null;
            case "ollamaModels":
                return CloudProvider.ollamaModels(prefs.endpoint()).toString();
            case "addCloudProvider":
                return addCloudProvider(call);
            case "removeCloudProvider":
                prefs.removeCloudProvider(argString(call, "id"));
                return prefs.cloudProviders().toString();
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

    private String downloadModel(String id) {
        ModelStore.Entry entry = ModelStore.find(id);
        if (entry == null) {
            return "No such model.";
        }
        return models.download(entry, new ModelStore.ProgressSink() {
            @Override
            public void onProgress(String modelId, long completed, long total, String status) {
                JSONObject event = new JSONObject();
                try {
                    event.put("type", "download");
                    event.put("id", modelId);
                    event.put("completed", completed);
                    event.put("total", total);
                    event.put("status", status);
                } catch (JSONException ignored) {
                    return;
                }
                push(event);
            }
        });
    }

    private String addCloudProvider(MethodCall call) throws JSONException {
        JSONObject provider = new JSONObject();
        provider.put("id", Long.toString(System.currentTimeMillis()));
        provider.put("label", argString(call, "label"));
        provider.put("baseUrl", argString(call, "baseUrl"));
        provider.put("apiKey", argString(call, "apiKey"));
        provider.put("model", argString(call, "model"));
        prefs.addCloudProvider(provider);
        return prefs.cloudProviders().toString();
    }

    /** One read of everything the UI draws, so a screen can rebuild in one call. */
    private Map<String, Object> snapshot() {
        Map<String, Object> out = new HashMap<>();
        out.put("executionMode", prefs.executionMode());
        out.put("workspaceGranted", workspace.hasRoot());
        out.put("workspaceName", workspace.rootName());
        out.put("activeModelId", prefs.activeModelId());
        out.put("endpoint", prefs.endpoint());
        out.put("failover", prefs.failoverEnabled());
        out.put("cloudProviders", prefs.cloudProviders().toString());
        out.put("running", agent.isRunning());
        out.put("hasToken", vault.has("github"));
        out.put("readOnlyTools", new ArrayList<>(ToolPolicy.READ_ONLY));
        out.put("mutatingTools", new ArrayList<>(ToolPolicy.MUTATING));
        out.put("maxFileBytes", WorkspaceStore.MAX_BYTES);
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
        if (requestCode != REQUEST_PICK_FOLDER) {
            return;
        }
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
    }
}
