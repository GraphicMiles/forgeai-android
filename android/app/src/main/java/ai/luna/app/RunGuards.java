package ai.luna.app;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The limits on one job.
 *
 * Three separate things, all of which have bitten this app before: a model that
 * calls the same tool forever, a job that never ends, and a cloud model that
 * quietly runs up a bill. Each guard can only ever narrow what is allowed —
 * nothing here hands permission back.
 *
 * The ledger is the fourth part: a record of what already ran in this job, so a
 * repeated read is answered from memory instead of doing the work twice.
 */
public final class RunGuards {

    /** Why a call was refused, or null when it may go ahead. */
    public static final class Verdict {
        public final boolean allowed;
        public final String reason;
        public final String replay;

        private Verdict(boolean allowed, String reason, String replay) {
            this.allowed = allowed;
            this.reason = reason;
            this.replay = replay;
        }

        static Verdict allow() {
            return new Verdict(true, null, null);
        }

        static Verdict refuse(String reason) {
            return new Verdict(false, reason, null);
        }

        static Verdict fromLedger(String recorded) {
            return new Verdict(false, null, recorded);
        }
    }

    /** How many times one read may be replayed before it is called a loop. */
    private static final int REPLAY_CAP = 2;

    /** How many times each identical read has been served from the ledger. */
    private final Map<String, Integer> replayCounts = new HashMap<>();

    private final int maxToolCalls;
    private final long maxRunMillis;
    private final int maxCloudCalls;

    private final Set<String> seen = new HashSet<>();
    private final Map<String, String> ledger = new HashMap<>();
    private final Map<String, Integer> toolCounts = new HashMap<>();

    /** Searches a job may run before the model is told to answer instead. */
    private static final int SEARCH_WEB_CAP = 3;

    private long startedAt;
    private int toolCalls;
    private int cloudCalls;

    public RunGuards(int maxToolCalls, int maxRunSeconds, int maxCloudCalls) {
        this.maxToolCalls = maxToolCalls;
        this.maxRunMillis = (long) maxRunSeconds * 1000L;
        this.maxCloudCalls = maxCloudCalls;
    }

    public void begin() {
        startedAt = System.currentTimeMillis();
        toolCalls = 0;
        cloudCalls = 0;
        seen.clear();
        ledger.clear();
    }

    public long elapsedMillis() {
        return System.currentTimeMillis() - startedAt;
    }

    public int toolCallCount() {
        return toolCalls;
    }

    /** Checked before a tool runs. Read-only repeats are replayed, not re-run. */
    public Verdict check(String tool, JSONObject args, boolean mutating) {
        if (elapsedMillis() > maxRunMillis) {
            return Verdict.refuse("This job hit its time limit of " + (maxRunMillis / 1000) + " seconds.");
        }
        if (toolCalls >= maxToolCalls) {
            return Verdict.refuse("This job hit its limit of " + maxToolCalls + " tool calls.");
        }
        // A model that rephrases the query forever is the search-tool loop:
        // each new wording dodges the replay guard below, so the count is
        // capped outright. After the cap the model is told to answer with what
        // it has rather than to keep looking.
        if (tool.equals("search_web")) {
            Integer ran = toolCounts.get(tool);
            if (ran != null && ran >= SEARCH_WEB_CAP) {
                return Verdict.refuse("You have run search_web " + SEARCH_WEB_CAP
                    + " times in this job. Stop searching and answer with what you already "
                    + "have, or ask the user how they want to proceed.");
            }
        }

        String signature = signature(tool, args);
        if (!mutating && ledger.containsKey(signature)) {
            // Handing back the same answer forever is its own loop: the model
            // asks again because the answer did not help, and gets it again.
            // After a few rounds the replay stops being a service and starts
            // being a wall, so say so in words the model can act on.
            Integer replays = replayCounts.merge(signature, 1, Integer::sum);
            if (replays > REPLAY_CAP) {
                return Verdict.refuse("You have already read " + tool
                    + " with those arguments " + replays + " times in this job and the answer "
                    + "has not changed. It does not contain what you are looking for. Do "
                    + "something different, or tell the user what you could not find "
                    + "rather than guessing.");
            }
            return Verdict.fromLedger(ledger.get(signature));
        }
        if (seen.contains(signature)) {
            return Verdict.refuse("You already ran " + tool + " with those arguments in this job. "
                + "Use what it returned, or do something different.");
        }
        return Verdict.allow();
    }

    /** Called after a tool has actually run. */
    public void record(String tool, JSONObject args, String observation) {
        String signature = signature(tool, args);
        seen.add(signature);
        toolCalls++;
        toolCounts.merge(tool, 1, Integer::sum);
        if (observation != null && observation.length() < 4000) {
            ledger.put(signature, observation);
        }
    }

    /** Cloud calls are counted separately: they are the ones that cost money. */
    public Verdict checkCloudCall() {
        if (cloudCalls >= maxCloudCalls) {
            return Verdict.refuse("This job already called the cloud model " + maxCloudCalls
                + " times. Stopping before it costs more.");
        }
        return Verdict.allow();
    }

    public void recordCloudCall() {
        cloudCalls++;
    }

    /** One line the UI can show, so the limits are never a surprise. */
    public String describe() {
        return toolCalls + " of " + maxToolCalls + " steps, "
            + (elapsedMillis() / 1000) + "s of " + (maxRunMillis / 1000) + "s";
    }

    private static String signature(String tool, JSONObject args) {
        return tool + "|" + (args == null ? "{}" : args.toString());
    }
}
