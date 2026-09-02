package ai.luna.app;

import ai.luna.builtin.Builtins;
import ai.luna.builtin.CoreSkills;
import ai.luna.builtin.LunaAgent;
import ai.luna.contracts.AgentBudget;
import ai.luna.contracts.AgentDefinition;
import ai.luna.contracts.AgentResult;
import ai.luna.contracts.MemoryKind;
import ai.luna.contracts.SkillDefinition;
import ai.luna.contracts.ToolContext;
import ai.luna.contracts.ToolDefinition;
import ai.luna.contracts.ToolProvider;
import ai.luna.contracts.ToolResult;
import ai.luna.contracts.WorkflowDefinition;
import ai.luna.runtime.AgentManager;
import ai.luna.runtime.AgentRegistry;
import ai.luna.runtime.EnvironmentRegistry;
import ai.luna.runtime.EphemeralMemory;
import ai.luna.runtime.InferenceRouter;
import ai.luna.runtime.MemoryRegistry;
import ai.luna.runtime.PluginManager;
import ai.luna.runtime.PluginVerifier;
import ai.luna.runtime.SkillRegistry;
import ai.luna.runtime.SubAgentSpawner;
import ai.luna.runtime.SkillResolver;
import ai.luna.runtime.SystemPrompt;
import ai.luna.runtime.ToolRegistry;
import ai.luna.runtime.WorkflowEngine;
import ai.luna.runtime.WorkflowHost;
import ai.luna.runtime.WorkflowRegistry;
import ai.luna.runtime.WorkflowRun;

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
    private static final int CLOUD_HISTORY_CHARS = 48000;

    /** How many turns of conversation the cloud model is given to hold. */
    private static final int CLOUD_HISTORY_TURNS = 30;

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

    /** Everything this runtime can do. Asked, never assumed. */
    private final ToolRegistry tools = new ToolRegistry();

    /** Where those tools run. Today the phone; the interface says "today". */
    private final AndroidExecution environment;

    /** Everywhere they could run. The phone is the first entry, not the only kind. */
    private final EnvironmentRegistry environments = new EnvironmentRegistry();

    /** What Luna knows. Text, not code, and none of it lives in this file. */
    private final SkillRegistry skills = new SkillRegistry();

    /** Every agent installed. Luna is the first entry, not the only kind. */
    private final AgentRegistry agents = new AgentRegistry();

    /** Which agent is running, and what that narrows this turn to. */
    private final AgentManager agentManager;

    /** Installed plugins: knowledge and agents from outside this build. */
    private final PluginManager pluginManager;

    /** Jobs written down as steps instead of hoped for in a prompt. */
    private final WorkflowRegistry workflows = new WorkflowRegistry();

    /** Five kinds of remembering, only two of which outlive the run. */
    private final MemoryRegistry memory = new MemoryRegistry();

    /** Which brain answers, and the sentence that justifies it. */
    private final InferenceRouter router = new InferenceRouter();

    /** Handing a piece of work to a narrower agent, under the same budget. */
    private final SubAgentSpawner spawner;

    /** Assembles the system prompt from the skills and the available tools. */
    private final SystemPrompt prompt;

    /** The message this turn is about, for deciding which skills apply. */
    private volatile String lastUserText = "";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);

    /** A turn ends exactly once, whoever gets there first. */
    private final AtomicBoolean finished = new AtomicBoolean(true);
    private final BlockingQueue<Boolean> approvals = new ArrayBlockingQueue<>(1);
    private final BlockingQueue<String> answers = new ArrayBlockingQueue<>(1);

    /** Tools run here, so one that hangs cannot take the loop with it. */
    private final ExecutorService toolRunner = Executors.newSingleThreadExecutor();

    /** How long any one tool gets before it is abandoned. */
    private static final long TOOL_TIMEOUT_MS = 90_000L;

    /** How often a live prompt is repeated in case the UI missed the first. */
    private static final long PROMPT_HEARTBEAT_MS = 2_000L;

    private final List<JSONObject> transcript = new ArrayList<>();
    private volatile String pendingApprovalId = "";
    private volatile String pendingQuestionId = "";
    private volatile String chatId = "";

    /**
     * The question on screen right now, approval or otherwise.
     *
     * It exists because an event can be missed — the channel is not a queue,
     * and a UI that was rebuilding when it arrived never sees it. Anything
     * waiting on a person is therefore said three ways: the event, a repeat
     * every two seconds, and this, which any snapshot can read. A job that
     * silently waits for an answer nobody was asked for is the worst failure
     * this app can have.
     */
    private volatile JSONObject livePrompt;

    /**
     * True when this turn is a greeting or a question about Luna herself. The
     * tool list is left out of the prompt entirely, because a small model shown
     * a tool will use one.
     */
    private volatile boolean conversationOnly;

    /** Milliseconds this run spent waiting on the person, not working. */
    private final java.util.concurrent.atomic.AtomicLong waitedMillis =
        new java.util.concurrent.atomic.AtomicLong(0L);

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
        this.environment = new AndroidExecution(workspace, browser, vault,
            new AppGitStore(new File(context.getFilesDir(), "git"), errors));
        environments.register(environment);
        for (JSONObject declared : prefs.declaredEnvironments()) {
            environments.register(ai.luna.builtin.DeclaredEnvironment.fromJson(declared));
        }
        for (ToolProvider provider : Builtins.all()) {
            tools.register(provider);
        }
        // The environment decides what is even offerable here: no shell on a
        // phone, so no tool that wants one is ever put in front of the model.
        tools.grant(environments.capabilities());
        skills.register(new CoreSkills());
        skills.disable(prefs.disabledSkills());
        router.register(new LocalInference(runtime, models));
        memory.register(new EphemeralMemory());
        memory.register(new FileMemory(this.context.getFilesDir(), errors));
        agents.register(LunaAgent.DEFINITION);
        // Plugins load before the agent manager exists, so an agent that
        // arrived in one can be the active agent on the very first turn.
        this.pluginManager = new PluginManager(
            new PluginVerifier().allowUnsigned(prefs.allowUnsignedPlugins()),
            skills, agents, workflows, new PrefsPluginStore(prefs));
        for (String refused : pluginManager.restore()) {
            errors.warn("plugins", "not loaded — " + refused);
        }
        for (JSONObject installed : prefs.installedAgents()) {
            agents.registerJson(installed);
        }
        this.agentManager = new AgentManager(agents, tools, skills);
        agentManager.activate(prefs.activeAgentId());
        this.prompt = new SystemPrompt(tools, skills, new SkillResolver(), agentManager);
        this.spawner = new SubAgentSpawner(agents, new SubAgentSpawner.Runner() {
            @Override
            public AgentResult run(SubAgentSpawner.SubAgentContext child) {
                return consult(child);
            }
        });
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
        livePrompt = null;
        if (running.getAndSet(false)) {
            finishWithMessage("I stopped there.", "stopped");
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
        waitedMillis.set(0L);
        livePrompt = null;
        conversationOnly = SmallTalk.matches(userText);
        lastUserText = userText == null ? "" : userText;
        long started = System.currentTimeMillis();
        // The app sets a ceiling; an agent may set itself a lower one.
        RunGuards guards = new RunGuards(agentManager.steps(prefs.budgetSteps()),
            agentManager.seconds(prefs.budgetSeconds()), prefs.budgetCloudCalls());
        guards.begin();
        ToolContext env = toolContext();
        emit("run_started", new JSONObject());

        try {
            ModelStore.Entry local = activeLocalModel();
            JSONObject cloud = activeCloudProvider();
            if (local == null && cloud == null) {
                finishWithMessage(noModelReason());
                return;
            }
            if (local != null && !loadIfNeeded(local)) {
                // The router decides, and says why. "It failed over" is not an
                // explanation; "this is bigger than the phone's model can hold"
                // is something a person can act on.
                router.failed("local.llamacpp");
                JSONObject standby = failoverProvider();
                InferenceRouter.Route route = router.choose(
                    candidates(local, standby), needFor(userText, standby));
                if (standby == null || !route.any() || !route.chosen.remote) {
                    finishWithMessage("That model would not load. It may be a bad download; "
                        + "delete it and get it again.");
                    return;
                }
                emit("failover", reasonEvent(route.reason));
                appendObservation("failover", route.reason);
                local = null;
                cloud = standby;
            }

            String answer = "";
            // A run that ends because it hit a limit is a run that can be
            // picked up again, and the chat should offer that.
            boolean cutShort = false;
            boolean repaired = false;
            while (true) {
                if (stopRequested.get()) {
                    finishWithMessage("I stopped there.", "stopped");
                    return;
                }
                if (guards.elapsedMillis() > (long) prefs.budgetSeconds() * 1000L) {
                    answer = "I ran past the " + prefs.budgetSeconds()
                        + " seconds you allow for one job, so I stopped. " + guards.describe() + ".";
                    cutShort = true;
                    break;
                }

                String raw = ask(local, cloud, guards);
                if (raw == null) {
                    return;
                }

                JSONObject call = parseToolCall(raw);
                if (call == null) {
                    String trimmed = raw.trim();
                    // A small model often wraps its sentence in a JSON object
                    // that names no tool — {"text": ...}, {"answer": ...}.
                    // That is an answer in JSON clothing, not a broken tool
                    // call: take the sentence, never the braces.
                    String wrapped = wrappedText(trimmed);
                    if (!wrapped.isEmpty()) {
                        answer = wrapped;
                        break;
                    }
                    // A reply that opens like a tool call but does not parse is
                    // a broken tool call, not an answer. Printing it would show
                    // the person half a brace. One correction is offered; a
                    // second failure is taken as prose so this cannot loop.
                    if (!repaired && (trimmed.startsWith("{") || trimmed.startsWith("["))) {
                        repaired = true;
                        appendObservation("format", "That was not a complete JSON object. "
                            + "Send exactly one object like {\"tool\":\"read_file\",\"args\":{\"path\":\"notes.md\"}}, "
                            + "or answer in plain sentences with no JSON at all.");
                        continue;
                    }
                    answer = trimmed;
                    break;
                }

                String tool = call.optString("tool");
                JSONObject args = call.optJSONObject("args");
                if (args == null) {
                    args = new JSONObject();
                }

                // A small model spells a tool id the way it sounds — readfile,
                // list-files — so a near-miss is resolved to the real id before
                // anything else judges it. An exact id always wins.
                String resolved = tools.resolve(tool);
                if (!resolved.isEmpty()) {
                    tool = resolved;
                }

                if (tool.equals("respond")) {
                    String said = args.optString("text", "").trim();
                    if (said.isEmpty()) {
                        // respond with no sentence: take whatever prose
                        // surrounded the call, never the call JSON itself.
                        said = proseOf(raw);
                    }
                    answer = said;
                    break;
                }

                if (conversationOnly) {
                    // Nothing was asked for that a tool could provide. Rather
                    // than run one, ask once for the sentence that was wanted.
                    if (!repaired) {
                        repaired = true;
                        appendObservation("format", "No tool is needed here. Reply to the person "
                            + "in one or two plain sentences, with no JSON.");
                        continue;
                    }
                    answer = "Hello. Tell me what you would like done and I will get on with it.";
                    break;
                }

                if (!agentManager.canUse(tool)) {
                    String elsewhere = environments.elsewhere(tools.definition(tool));
                    appendObservation(tool, elsewhere.isEmpty()
                        ? "That tool does not exist. Use one of: " + agentManager.toolIds(env) + "."
                        : elsewhere);
                    continue;
                }

                if (tool.equals("ask_user")) {
                    String question = args.optString("question", args.optString("text", ""));
                    String reply = askUser(question);
                    if (stopRequested.get()) {
                        finishWithMessage("I stopped there.", "stopped");
                        return;
                    }
                    appendObservation(tool, reply.isEmpty()
                        ? "The user did not answer. Carry on with what you already know, or say what you need."
                        : "The user answered: " + reply);
                    continue;
                }

                String path = args.optString("path", args.optString("url", args.optString("query", "")));
                boolean mutating = tools.mutating(tool);

                if (tools.needsFolder(tool) && !workspace.hasRoot()) {
                    // Asking the person to approve something that cannot work
                    // is a way of blaming them for it.
                    emitStep(tool, path, "no_folder");
                    appendObservation(tool, "No folder is granted, so there is nothing to read or "
                        + "write. Ask for a folder in one sentence, or answer without files.");
                    continue;
                }

                if (tool.equals("open_page")) {
                    String invented = NetworkTargets.placeholderReason(
                        args.optString("url", path));
                    if (invented != null) {
                        emitStep(tool, path, "invented");
                        appendObservation(tool, invented);
                        continue;
                    }
                }

                RunGuards.Verdict verdict = guards.check(tool, args, mutating);
                if (verdict.replay != null) {
                    // Read it once, remember it. The same read twice is a loop.
                    emitStep(tool, path, "replayed");
                    appendObservation(tool, verdict.replay);
                    continue;
                }
                if (!verdict.allowed) {
                    emitStep(tool, path, "blocked", reasonStep(verdict.reason));
                    appendObservation(tool, verdict.reason);
                    if (verdict.reason.contains("limit")) {
                        // Hitting a limit mid-job is the same as running out of
                        // time: everything already done is in the transcript,
                        // so the chat has to offer the way back in.
                        answer = verdict.reason;
                        cutShort = true;
                        break;
                    }
                    continue;
                }

                ToolPolicy.Decision decision = ToolPolicy.decide(tool, path, prefs);
                if (decision == ToolPolicy.Decision.ASK) {
                    // The row has to exist while the job is parked on it. A
                    // trace that hides the tool you are being asked about is
                    // asking you to approve something you cannot see.
                    emitStep(tool, path, "held");
                    boolean approved = requestApproval(tool, path, args.optString("content", ""));
                    if (!approved) {
                        emitStep(tool, path, "declined");
                        appendObservation(tool, "The user declined this action. Do not retry it; "
                            + "explain what you would have done instead.");
                        continue;
                    }
                }

                emitStep(tool, path, "running");
                ToolResult result = tools.run(env, tool, args, watchdog());
                if (result.timedOut) {
                    emitStep(tool, path, "unfinished", reasonStep(result.observation));
                    appendObservation(tool, result.observation
                        + " Try a smaller piece of the same job, or a different way in.");
                    continue;
                }
                String observation = result.observation;
                guards.record(tool, args, observation);
                emitStep(tool, path, result.ok ? "done" : "failed",
                    result.ok ? null : reasonStep(observation));
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
                // What Luna did, with the time you spent deciding taken out.
                done.put("workMs", Math.max(0L,
                    System.currentTimeMillis() - started - waitedMillis.get()));
            } catch (JSONException ignored) {
                // The event still carries meaning without the timing.
            }
            if (finished.compareAndSet(false, true)) {
                emit("run_done", done);
                memory.remember(MemoryKind.EXECUTION, agentManager.activeId(),
                    "Asked: " + ReadableText.clean(lastUserText, 120), 40, "run");
            }
        } catch (Exception error) {
            errors.record("agent", error);
            finishWithMessage("Something went wrong while I was working, so I stopped. "
                + "Nothing was left half-written. Settings keeps the details.");
        } finally {
            running.set(false);
            pendingApprovalId = "";
            pendingQuestionId = "";
            // Working memory is meant to be lost: a note about the job that
            // just ended is not a fact about the person.
            memory.endOfRun(agentManager.activeId());
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
        final StreamFilter spigot = new StreamFilter();
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
                        String show = spigot.filter(text);
                        if (show.isEmpty()) {
                            return;
                        }
                        JSONObject event = new JSONObject();
                        try {
                            event.put("text", show);
                        } catch (JSONException ignored) {
                            return;
                        }
                        emit("token", event);
                    }
                });
            String failure = result.failure();
            if (failure != null) {
                router.failed("local.llamacpp");
                finishWithMessage(failure);
                return null;
            }
            router.worked("local.llamacpp");
            JSONObject speed = new JSONObject();
            try {
                speed.put("tokensPerSecond", result.tokensPerSecond());
                speed.put("outputTokens", result.outputTokens);
            } catch (JSONException ignored) {
                // Telemetry only.
            }
            emit("speed", speed);
            flushSpigot(spigot);
            return result.text;
        }

        RunGuards.Verdict allowed = guards.checkCloudCall();
        if (!allowed.allowed) {
            finishWithMessage(allowed.reason);
            return null;
        }
        guards.recordCloudCall();

        // The key is read for this one call and never goes into the prompt.
        // Ollama on your own machine has no key, and asking the keystore for
        // one that was never stored just returns empty anyway.
        String key = cloud.optBoolean("local") ? "" : vault.read("cloud:" + cloud.optString("id"));
        CloudProvider.Config config = new CloudProvider.Config(
            cloud.optString("kind"),
            cloud.optString("baseUrl"),
            key,
            cloud.optString("model"),
            cloud.optString("authStyle"),
            cloud.optString("authName"),
            cloud.optJSONObject("headers"));
        CloudProvider.Reply reply = CloudProvider.chatStreaming(config, cloudMessages(), 1024,
            new CloudProvider.TokenSink() {
                @Override
                public void onToken(String text) {
                    String show = spigot.filter(text);
                    if (show.isEmpty()) {
                        return;
                    }
                    JSONObject event = new JSONObject();
                    try {
                        event.put("text", show);
                    } catch (JSONException ignored) {
                        return;
                    }
                    emit("token", event);
                }
            },
            new CloudProvider.Cancellation() {
                @Override
                public boolean cancelled() {
                    return stopRequested.get();
                }
            });
        if (reply.error != null) {
            router.failed("cloud:" + cloud.optString("id"));
            errors.record("cloud", reply.error);
            // A model that is no longer there must not stay selected, or every
            // job from now on fails with the same sentence.
            if (reply.error.contains("not available on this key")
                && !cloud.optBoolean("local")) {
                prefs.clearCloudModel(cloud.optString("id"));
                finishWithMessage(reply.error
                    + " I have cleared it, so open Model Zoo, tap the provider and check the "
                    + "model list.");
                return null;
            }
            finishWithMessage(reply.error);
            return null;
        }
        router.worked("cloud:" + cloud.optString("id"));
        flushSpigot(spigot);
        return reply.text;
    }

    /** Releases a stream filter's held tail — the last sentence of an answer. */
    private void flushSpigot(StreamFilter spigot) {
        String rest = spigot.finish();
        if (!rest.isEmpty()) {
            emitToken(rest);
        }
    }

    /**
     * One model call with a prompt of the caller's own, outside the chat.
     *
     * <p>A workflow step is not a turn in a conversation: it has no transcript
     * behind it and no tool list in front of it. It gets the same two code
     * paths — the loaded local model, or the configured provider — and nothing
     * else from the chat loop.
     */
    private String askOnce(String instruction, ModelStore.Entry local, JSONObject cloud,
                           RunGuards guards) {
        emit("thinking", new JSONObject());
        String system = "You are carrying out one step of a job. Answer the instruction directly "
            + "and plainly. No JSON, no tool calls, no preamble.";
        if (local != null) {
            StringBuilder prompt = new StringBuilder();
            prompt.append("<|im_start|>system\n").append(system).append("<|im_end|>\n");
            prompt.append("<|im_start|>user\n").append(instruction).append("<|im_end|>\n");
            prompt.append("<|im_start|>assistant\n");
            OnDeviceRuntime.Result result = runtime.generate(prompt.toString(),
                local.maxOutputTokens, local.contextTokens, DeviceCapacity.suggestedThreads(),
                new OnDeviceRuntime.TokenSink() {
                    @Override
                    public void onToken(String text) {
                        emitToken(text);
                    }
                });
            return result.failure() != null ? "" : result.text;
        }
        if (cloud == null) {
            return "";
        }
        RunGuards.Verdict allowed = guards.checkCloudCall();
        if (!allowed.allowed) {
            return "";
        }
        guards.recordCloudCall();
        String key = cloud.optBoolean("local") ? "" : vault.read("cloud:" + cloud.optString("id"));
        CloudProvider.Config config = new CloudProvider.Config(
            cloud.optString("kind"), cloud.optString("baseUrl"), key, cloud.optString("model"),
            cloud.optString("authStyle"), cloud.optString("authName"),
            cloud.optJSONObject("headers"));
        List<JSONObject> messages = new java.util.ArrayList<>();
        messages.add(CloudInference.message("system", system));
        messages.add(CloudInference.message("user", instruction));
        CloudProvider.Reply reply = CloudProvider.chatStreaming(config, messages, 1024,
            new CloudProvider.TokenSink() {
                @Override
                public void onToken(String text) {
                    emitToken(text);
                }
            },
            new CloudProvider.Cancellation() {
                @Override
                public boolean cancelled() {
                    return stopRequested.get();
                }
            });
        if (reply.error != null) {
            errors.record("cloud", reply.error);
            return "";
        }
        return reply.text;
    }

    /** A streamed fragment, on its way to the chat. */
    private void emitToken(String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        JSONObject event = new JSONObject();
        try {
            event.put("text", text);
        } catch (JSONException ignored) {
            return;
        }
        emit("token", event);
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

    /**
     * The thing on the other end of a network call: a hosted provider, or the
     * Ollama server on your own machine. Ollama speaks the same OpenAI shape at
     * /v1, so once the address is turned into a config the loop cannot tell
     * them apart — which is why "your computer" is now a model you can pick
     * rather than an address that sat there doing nothing.
     */
    private JSONObject activeCloudProvider() {
        String id = prefs.activeModelId();
        if (id.startsWith("ollama:")) {
            return ollamaProvider(id.substring("ollama:".length()));
        }
        if (!id.startsWith("cloud:")) {
            return null;
        }
        return prefs.cloudProvider(id.substring("cloud:".length()));
    }

    /**
     * Where to go when the on-device model cannot answer. Null unless the user
     * turned failover on and there is somewhere to fail over to.
     */
    private JSONObject failoverProvider() {
        if (!prefs.failoverEnabled()) {
            return null;
        }
        JSONArray providers = prefs.cloudProviders(vault);
        for (int index = 0; index < providers.length(); index++) {
            JSONObject provider = providers.optJSONObject(index);
            if (provider != null && !provider.optString("model").isEmpty()) {
                return provider;
            }
        }
        return null;
    }

    /** Everything that could answer this turn, as the router sees it. */
    private List<InferenceRouter.Candidate> candidates(ModelStore.Entry local, JSONObject cloud) {
        List<InferenceRouter.Candidate> out = new java.util.ArrayList<>();
        if (local != null) {
            out.add(new InferenceRouter.Candidate("local.llamacpp", local.name, false,
                local.contextTokens, 0));
        }
        if (cloud != null) {
            out.add(new InferenceRouter.Candidate("cloud:" + cloud.optString("id"),
                cloud.optString("label", "the configured provider"), true, 0, 1));
        }
        return out;
    }

    /** What this turn needs: roughly its size, and whether it may leave. */
    private InferenceRouter.Need needFor(String userText, JSONObject standby) {
        int tokens = Math.max(512, (transcriptCharacters() + userText.length()) / 4);
        return new InferenceRouter.Need(tokens, false, prefs.activeModelId(),
            prefs.failoverEnabled() && standby != null);
    }

    private int transcriptCharacters() {
        int total = 0;
        for (JSONObject message : transcript) {
            total += message.optString("content").length();
        }
        return total;
    }

    private JSONObject reasonEvent(String reason) {
        JSONObject event = new JSONObject();
        try {
            event.put("reason", reason);
        } catch (JSONException ignored) {
            // The event is still worth emitting without its sentence.
        }
        return event;
    }

    /** Which providers have been working, for the debug panel. */
    public JSONArray inferenceHealth() {
        return router.health();
    }

    private JSONObject ollamaProvider(String model) {
        String endpoint = prefs.endpoint().replaceAll("/+$", "");
        if (endpoint.isEmpty() || model.isEmpty()) {
            return null;
        }
        JSONObject out = new JSONObject();
        try {
            out.put("id", "ollama");
            out.put("label", model + " on your computer");
            out.put("baseUrl", endpoint + "/v1");
            out.put("model", model);
            out.put("kind", CloudProvider.OPENAI);
            out.put("authStyle", CloudProvider.AUTH_NONE);
            out.put("local", true);
        } catch (JSONException error) {
            return null;
        }
        return out;
    }

    // --- approval ------------------------------------------------------------

    private boolean requestApproval(String tool, String path, String content) {
        return requestApproval(tool, path, ToolPolicy.describe(tool, path, content),
            ToolPolicy.consequence(tool, path), content);
    }

    /**
     * The same wait, with the question written by the caller.
     *
     * <p>A tool's question comes from {@code ToolPolicy}. A workflow's comes
     * from the workflow. The machinery underneath — the held row, the repeated
     * event, the ten-minute deadline, the banked waiting time — is the same,
     * because a person should never be able to tell which one is asking.
     */
    private boolean requestApproval(String tool, String path, String headline, String consequence,
                                    String content) {
        String id = Long.toString(System.nanoTime());
        pendingApprovalId = id;
        approvals.clear();

        JSONObject event = new JSONObject();
        try {
            event.put("id", id);
            event.put("tool", tool);
            event.put("path", path);
            event.put("headline", headline);
            event.put("consequence", consequence);
            event.put("preview", content.length() > 600 ? content.substring(0, 600) + "…" : content);
        } catch (JSONException ignored) {
            pendingApprovalId = "";
            return false;
        }
        livePrompt = event;
        // Said twice: once as the row that is now waiting, carrying the whole
        // question, and once as the approval itself. Either one can draw the
        // card, so losing one of them costs nothing.
        emitStep(tool, path, "held", event);
        emit("approval", event);

        Boolean answer = waitForApproval();
        pendingApprovalId = "";
        livePrompt = null;
        emit("prompt_cleared", new JSONObject());
        return answer != null && answer;
    }

    /**
     * Waits for the tap, in short hops rather than one long sleep.
     *
     * Polling in half seconds is what makes Stop feel instant and lets the
     * question be repeated while it is still unanswered. The waiting time is
     * banked so the elapsed clock can subtract it: a person taking a minute to
     * decide is not Luna taking a minute to think.
     */
    private Boolean waitForApproval() {
        long began = System.currentTimeMillis();
        long deadline = began + TimeUnit.MINUTES.toMillis(10);
        long lastBeat = began;
        try {
            while (System.currentTimeMillis() < deadline) {
                Boolean answer = approvals.poll(500, TimeUnit.MILLISECONDS);
                if (answer != null) {
                    waitedMillis.addAndGet(System.currentTimeMillis() - began);
                    return answer;
                }
                if (stopRequested.get()) {
                    waitedMillis.addAndGet(System.currentTimeMillis() - began);
                    return Boolean.FALSE;
                }
                long now = System.currentTimeMillis();
                JSONObject prompt = livePrompt;
                if (prompt != null && now - lastBeat >= PROMPT_HEARTBEAT_MS) {
                    lastBeat = now;
                    emit("approval", prompt);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        waitedMillis.addAndGet(System.currentTimeMillis() - began);
        return Boolean.FALSE;
    }

    private String askUser(String question) {
        String id = Long.toString(System.nanoTime());
        pendingQuestionId = id;
        answers.clear();

        JSONObject event = new JSONObject();
        try {
            event.put("id", id);
            event.put("question", question.trim());
        } catch (JSONException ignored) {
            pendingQuestionId = "";
            return "";
        }
        livePrompt = event;
        emitStep("ask_user", "", "held", event);
        emit("ask", event);

        String answer = waitForAnswer();
        pendingQuestionId = "";
        livePrompt = null;
        emit("prompt_cleared", new JSONObject());
        return answer == null ? "" : answer.trim();
    }

    /** The same short hops as the approval wait, for the same reasons. */
    private String waitForAnswer() {
        long began = System.currentTimeMillis();
        long deadline = began + TimeUnit.MINUTES.toMillis(10);
        long lastBeat = began;
        try {
            while (System.currentTimeMillis() < deadline) {
                String answer = answers.poll(500, TimeUnit.MILLISECONDS);
                if (answer != null) {
                    waitedMillis.addAndGet(System.currentTimeMillis() - began);
                    return answer;
                }
                if (stopRequested.get()) {
                    break;
                }
                long now = System.currentTimeMillis();
                JSONObject prompt = livePrompt;
                if (prompt != null && now - lastBeat >= PROMPT_HEARTBEAT_MS) {
                    lastBeat = now;
                    emit("ask", prompt);
                }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
        }
        waitedMillis.addAndGet(System.currentTimeMillis() - began);
        return "";
    }

    // --- prompt --------------------------------------------------------------

    private String systemPrompt(String modelPersona) {
        if (conversationOnly) {
            // One skill, no tool list. A model shown no tools cannot call one.
            return prompt.conversational(CoreSkills.SMALL_TALK, modelPersona);
        }
        return prompt.build(toolContext(), lastUserText,
            workspace.hasRoot() ? workspace.rootName() : "", prefs.unattended(), modelPersona,
            memory.recallLines(lastUserText, agentManager.activeId(), 5));
    }

    /** What is left of this run, in the shape a child can be given a share of. */
    private AgentBudget budgetLeft(RunGuards guards) {
        int steps = Math.max(0, agentManager.steps(prefs.budgetSteps())
            - guards.toolCallCount());
        int seconds = Math.max(0, agentManager.seconds(prefs.budgetSeconds())
            - (int) (guards.elapsedMillis() / 1000L));
        return new AgentBudget(steps, seconds, prefs.budgetCloudCalls(), 1);
    }

    /**
     * A child agent, asked for its opinion under its own definition.
     *
     * <p>It gets its own system prompt — its skills, its narrowed tool list —
     * and one model call. It does not get to run tools yet: the chat loop is
     * still the only thing that can, and giving a child a second copy of it
     * before that loop is extracted would be two loops that can disagree about
     * what a run has already spent.
     */
    private AgentResult consult(SubAgentSpawner.SubAgentContext child) {
        String previous = agentManager.activeId();
        try {
            agentManager.activate(child.agent.id);
            String system = prompt.build(toolContext(), child.task,
                workspace.hasRoot() ? workspace.rootName() : "", prefs.unattended(), null,
                memory.recallLines(child.task, child.agent.id, 3));
            String answer = askOnce(system + "\n\n" + child.task, activeLocalModel(),
                activeCloudProvider(), new RunGuards(child.budget.steps, child.budget.seconds,
                    child.budget.cloudCalls));
            if (answer == null || answer.trim().isEmpty()) {
                return AgentResult.refused(child.agent.id,
                    child.agent.name + " had nothing to add.");
            }
            return AgentResult.of(child.agent.id, ReadableText.clean(answer, 1200),
                new AgentBudget(1, 0, 1, 0));
        } finally {
            agentManager.activate(previous);
        }
    }

    /** What Luna remembers, by kind, for the screen that shows it. */
    public JSONArray memoryCatalogue() {
        return memory.describe(agentManager.activeId());
    }

    /** Forgets one kind. The person decides what their agent keeps. */
    public int forgetMemory(String kind) {
        return memory.clear(kind, agentManager.activeId());
    }

    /** Something worth keeping about the person or their folder. */
    public void remember(String kind, String text, int importance) {
        if (MemoryKind.isKind(kind) && text != null && !text.trim().isEmpty()) {
            memory.remember(kind, agentManager.activeId(), text.trim(), importance);
        }
    }

    /** Every workflow installed, as data. */
    public JSONArray workflowCatalogue() {
        return workflows.describe();
    }

    /**
     * Runs a workflow instead of a conversation.
     *
     * <p>Same worker thread, same guards, same approval and question machinery,
     * same trace events. From the outside a workflow run and a chat turn are
     * the same kind of thing happening; the difference is only that one of them
     * knows its own steps in advance.
     */
    public boolean startWorkflow(final String id, final JSONObject input) {
        if (running.get() || !workflows.has(id)) {
            return false;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                runWorkflowTurn(workflows.get(id), input);
            }
        });
        return true;
    }

    private void runWorkflowTurn(WorkflowDefinition workflow, JSONObject input) {
        running.set(true);
        stopRequested.set(false);
        finished.set(false);
        waitedMillis.set(0L);
        livePrompt = null;
        long started = System.currentTimeMillis();
        final RunGuards guards = new RunGuards(agentManager.steps(prefs.budgetSteps()),
            agentManager.seconds(prefs.budgetSeconds()), prefs.budgetCloudCalls());
        guards.begin();
        final ToolContext env = toolContext();
        emit("run_started", new JSONObject());
        append("user", "Run: " + workflow.name, null);

        try {
            ModelStore.Entry local = activeLocalModel();
            JSONObject cloud = activeCloudProvider();
            if (local != null && !loadIfNeeded(local)) {
                local = null;
                cloud = failoverProvider();
            }
            WorkflowRun run = new WorkflowEngine(
                new EngineHost(env, guards, local, cloud)).run(workflow, input);
            // A stop that raced the workflow already ended the turn; say the
            // summary only if nothing else did, so the chat never shows two
            // answers for one run.
            if (!finished.compareAndSet(false, true)) {
                return;
            }
            JSONObject done = new JSONObject();
            done.put("text", summarise(run));
            done.put("elapsedMs", System.currentTimeMillis() - started);
            done.put("workMs", System.currentTimeMillis() - started - waitedMillis.get());
            done.put("workflow", run.toJson());
            append("assistant", summarise(run), null);
            emit("run_done", done);
            memory.remember(MemoryKind.EXECUTION, agentManager.activeId(),
                "Ran the " + workflow.name + " workflow: " + run.status(), 45, "workflow");
        } catch (Exception error) {
            errors.record("workflow", error);
            finishWithMessage("That workflow stopped because something went wrong. "
                + "Settings keeps the details.");
        } finally {
            running.set(false);
            pendingApprovalId = "";
            pendingQuestionId = "";
            memory.endOfRun(agentManager.activeId());
            browser.close();
            if (!prefs.keepWarm()) {
                runtime.unload();
            }
        }
    }

    /** One plain sentence about how a workflow run ended. */
    private String summarise(WorkflowRun run) {
        if (run.ok()) {
            String message = run.message();
            return message.isEmpty()
                ? "Done — " + run.taken() + " steps." : message;
        }
        String message = run.message();
        return message.isEmpty() ? "That workflow ended early." : message;
    }

    /**
     * The workflow engine's window onto this device.
     *
     * <p>Every route out goes through something that already exists: the tool
     * registry with its watchdog, the approval wait, the question wait, the
     * model call. A workflow gets no power the chat loop does not have.
     */
    private final class EngineHost implements WorkflowHost {

        private final ToolContext env;
        private final RunGuards guards;
        private final ModelStore.Entry local;
        private final JSONObject cloud;

        EngineHost(ToolContext env, RunGuards guards, ModelStore.Entry local, JSONObject cloud) {
            this.env = env;
            this.guards = guards;
            this.local = local;
            this.cloud = cloud;
        }

        @Override
        public String think(String prompt) {
            if (local == null && cloud == null) {
                return "";
            }
            String answer = askOnce(prompt, local, cloud, guards);
            return answer == null ? "" : answer;
        }

        @Override
        public ToolResult tool(String toolId, JSONObject args) {
            if (!agentManager.canUse(toolId)) {
                return ToolResult.denied(toolId);
            }
            String path = args.optString("path", args.optString("url", ""));
            if (tools.needsFolder(toolId) && !workspace.hasRoot()) {
                emitStep(toolId, path, "no_folder");
                return ToolResult.failed("No folder is granted.");
            }
            RunGuards.Verdict verdict = guards.check(toolId, args, tools.mutating(toolId));
            if (verdict.replay != null) {
                emitStep(toolId, path, "replayed");
                return ToolResult.ok(verdict.replay);
            }
            if (!verdict.allowed) {
                emitStep(toolId, path, "blocked", reasonStep(verdict.reason));
                return ToolResult.failed(verdict.reason);
            }
            if (ToolPolicy.decide(toolId, path, prefs) == ToolPolicy.Decision.ASK) {
                emitStep(toolId, path, "held");
                if (!requestApproval(toolId, path, args.optString("content", ""))) {
                    emitStep(toolId, path, "declined");
                    return ToolResult.denied(toolId);
                }
            }
            emitStep(toolId, path, "running");
            ToolResult result = tools.run(env, toolId, args, watchdog());
            guards.record(toolId, args, result.observation);
            emitStep(toolId, path, result.timedOut ? "unfinished" : (result.ok ? "done" : "failed"),
                result.ok ? null : reasonStep(result.observation));
            return result;
        }

        @Override
        public boolean approve(String message, String consequence) {
            emitStep("workflow", "", "held");
            return requestApproval("workflow", "", message,
                consequence.isEmpty() ? "This step needs your say-so." : consequence, "");
        }

        @Override
        public String ask(String question) {
            return askUser(question);
        }

        @Override
        public String subAgent(String agentId, String task) {
            AgentResult result = spawner.spawn(agentManager.active(), agentId, task,
                budgetLeft(guards), environments.capabilities());
            emitStep("sub_agent", agentId, result.ok ? "done" : "denied");
            return result.observation();
        }

        @Override
        public boolean pause(long millis) {
            long deadline = System.currentTimeMillis() + millis;
            while (System.currentTimeMillis() < deadline) {
                if (stopRequested.get()) {
                    return false;
                }
                try {
                    Thread.sleep(Math.min(250L, deadline - System.currentTimeMillis()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }

        @Override
        public boolean stopped() {
            return stopRequested.get();
        }

        @Override
        public void event(JSONObject event) {
            emit("step", event);
        }
    }

    /** Everything installed from outside this build. */
    public JSONArray pluginCatalogue() {
        return pluginManager.describe();
    }

    /** Installs a plugin. Returns null on success, or why it was refused. */
    public String installPlugin(JSONObject manifest) {
        String refusal = pluginManager.install(manifest);
        errors.note("plugins", refusal == null
            ? "installed " + manifest.optString("id", "?")
            : "refused " + manifest.optString("id", "?") + " — " + refusal);
        return refusal;
    }

    /** Removes one. Its knowledge is gone from the next run onwards. */
    public boolean removePlugin(String id) {
        return pluginManager.remove(id);
    }

    /** Every agent installed, as data. Luna is one of them. */
    public JSONArray agentCatalogue() {
        return agents.describe();
    }

    public String activeAgentId() {
        return agentManager.activeId();
    }

    /** Switches agent between runs. An unknown id changes nothing. */
    public boolean activateAgent(String id) {
        if (isRunning() || !agentManager.activate(id)) {
            return false;
        }
        prefs.setActiveAgentId(id);
        return true;
    }

    /** Every skill this agent has, as data, for the UI and for a manifest. */
    public JSONArray skillCatalogue() {
        return skills.describe();
    }

    /** Turns a skill off. The person decides what their agent is told. */
    public void setSkillsDisabled(java.util.List<String> ids) {
        prefs.setDisabledSkills(ids);
        skills.disable(ids);
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
            for (JSONObject message : tailTurns(transcript, CLOUD_HISTORY_TURNS,
                CLOUD_HISTORY_CHARS)) {
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

    /**
     * The newest turns of a conversation, for the cloud model.
     *
     * <p>A turn is one thing the person asked, with everything said after it —
     * the tool results and the reply — up to the next question. At least the
     * last {@code maxTurns} turns are kept, bounded only by the character
     * budget that keeps the request inside the provider's window. Older turns,
     * and the tool results that belong to them, are dropped first.
     */
    static List<JSONObject> tailTurns(List<JSONObject> messages, int maxTurns, int budgetChars) {
        List<JSONObject> out = new ArrayList<>();
        int turns = 0;
        int chars = 0;
        for (int index = messages.size() - 1; index >= 0; index--) {
            JSONObject message = messages.get(index);
            if (turns >= maxTurns) {
                break;
            }
            int cost = message.optString("content").length();
            if (chars + cost > budgetChars && !out.isEmpty()) {
                break;
            }
            out.add(0, message);
            chars += cost;
            if (message.optString("role").equals("user")) {
                turns++;
            }
        }
        // A tool result with nothing before it in the window is meaningless.
        while (!out.isEmpty()
            && out.get(0).optString("role").equals("observation")) {
            out.remove(0);
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

    /**
     * When a model wraps its sentence in one JSON object that names no tool —
     * {@code {"text": ...}}, {@code {"answer": ...}} — the sentence inside is
     * the answer. Returns the first non-empty text-ish field, or empty.
     */
    static String wrappedText(String raw) {
        try {
            JSONObject json = new JSONObject(raw);
            String[] fields = {
                "text", "answer", "response", "content", "message", "reply", "output", "result",
            };
            for (String field : fields) {
                String value = json.optString(field, "").trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        } catch (JSONException notAnObject) {
            // Not a single JSON object; there is nothing to unwrap.
        }
        return "";
    }

    /**
     * Everything in a reply that is not a balanced {@code {...}} object. The
     * sentence a model wrote around a tool call survives; the JSON does not.
     */
    static String proseOf(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < raw.length()) {
            int start = raw.indexOf('{', index);
            if (start < 0) {
                out.append(raw, index, raw.length());
                break;
            }
            out.append(raw, index, start).append(' ');
            int end = balancedEnd(raw, start);
            if (end < 0) {
                out.append(raw, start, raw.length());
                break;
            }
            index = end + 1;
        }
        return out.toString().replaceAll("\\s+", " ").trim();
    }

    /** The '}' that closes the '{' at start, or -1 while still unbalanced. */
    private static int balancedEnd(String text, int start) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = start; index < text.length(); index++) {
            char symbol = text.charAt(index);
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
                    return index;
                }
            }
        }
        return -1;
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
        emitStep(tool, path, state, null);
    }

    private void emitStep(String tool, String path, String state, JSONObject extra) {
        JSONObject event = new JSONObject();
        try {
            event.put("tool", tool);
            event.put("path", path);
            event.put("state", state);
            if (extra != null) {
                for (java.util.Iterator<String> keys = extra.keys(); keys.hasNext(); ) {
                    String key = keys.next();
                    if (!"tool".equals(key) && !"path".equals(key) && !"state".equals(key)) {
                        event.put(key, extra.get(key));
                    }
                }
            }
        } catch (JSONException ignored) {
            return;
        }
        emit("step", event);
    }

    /** A step event carrying the real reason, so the trace can show it. */
    private static JSONObject reasonStep(String reason) {
        JSONObject extra = new JSONObject();
        try {
            extra.put("detail", ReadableText.clean(reason, 160));
        } catch (JSONException ignored) {
            // The step still lands; it just carries no explanation.
        }
        return extra;
    }

    /**
     * Runs one tool with a time limit. Returns null when it ran out of time.
     *
     * The tool keeps its own thread, so a page that never loads or a file
     * system call that blocks cannot freeze the turn — the loop takes the
     * timeout as a failed step and carries on with what it knows.
     */
    private ToolRegistry.Watchdog watchdog() {
        return new ToolRegistry.Watchdog() {
            @Override
            public ToolResult call(final ToolRegistry.Job job, long timeoutMs) {
                java.util.concurrent.Future<ToolResult> pending = toolRunner.submit(
                    new java.util.concurrent.Callable<ToolResult>() {
                        @Override
                        public ToolResult call() {
                            return job.run();
                        }
                    });
                try {
                    return pending.get(timeoutMs <= 0 ? TOOL_TIMEOUT_MS : timeoutMs,
                        TimeUnit.MILLISECONDS);
                } catch (java.util.concurrent.TimeoutException timeout) {
                    pending.cancel(true);
                    errors.record("tool", "timed out after " + timeoutMs + "ms");
                    return null;
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    pending.cancel(true);
                    return null;
                } catch (java.util.concurrent.ExecutionException failure) {
                    errors.record("tool", String.valueOf(failure.getCause()));
                    return ToolResult.failed(
                        ReadableText.clean(String.valueOf(failure.getCause()), 160));
                }
            }
        };
    }

    /** Who is running, where, and with what in front of them. */
    private ToolContext toolContext() {
        return environments.contextFor(agentManager.activeId(), errors);
    }

    /** Everywhere work could run, and what is wrong with each. */
    public JSONArray environmentCatalogue() {
        return environments.describe();
    }

    /** What the UI lists, straight from the definitions. */
    public java.util.List<String> toolIds(boolean mutating) {
        return tools.idsByRisk(mutating);
    }

    /** Every tool the runtime can reach, as data. */
    public JSONArray toolCatalogue() {
        return tools.describe();
    }

    /** The live question, for a UI that has just woken up or missed an event. */
    public JSONObject pendingPrompt() {
        return livePrompt;
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
