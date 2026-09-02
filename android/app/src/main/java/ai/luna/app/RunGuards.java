package ai.luna.app;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
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

    /** How many identical failures before the call is refused outright. */
    private static final int FAILURE_CAP = 2;

    /** How many times each exact call has failed. */
    private final Map<String, Integer> failureCounts = new HashMap<>();

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
        // These three were left standing across runs, so a job could inherit
        // the previous job's counts and refuse a call the model had not yet
        // made. Everything a guard counts belongs to one job only.
        replayCounts.clear();
        toolCounts.clear();
        failureCounts.clear();
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

        // A call that keeps failing the same way is the loop the ledger cannot
        // see: a mutating tool is never replayed, so nothing above counts it.
        // The model rewrites one argument, fails identically, and tries again
        // until the step budget is gone. Two identical failures is enough to
        // know the third will not be different.
        Integer failures = failureCounts.get(signature);
        if (failures != null && failures >= FAILURE_CAP) {
            return Verdict.refuse("Calling " + tool + " with those arguments has failed "
                + failures + " times in this job with the same result. Do not send it again. "
                + "Change the approach -- read the file or list the folder to find out what is "
                + "actually there -- or tell the user what is blocking you.");
        }
        return Verdict.allow();
    }

    /** Called after a tool has actually run. */
    public void record(String tool, JSONObject args, String observation) {
        String signature = signature(tool, args);
        toolCalls++;
        toolCounts.merge(tool, 1, Integer::sum);

        if (failed(observation)) {
            // A failure is not an answer, so it must not go in the ledger to be
            // replayed as one, and the signature must stay callable: the model
            // deserves a second attempt at something that might be transient.
            // It does not deserve a third.
            failureCounts.merge(signature, 1, Integer::sum);
            return;
        }

        seen.add(signature);
        if (observation != null && observation.length() < 4000) {
            ledger.put(signature, observation);
        }
    }

    /**
     * Whether an observation is a failure rather than an answer.
     *
     * <p>Every tool in this app reports trouble in words, not exceptions, so
     * this reads the words. It is deliberately conservative: treating a real
     * answer as a failure would let the same read run twice, which merely
     * wastes a step, whereas the reverse re-serves an error as though it were
     * content.
     */
    private static boolean failed(String observation) {
        if (observation == null) {
            return true;
        }
        String text = observation.trim();
        if (text.isEmpty()) {
            return true;
        }
        return text.startsWith("Failed:")
            || text.startsWith("There is no ")
            || text.startsWith("Could not ")
            || text.startsWith("Give me ")
            || text.startsWith("That repository address was refused")
            || text.startsWith("The folder permission was withdrawn");
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
        return tool + "|" + canonical(args);
    }

    /**
     * The arguments in a form where two identical calls always look identical.
     *
     * <p>{@code JSONObject.toString()} is not stable: it emits keys in hash
     * order, so the same call written by the model in a different key order
     * produces a different string. That silently defeated every guard below --
     * the repeat looked new, so it ran again. Sorting the keys fixes the loop
     * that mattered.
     *
     * <p>Values are normalised too, since a model retrying a failed call tends
     * to jiggle the formatting rather than the substance: {@code "10"} and
     * {@code 10} are the same limit, and a padded path is the same path.
     */
    private static String canonical(JSONObject args) {
        if (args == null || args.length() == 0) {
            return "{}";
        }
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = args.keys(); it.hasNext(); ) {
            keys.add(it.next());
        }
        Collections.sort(keys);

        StringBuilder out = new StringBuilder("{");
        for (int index = 0; index < keys.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            String key = keys.get(index);
            out.append(key).append('=').append(normalise(args.opt(key)));
        }
        return out.append('}').toString();
    }

    /** One argument value, stripped of differences that do not change meaning. */
    private static String normalise(Object value) {
        if (value == null || value == JSONObject.NULL) {
            return "";
        }
        if (value instanceof Boolean) {
            return value.toString();
        }
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            // A whole number is the same whether it arrived as 10, 10.0 or "10".
            return number == Math.rint(number) && !Double.isInfinite(number)
                ? String.valueOf((long) number) : String.valueOf(number);
        }
        String text = value.toString().trim();
        // A number that arrived as a string is still that number.
        if (text.matches("-?\\d+")) {
            try {
                return String.valueOf(Long.parseLong(text));
            } catch (NumberFormatException ignored) {
                return text;
            }
        }
        return text;
    }
}
