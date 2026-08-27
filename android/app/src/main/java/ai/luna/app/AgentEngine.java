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

    /** Roughly how many characters one token is worth for these models. */
    private static final int CHARS_PER_TOKEN = 3;

    /** What a cloud model gets. Their windows are large; this is the sane cap. */
    private static final int CLOUD_HISTORY_CHARS = 12000;

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

    private final CredentialVault vault;
    private final ErrorLog errors;
    private final HeadlessBrowser browser;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    /** A turn ends exactly once, whoever gets there first. */
    private final AtomicBoolean finished = new AtomicBoolean(true);
    private final BlockingQueue<Boolean> approvals = new ArrayBlockingQueue<>(1);
    private final BlockingQueue<String> answers = new ArrayBlockingQueue<>(1);

    private final List<JSONObject> transcript = new ArrayList<>();
    private volatile String pendingApprovalId = "";
    private volatile String pendingQuestionId = "";
    private volatile String chatId = "";

    public AgentEngine(Context context, Prefs prefs, WorkspaceStore workspace, ModelStore models,
                       OnDeviceRuntime runtime, CredentialVault vault, ErrorLog errors,
                       HeadlessBrowser browser, Events events) {
        this.context = context.getApplicationContext();
        this.prefs = prefs;
        this.workspace = workspace;
        this.models = models;
        this.runtime = runtime;
        this.vault = vault;
        this.errors = errors;
        this.browser = browser;
        this.events = events;
        this.chatId = prefs.activeChatId();
        if (this.chatId.isEmpty()) {
            this.chatId = "chat-" + System.currentTimeMillis();
            prefs.setActiveChatId(this.chatId);
        }
        loadTranscript();
    }

    // --- transcript ----------------------------------------------------------

    private File transcriptFile() {
        File chats = new File(context.getFilesDir(), "chats");
        if (!chats.exists()) {
            chats.mkdirs();
        }
        File file = new File(chats, chatId + ".json");
        File legacy = new File(context.getFilesDir(), "chat.json");
        if (!file.exists() && legacy.exists()) {
            // One chat existed before this build. Keep it rather than lose it.
            legacy.renameTo(file);
        }
        return file;
    }

    // --- more than one chat ---------------------------------------------------

    public String activeChatId() {
        return chatId;
    }

    /** The list the chat picker draws: id, first line, when it was last touched. */
    public JSONArray chatIndex() {
        JSONArray out = new JSONArray();
        File chats = new File(context.getFilesDir(), "chats");
        File[] files = chats.listFiles();
        if (files == null) {
            return out;
        }
        java.util.Arrays.sort(files, new java.util.Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return Long.compare(right.lastModified(), left.lastModified());
            }
        });
        for (File file : files) {
            String name = file.getName();
            if (!name.endsWith(".json")) {
                continue;
            }
            try {
                JSONObject entry = new JSONObject();
                entry.put("id", name.substring(0, name.length() - 5));
                entry.put("at", file.lastModified());
                entry.put("title", titleOf(file));
                entry.put("active", name.substring(0, name.length() - 5).equals(chatId));
                out.put(entry);
            } catch (JSONException ignored) {
                // Skip a chat that will not describe itself.
            }
        }
        return out;
    }

    /** Chats whose text contains the words, so an old job can be found again. */
    public JSONArray searchChats(String needle) {
        JSONArray out = new JSONArray();
        if (needle == null || needle.trim().isEmpty()) {
            return chatIndex();
        }
        String lower = needle.toLowerCase(java.util.Locale.US);
        JSONArray all = chatIndex();
        for (int index = 0; index < all.length(); index++) {
            JSONObject entry = all.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            File file = new File(new File(context.getFilesDir(), "chats"), entry.optString("id") + ".json");
            String body = readWhole(file).toLowerCase(java.util.Locale.US);
            if (body.contains(lower)) {
                out.put(entry);
            }
        }
        return out;
    }

    public void newChat() {
        if (running.get()) {
            return;
        }
        chatId = "chat-" + System.currentTimeMillis();
        prefs.setActiveChatId(chatId);
        transcript.clear();
    }

    public void switchChat(String id) {
        if (running.get() || id == null || id.isEmpty()) {
            return;
        }
        chatId = id;
        prefs.setActiveChatId(chatId);
        transcript.clear();
        loadTranscript();
    }

    public void deleteChat(String id) {
        File file = new File(new File(context.getFilesDir(), "chats"), id + ".json");
        if (file.exists()) {
            file.delete();
        }
        if (id.equals(chatId)) {
            newChat();
        }
    }

    /**
     * The whole job as a file: what was asked, every step, what changed, and the
     * answer. Written as Markdown because it is meant to be read by a person.
     */
    public String exportChat() {
        StringBuilder out = new StringBuilder();
        out.append("# Luna — ").append(titleOf(transcriptFile())).append("\n\n");
        out.append("Exported ").append(new java.util.Date().toString()).append("\n\n");
        for (JSONObject message : transcript) {
            String role = message.optString("role");
            String content = message.optString("content");
            if (role.equals("user")) {
                out.append("## You\n\n").append(content).append("\n\n");
            } else if (role.equals("assistant")) {
                out.append("## Luna\n\n").append(content).append("\n\n");
            } else if (role.equals("observation")) {
                out.append("- step: ").append(content.replace("\n", " ")).append('\n');
            }
        }
        out.append("\nNothing in this file left the device unless a cloud model was used.\n");
        return out.toString();
    }

    private String titleOf(File file) {
        try {
            JSONArray array = new JSONArray(readWhole(file));
            for (int index = 0; index < array.length(); index++) {
                JSONObject message = array.optJSONObject(index);
                if (message != null && message.optString("role").equals("user")) {
                    String text = message.optString("content").trim().replace("\n", " ");
                    return text.length() > 60 ? text.substring(0, 60) + "…" : text;
                }
            }
        } catch (Exception ignored) {
            // An unreadable chat still deserves a row in the list.
        }
        return "Empty chat";
    }

    private String readWhole(File file) {
        if (!file.exists()) {
            return "";
        }
        FileInputStream input = null;
        try {
            input = new FileInputStream(file);
            byte[] buffer = new byte[(int) file.length()];
            int read = input.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, StandardCharsets.UTF_8);
        } catch (Exception error) {
            return "";
        } finally {
            WorkspaceStore.closeQuietly(input);
        }
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
        noteInterruptedTurn();
    }

    /**
     * A job that was killed with the app leaves a user message and no answer.
     * Say so on the way back in, rather than showing a conversation that looks
     * like Luna ignored the question.
     */
    private void noteInterruptedTurn() {
        if (!dangling(transcript)) {
            return;
        }
        append("assistant", "That job stopped when Luna closed. Anything above this line did happen; "
            + "nothing after it ran. Tap Carry on and I will pick it up.", "interrupted");
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
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        if (running.get()) {
            // Silence here is how a wedged job eats every message after it.
            append("assistant", "A job is still running. Stop it first, then send this again.", null);
            emit("run_done", new JSONObject());
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

    /**
     * Stop means stop. Cancelling the runtime handles generation, but a model
     * load is native and cannot be interrupted, so the turn is also ended here
     * and now — otherwise the app looks alive and quietly drops everything the
     * user types next.
     */
    /**
     * Pick a stopped job back up. The transcript already holds every step that
     * succeeded, so the model is told to continue rather than start again.
     */
    public void resume() {
        if (running.get()) {
            return;
        }
        if (lastUserInstruction().isEmpty()) {
            return;
        }
        send("Carry on from where you stopped. The steps recorded above already happened — "
            + "do not repeat them, and do not start over.");
    }

    /** True when the last thing in the chat is a stop or an interruption. */
    public boolean canResume() {
        return !running.get() && resumable(transcript);
    }

    /** Pure: a chat can be carried on when it stopped and there was an order. */
    static boolean resumable(List<JSONObject> messages) {
        if (messages.isEmpty() || lastUserInstruction(messages).isEmpty()) {
            return false;
        }
        String meta = messages.get(messages.size() - 1).optString("meta");
        return meta.equals("interrupted") || meta.equals("stopped");
    }

    /** Pure: the turn never got an answer, so the app died in the middle of it. */
    static boolean dangling(List<JSONObject> messages) {
        return !messages.isEmpty()
            && !messages.get(messages.size() - 1).optString("role").equals("assistant");
    }

    private String lastUserInstruction() {
        return lastUserInstruction(transcript);
    }

    static String lastUserInstruction(List<JSONObject> messages) {
        for (int index = messages.size() - 1; index >= 0; index--) {
            JSONObject message = messages.get(index);
            if (message.optString("role").equals("user")) {
                return message.optString("content");
            }
        }
        return "";
    }

    public void stop() {
        stopRequested.set(true);
        runtime.cancel();
        approvals.offer(Boolean.FALSE);
        answers.offer("");
        if (running.getAndSet(false)) {
            finishWithMessage("Stopped.", "stopped");
        }
    }

    /** The answer to an ask_user question. An empty answer counts as "skip". */
    public void answerQuestion(String id, String text) {
        if (!id.equals(pendingQuestionId)) {
            return;
        }
        answers.offer(text == null ? "" : text);
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
        finished.set(false);
        long started = System.currentTimeMillis();
        RunGuards guards = new RunGuards(prefs.budgetSteps(), prefs.budgetSeconds(), prefs.budgetCloudCalls());
        guards.begin();
        Tools.Env env = new Tools.Env(workspace, browser, vault, errors);
        emit("run_started", new JSONObject());

        try {
            ModelStore.Entry local = activeLocalModel();
            JSONObject cloud = activeCloudProvider();
            if (local == null && cloud == null) {
                finishWithMessage(noModelReason());
                return;
            }
            if (local != null && !loadIfNeeded(local)) {
                finishWithMessage("That model would not load. It may be a bad download; delete it and get it again.");
                return;
            }

            String answer = "";
            // A run that ends because it hit a limit is a run that can be
            // picked up again, and the chat should offer that.
            boolean cutShort = false;
            while (true) {
                if (stopRequested.get()) {
                    finishWithMessage("Stopped.", "stopped");
                    return;
                }
                if (guards.elapsedMillis() > (long) prefs.budgetSeconds() * 1000L) {
                    answer = "I hit the time limit for one job (" + prefs.budgetSeconds()
                        + " seconds) and stopped. " + guards.describe() + ".";
                    cutShort = true;
                    break;
                }

                String raw = ask(local, cloud, guards);
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

                if (tool.equals("ask_user")) {
                    String question = args.optString("question", args.optString("text", ""));
                    String reply = askUser(question);
                    if (stopRequested.get()) {
                        finishWithMessage("Stopped.", "stopped");
                        return;
                    }
                    appendObservation(tool, reply.isEmpty()
                        ? "The user did not answer. Carry on with what you already know, or say what you need."
                        : "The user answered: " + reply);
                    continue;
                }

                String path = args.optString("path", args.optString("url", ""));
                boolean mutating = ToolPolicy.isMutating(tool);

                RunGuards.Verdict verdict = guards.check(tool, args, mutating);
                if (verdict.replay != null) {
                    // Read it once, remember it. The same read twice is a loop.
                    emitStep(tool, path, "replayed");
                    appendObservation(tool, verdict.replay);
                    continue;
                }
                if (!verdict.allowed) {
                    emitStep(tool, path, "blocked");
                    appendObservation(tool, verdict.reason);
                    if (verdict.reason.contains("limit")) {
                        answer = verdict.reason;
                        break;
                    }
                    continue;
                }

                ToolPolicy.Decision decision = ToolPolicy.decide(tool, prefs);
                if (decision == ToolPolicy.Decision.REFUSE) {
                    emitStep(tool, path, "denied");
                    appendObservation(tool, "Your rules say never for " + tool
                        + ". It was not run. Do something else, or say what you would have needed.");
                    continue;
                }
                if (decision == ToolPolicy.Decision.ASK) {
                    boolean approved = requestApproval(tool, path, args.optString("content", ""));
                    if (!approved) {
                        emitStep(tool, path, "denied");
                        appendObservation(tool, "The user declined this action. Do not retry it; "
                            + "explain what you would have done instead.");
                        continue;
                    }
                }

                emitStep(tool, path, "running");
                String observation = Tools.run(env, tool, args);
                guards.record(tool, args, observation);
                emitStep(tool, path, "done");
                appendObservation(tool, observation);
            }

            if (answer.isEmpty()) {
                answer = "I ran out of steps before finishing. Tell me which part to pick up.";
                cutShort = true;
            }
            append("assistant", answer, cutShort ? "stopped" : null);
            saveTranscript();

            JSONObject done = new JSONObject();
            try {
                done.put("text", answer);
                done.put("elapsedMs", System.currentTimeMillis() - started);
            } catch (JSONException ignored) {
                // The event still carries meaning without the timing.
            }
            if (finished.compareAndSet(false, true)) {
                emit("run_done", done);
            }
        } catch (Exception error) {
            errors.record("agent", error);
            finishWithMessage("Something went wrong in the loop: " + error);
        } finally {
            running.set(false);
            pendingApprovalId = "";
            pendingQuestionId = "";
            // Cookies and page history belong to the job, not to the app.
            browser.close();
            if (!prefs.keepWarm()) {
                runtime.unload();
            }
        }
    }

    /** One model call. Returns raw text, or null when the turn already ended. */
    private String ask(ModelStore.Entry local, JSONObject cloud, RunGuards guards) {
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

        RunGuards.Verdict allowed = guards.checkCloudCall();
        if (!allowed.allowed) {
            finishWithMessage(allowed.reason);
            return null;
        }
        guards.recordCloudCall();

        // The key is read for this one call and never goes into the prompt.
        String key = vault.read("cloud:" + cloud.optString("id"));
        CloudProvider.Config config = new CloudProvider.Config(
            cloud.optString("baseUrl"), key, cloud.optString("model"));
        CloudProvider.Reply reply = CloudProvider.chatStreaming(config, cloudMessages(), 1024,
            new CloudProvider.TokenSink() {
                @Override
                public void onToken(String text) {
                    JSONObject event = new JSONObject();
                    try {
                        event.put("text", text);
                    } catch (JSONException ignored) {
                        return;
                    }
                    emit("token", event);
                }
            });
        if (reply.error != null) {
            errors.record("cloud", reply.error);
            finishWithMessage(reply.error);
            return null;
        }
        return reply.text;
    }

    private boolean loadIfNeeded(ModelStore.Entry entry) {
        if (runtime.isLoaded() && entry.id.equals(runtime.loadedModelId())) {
            return true;
        }
        emit("loading_model", new JSONObject());
        boolean ok = runtime.load(entry.id, models.fileFor(entry));
        // The step has to be closed either way. A row that spins forever is
        // worse than a row that says it failed.
        emitStep("load_model", "", ok ? "done" : "denied");
        return ok;
    }

    private ModelStore.Entry activeLocalModel() {
        String id = prefs.activeModelId();
        if (id.isEmpty() || id.startsWith("cloud:")) {
            return null;
        }
        if (id.startsWith("imported:")) {
            return importedEntry(id);
        }
        ModelStore.Entry entry = ModelStore.find(id);
        // A file that is on disk and has some size in it is runnable. The exact
        // size only decides whether a download finished, and that check has
        // already happened by the time it is sitting here.
        return entry != null && models.fileFor(entry).length() > 0L ? entry : null;
    }

    /** A model the user imported, or null when the file is no longer there. */
    private ModelStore.Entry importedEntry(String id) {
        JSONObject model = prefs.importedModel(id);
        if (model == null) {
            return null;
        }
        String stored = model.optString("file");
        File file = new File(stored);
        if (!file.exists()) {
            // Imports are copied into the models folder, so the name still finds
            // it even if the recorded path is stale.
            file = new File(models.modelsDir(), new File(stored).getName());
        }
        if (!file.exists() || file.length() <= 0L) {
            return null;
        }
        return ModelStore.imported(id, model.optString("name", file.getName()), file);
    }

    /**
     * Why there is nothing to run. "Pick a model" is a lie when a model is
     * picked and its file has gone missing, and a lie in a failure message
     * costs an hour of looking in the wrong place.
     */
    private String noModelReason() {
        String id = prefs.activeModelId();
        if (id.isEmpty()) {
            return "Pick a model first — Model Zoo tab. Nothing has been sent anywhere.";
        }
        if (id.startsWith("cloud:")) {
            return "That cloud provider is no longer set up. Add the key again in the Model Zoo.";
        }
        if (id.startsWith("imported:")) {
            JSONObject model = prefs.importedModel(id);
            if (model == null) {
                return "That imported model is no longer in the list. Import the file again.";
            }
            return "The file for " + model.optString("name", "that model")
                + " is not in Luna's models folder any more. Import it again.";
        }
        ModelStore.Entry entry = ModelStore.find(id);
        if (entry == null) {
            return "The selected model is not one Luna knows about. Pick another in the Model Zoo.";
        }
        File file = models.fileFor(entry);
        if (!file.exists()) {
            return entry.name + " is selected but not downloaded. Get it in the Model Zoo.";
        }
        return entry.name + " is only " + (file.length() / (1024L * 1024L)) + " MB on disk, and it should be "
            + (entry.sizeBytes / (1024L * 1024L)) + " MB. The download did not finish — get it again.";
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

    /**
     * A real question. The job stops here until the person answers, the same way
     * an approval does — a question nobody sees is worse than not asking.
     */
    private String askUser(String question) {
        if (question == null || question.trim().isEmpty()) {
            return "";
        }
        String id = Long.toString(System.nanoTime());
        pendingQuestionId = id;
        answers.clear();

        JSONObject event = new JSONObject();
        try {
            event.put("id", id);
            event.put("question", question.trim());
        } catch (JSONException ignored) {
            return "";
        }
        emit("ask", event);

        try {
            String answer = answers.poll(10, TimeUnit.MINUTES);
            pendingQuestionId = "";
            return answer == null ? "" : answer.trim();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            pendingQuestionId = "";
            return "";
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
        out.append("{\"tool\":\"delete_file\",\"args\":{\"path\":\"old.txt\"}}\n");
        out.append("{\"tool\":\"rename_file\",\"args\":{\"path\":\"a.txt\",\"newName\":\"b.txt\"}}\n");
        out.append("{\"tool\":\"open_page\",\"args\":{\"url\":\"example.com/page\"}}\n");
        out.append("{\"tool\":\"read_page\",\"args\":{}}\n");
        out.append("{\"tool\":\"github_file\",\"args\":{\"repo\":\"owner/name\",\"path\":\"README.md\"}}\n");
        out.append("{\"tool\":\"ask_user\",\"args\":{\"question\":\"Which folder did you mean?\"}}\n\n");
        out.append("open_page then read_page is how you read the web; the browser has no window and \n");
        out.append("forgets everything when the job ends. ask_user stops and waits for a real answer, so \n");
        out.append("use it when a guess would be expensive.\n\n");
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
        String system = systemPrompt(entry.systemPrompt);
        out.append("<|im_start|>system\n").append(system).append("<|im_end|>\n");
        for (JSONObject message : trimmedHistory(historyBudget(entry, system))) {
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
            for (JSONObject message : trimmedHistory(CLOUD_HISTORY_CHARS)) {
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

    /**
     * How much conversation actually fits. The window has to hold the system
     * prompt and the answer as well, so the history gets what is left — a fixed
     * 6000 characters used to overflow a 2048-token model and the turn died
     * with "longer than the context window" instead of forgetting the oldest
     * part, which is what it should do.
     */
    private int historyBudget(ModelStore.Entry entry, String system) {
        int window = entry.contextTokens > 0 ? entry.contextTokens : 2048;
        int reserved = entry.maxOutputTokens + (system.length() / CHARS_PER_TOKEN) + 128;
        int left = (window - reserved) * CHARS_PER_TOKEN;
        return Math.max(1200, left);
    }

    /** Keep the tail of the conversation that fits the context budget. */
    private List<JSONObject> trimmedHistory(int budgetChars) {
        return tail(transcript, budgetChars);
    }

    /** Pure: the newest messages that fit, oldest first, never empty. */
    static List<JSONObject> tail(List<JSONObject> messages, int budgetChars) {
        List<JSONObject> out = new ArrayList<>();
        int budget = budgetChars;
        for (int index = messages.size() - 1; index >= 0; index--) {
            JSONObject message = messages.get(index);
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
        finishWithMessage(text, null);
    }

    private void finishWithMessage(String text, String meta) {
        if (!finished.compareAndSet(false, true)) {
            // The turn already ended — a stop that raced the loop, usually.
            return;
        }
        append("assistant", text, meta);
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
