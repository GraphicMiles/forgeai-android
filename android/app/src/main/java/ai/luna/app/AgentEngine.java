package ai.luna.app;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Luna's loop.
 *
 * One turn is: build the prompt from the transcript, ask the model, look for a
 * tool call, decide whether it needs the user, run it, feed the result back.
 * Repeats until the model answers in plain text or the iteration budget runs
 * out. Everything the UI needs to draw is pushed out as an event; the UI never
 * drives the loop.
 */
public final class AgentEngine {

    private static final int MAX_ITERATIONS = 6;
    private static final int MAX_HISTORY_CHARS = 6000;

    /** How the engine talks to Flutter. */
    public interface Events {
        void emit(JSONObject event);
    }

    private final Context context;
    private final Prefs prefs;
    private final WorkspaceStore workspace;
    private final ModelStore models;
    private final OnDeviceRuntime runtime;
    private final Events events;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final BlockingQueue<Boolean> approvals = new ArrayBlockingQueue<>(1);

    private final List<JSONObject> transcript = new ArrayList<>();
    private volatile String pendingApprovalId = "";

    public AgentEngine(Context context, Prefs prefs, WorkspaceStore workspace, ModelStore models,
                       OnDeviceRuntime runtime, Events events) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.workspace = workspace;
        this.models = models;
        this.runtime = runtime;
        this.events = events;
        loadTranscript();
    }

    // --- transcript ----------------------------------------------------------

    private File transcriptFile() {
        return new File(context.getFilesDir(), "chat.json");
    }

    private void loadTranscript() {
        File file = transcriptFile();
        if (!file.exists()) {
            return;
        }
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            int read = input.read(buffer);
            if (read <= 0) {
                return;
            }
            JSONArray array = new JSONArray(new String(buffer, 0, read, StandardCharsets.UTF_8));
            for (int index = 0; index < array.length(); index++) {
                transcript.add(array.getJSONObject(index));
            }
        } catch (Exception ignored) {
            // A corrupt transcript is not worth crashing over; start clean.
        } finally {
            WorkspaceStore.closeQuietly(input);
        }
    }

    private void saveTranscript() {
        FileOutputStream output = null;
        try {
            JSONArray array = new JSONArray();
            for (JSONObject message : transcript) {
                array.put(message);
            }
            output = new FileOutputStream(transcriptFile());
            output.write(array.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            // Losing the transcript is survivable; losing the turn is not.
        } finally {
            WorkspaceStore.closeQuietly(output);
        }
    }

    public JSONArray messages() {
        JSONArray array = new JSONArray();
        for (JSONObject message : transcript) {
            array.put(message);
        }
        return array;
    }

    public void clear() {
        transcript.clear();
        saveTranscript();
    }

    public boolean isRunning() {
        return running.get();
    }

    // --- turn ----------------------------------------------------------------

    public void send(final String text) {
        if (text == null || text.trim().isEmpty() || running.get()) {
            return;
        }
        append("user", text.trim(), null);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                runTurn(text.trim());
            }
        });
    }

    public void stop() {
        stopRequested.set(true);
        runtime.cancel();
        approvals.offer(Boolean.FALSE);
    }

    public void resolveApproval(String id, boolean approved) {
        if (!id.equals(pendingApprovalId)) {
            return;
        }
        approvals.offer(approved ? Boolean.TRUE : Boolean.FALSE);
    }

    private void runTurn(String userText) {
        running.set(true);
        stopRequested.set(false);
        long started = System.currentTimeMillis();
        emit("run_started", new JSONObject());

        try {
            ModelStore.Entry local = activeLocalModel();
            JSONObject cloud = activeCloudProvider();
            if (local == null && cloud == null) {
                finishWithMessage("Pick a model first — Models tab. Nothing has been sent anywhere.");
                return;
            }
            if (local != null && !loadIfNeeded(local)) {
                finishWithMessage("That model would not load. It may be a bad download; delete it and get it again.");
                return;
            }

            String answer = "";
            for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
                if (stopRequested.get()) {
                    finishWithMessage("Stopped.");
                    return;
                }

                String raw = ask(local, cloud, iteration);
                if (raw == null) {
                    return;
                }

                JSONObject call = parseToolCall(raw);
                if (call == null) {
                    answer = raw.trim();
                    break;
                }

                String tool = call.optString("tool");
                JSONObject args = call.optJSONObject("args");
                if (args == null) {
                    args = new JSONObject();
                }

                if (tool.equals("respond")) {
                    answer = args.optString("text", raw.trim());
                    break;
                }

                if (!ToolPolicy.isKnown(tool)) {
                    appendObservation(tool, "That tool does not exist. Use one of: "
                        + ToolPolicy.READ_ONLY + " " + ToolPolicy.MUTATING);
                    continue;
                }

                String path = args.optString("path", "");
                if (ToolPolicy.needsApproval(tool, prefs.unattended())) {
                    boolean approved = requestApproval(tool, path, args.optString("content", ""));
                    if (!approved) {
                        appendObservation(tool, "The user declined this action. Do not retry it; "
                            + "explain what you would have done instead.");
                        continue;
                    }
                }

                emitStep(tool, path, "running");
                String observation = Tools.run(workspace, tool, args);
                emitStep(tool, path, "done");
                appendObservation(tool, observation);
            }

            if (answer.isEmpty()) {
                answer = "I ran out of steps before finishing. Tell me which part to pick up.";
            }
            append("assistant", answer, null);
            saveTranscript();

            JSONObject done = new JSONObject();
            try {
                done.put("text", answer);
                done.put("elapsedMs", System.currentTimeMillis() - started);
            } catch (JSONException ignored) {
                // The event still carries meaning without the timing.
            }
            emit("run_done", done);
        } catch (Exception error) {
            finishWithMessage("Something went wrong in the loop: " + error);
        } finally {
            running.set(false);
            pendingApprovalId = "";
        }
    }

    /** One model call. Returns raw text, or null when the turn already ended. */
    private String ask(ModelStore.Entry local, JSONObject cloud, int iteration) {
        emit("thinking", new JSONObject());
        if (local != null) {
            final StringBuilder streamed = new StringBuilder();
            OnDeviceRuntime.Result result = runtime.generate(
                buildPrompt(local),
                local.maxOutputTokens,
                local.contextTokens,
                DeviceCapacity.suggestedThreads(),
                new OnDeviceRuntime.TokenSink() {
                    @Override
                    public void onToken(String text) {
                        streamed.append(text);
                        JSONObject event = new JSONObject();
                        try {
                            event.put("text", text);
                        } catch (JSONException ignored) {
                            return;
                        }
                        emit("token", event);
                    }
                });
            String failure = result.failure();
            if (failure != null) {
                finishWithMessage(failure);
                return null;
            }
            JSONObject speed = new JSONObject();
            try {
                speed.put("tokensPerSecond", result.tokensPerSecond());
                speed.put("outputTokens", result.outputTokens);
            } catch (JSONException ignored) {
                // Telemetry only.
            }
            emit("speed", speed);
            return result.text;
        }

        CloudProvider.Config config = new CloudProvider.Config(
            cloud.optString("baseUrl"), cloud.optString("apiKey"), cloud.optString("model"));
        CloudProvider.Reply reply = CloudProvider.chat(config, cloudMessages(), 1024);
        if (reply.error != null) {
            finishWithMessage(reply.error);
            return null;
        }
        JSONObject event = new JSONObject();
        try {
            event.put("text", reply.text);
        } catch (JSONException ignored) {
            // Fall through: the answer still gets appended below.
        }
        emit("token", event);
        return reply.text;
    }

    private boolean loadIfNeeded(ModelStore.Entry entry) {
        if (runtime.isLoaded() && entry.id.equals(runtime.loadedModelId())) {
            return true;
        }
        emit("loading_model", new JSONObject());
        return runtime.load(entry.id, models.fileFor(entry));
    }

    private ModelStore.Entry activeLocalModel() {
        String id = prefs.activeModelId();
        if (id.isEmpty() || id.startsWith("cloud:")) {
            return null;
        }
        ModelStore.Entry entry = ModelStore.find(id);
        return entry != null && models.isInstalled(entry) ? entry : null;
    }

    private JSONObject activeCloudProvider() {
        String id = prefs.activeModelId();
        if (!id.startsWith("cloud:")) {
            return null;
        }
        return prefs.cloudProvider(id.substring("cloud:".length()));
    }

    // --- approval ------------------------------------------------------------

    private boolean requestApproval(String tool, String path, String content) {
        String id = Long.toString(System.nanoTime());
        pendingApprovalId = id;
        approvals.clear();

        JSONObject event = new JSONObject();
        try {
            event.put("id", id);
            event.put("tool", tool);
            event.put("path", path);
            event.put("headline", ToolPolicy.describe(tool, path, content));
            event.put("consequence", ToolPolicy.consequence(tool, path));
            event.put("preview", content.length() > 600 ? content.substring(0, 600) + "…" : content);
        } catch (JSONException ignored) {
            return false;
        }
        emit("approval", event);

        try {
            Boolean answer = approvals.poll(10, TimeUnit.MINUTES);
            pendingApprovalId = "";
            return answer != null && answer;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            pendingApprovalId = "";
            return false;
        }
    }

    // --- prompt --------------------------------------------------------------

    private String systemPrompt(String modelPersona) {
        StringBuilder out = new StringBuilder();
        out.append("You are Luna, a local utility agent that runs natively on the user's Android phone. ");
        out.append("You are not a chat window with tools bolted on: the device is your workplace.\n\n");
        out.append("Folder granted: ").append(workspace.hasRoot() ? workspace.rootName() : "none yet").append('\n');
        out.append("Mode: ").append(prefs.unattended() ? "unattended" : "ask before acting").append("\n\n");
        out.append("To use a tool, reply with one JSON object and nothing else:\n");
        out.append("{\"tool\":\"list_files\",\"args\":{\"path\":\"\"}}\n");
        out.append("{\"tool\":\"read_file\",\"args\":{\"path\":\"notes.md\"}}\n");
        out.append("{\"tool\":\"search_code\",\"args\":{\"query\":\"TODO\"}}\n");
        out.append("{\"tool\":\"write_file\",\"args\":{\"path\":\"notes.md\",\"content\":\"...\"}}\n");
        out.append("{\"tool\":\"create_file\",\"args\":{\"path\":\"a.txt\"}}\n");
        out.append("{\"tool\":\"create_folder\",\"args\":{\"path\":\"notes\"}}\n");
        out.append("{\"tool\":\"delete_file\",\"args\":{\"path\":\"old.txt\"}}\n\n");
        out.append("Rules: read before you write. One tool per reply. Paths are relative to the granted ");
        out.append("folder. When the work is done, reply in plain sentences — no JSON — and say what you ");
        out.append("changed. Never claim you did something a tool result does not show.\n");
        if (modelPersona != null && !modelPersona.isEmpty()) {
            out.append('\n').append(modelPersona).append('\n');
        }
        return out.toString();
    }

    /** ChatML, which every model in the catalogue was trained on. */
    private String buildPrompt(ModelStore.Entry entry) {
        StringBuilder out = new StringBuilder();
        out.append("<|im_start|>system\n").append(systemPrompt(entry.systemPrompt)).append("<|im_end|>\n");
        for (JSONObject message : trimmedHistory()) {
            String role = message.optString("role");
            String content = message.optString("content");
            if (role.equals("observation")) {
                out.append("<|im_start|>user\nTool result: ").append(content).append("<|im_end|>\n");
            } else {
                out.append("<|im_start|>").append(role).append('\n').append(content).append("<|im_end|>\n");
            }
        }
        out.append("<|im_start|>assistant\n");
        return out.toString();
    }

    private List<JSONObject> cloudMessages() {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONObject system = new JSONObject();
            system.put("role", "system");
            system.put("content", systemPrompt(null));
            out.add(system);
            for (JSONObject message : trimmedHistory()) {
                JSONObject copy = new JSONObject();
                String role = message.optString("role");
                copy.put("role", role.equals("assistant") ? "assistant" : "user");
                copy.put("content", role.equals("observation")
                    ? "Tool result: " + message.optString("content")
                    : message.optString("content"));
                out.add(copy);
            }
        } catch (JSONException ignored) {
            // A malformed message is dropped rather than poisoning the request.
        }
        return out;
    }

    /** Keep the tail of the conversation that fits the context budget. */
    private List<JSONObject> trimmedHistory() {
        List<JSONObject> out = new ArrayList<>();
        int budget = MAX_HISTORY_CHARS;
        for (int index = transcript.size() - 1; index >= 0; index--) {
            JSONObject message = transcript.get(index);
            int cost = message.optString("content").length();
            if (cost > budget && !out.isEmpty()) {
                break;
            }
            budget -= cost;
            out.add(0, message);
        }
        return out;
    }

    // --- parsing -------------------------------------------------------------

    /** Pull the first balanced {...} that names a tool. Returns null for prose. */
    static JSONObject parseToolCall(String raw) {
        if (raw == null) {
            return null;
        }
        int start = raw.indexOf('{');
        while (start >= 0) {
            int depth = 0;
            boolean inString = false;
            boolean escaped = false;
            for (int index = start; index < raw.length(); index++) {
                char symbol = raw.charAt(index);
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (symbol == '\\') {
                    escaped = true;
                    continue;
                }
                if (symbol == '"') {
                    inString = !inString;
                    continue;
                }
                if (inString) {
                    continue;
                }
                if (symbol == '{') {
                    depth++;
                } else if (symbol == '}') {
                    depth--;
                    if (depth == 0) {
                        String candidate = raw.substring(start, index + 1);
                        try {
                            JSONObject json = new JSONObject(candidate);
                            if (json.has("tool")) {
                                return json;
                            }
                        } catch (JSONException ignored) {
                            // Not a tool call: keep scanning.
                        }
                        break;
                    }
                }
            }
            start = raw.indexOf('{', start + 1);
        }
        return null;
    }

    // --- plumbing ------------------------------------------------------------

    private void append(String role, String content, String meta) {
        try {
            JSONObject message = new JSONObject();
            message.put("role", role);
            message.put("content", content);
            message.put("at", System.currentTimeMillis());
            if (meta != null) {
                message.put("meta", meta);
            }
            transcript.add(message);
            saveTranscript();
        } catch (JSONException ignored) {
            // Nothing to add if the message will not serialise.
        }
    }

    private void appendObservation(String tool, String observation) {
        append("observation", tool + " → " + observation, tool);
    }

    private void finishWithMessage(String text) {
        append("assistant", text, null);
        saveTranscript();
        JSONObject event = new JSONObject();
        try {
            event.put("text", text);
        } catch (JSONException ignored) {
            // The UI still gets run_done and re-reads the transcript.
        }
        emit("run_done", event);
    }

    private void emitStep(String tool, String path, String state) {
        JSONObject event = new JSONObject();
        try {
            event.put("tool", tool);
            event.put("path", path);
            event.put("state", state);
        } catch (JSONException ignored) {
            return;
        }
        emit("step", event);
    }

    private void emit(String type, JSONObject payload) {
        try {
            payload.put("type", type);
        } catch (JSONException ignored) {
            return;
        }
        events.emit(payload);
    }
}
