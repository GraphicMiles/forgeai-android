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

    private final int maxToolCalls;
    private final long maxRunMillis;
    private final int maxCloudCalls;

    private final Set<String> seen = new HashSet<>();
    private final Map<String, String> ledger = new HashMap<>();

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

        String signature = signature(tool, args);
        if (!mutating && ledger.containsKey(signature)) {
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
