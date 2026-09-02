package ai.luna.app;

import org.json.JSONObject;

/**
 * The limits on one job, and the loops they are supposed to catch.
 *
 * <p>Three of these check things that were silently broken: a signature that
 * changed when the model wrote the same arguments in a different order, a
 * failing write that could repeat until the budget was gone, and counters that
 * survived into the next job.
 */
public final class RunGuardsTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        signatures();
        repeatedReads();
        repeatedFailures();
        transientFailures();
        freshRuns();
        budgets();
        recoveryMessagesCount();

        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " FAILED, " + passed + " passed");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    // --- the bug that let every other guard be dodged -------------------------

    private static void signatures() {
        RunGuards guards = fresh();

        // The same call, written with the keys in the other order. org.json
        // emits keys in hash order, so this used to hash differently and slip
        // past the replay guard entirely.
        JSONObject first = new JSONObject();
        first.put("zebra", "v");
        first.put("alpha", "v");
        first.put("month", "v");
        first.put("path", "v");

        JSONObject second = new JSONObject();
        second.put("path", "v");
        second.put("month", "v");
        second.put("alpha", "v");
        second.put("zebra", "v");

        guards.record("read_file", first, "the contents");
        RunGuards.Verdict verdict = guards.check("read_file", second, false);
        check("key order does not change a call's identity", verdict.replay != null);
        check("and the remembered answer comes back",
            verdict.replay != null && verdict.replay.equals("the contents"));

        // Formatting drift in the values, likewise.
        RunGuards other = fresh();
        JSONObject asNumber = new JSONObject();
        asNumber.put("path", "notes.txt");
        asNumber.put("limit", 10);
        JSONObject asText = new JSONObject();
        asText.put("path", " notes.txt ");
        asText.put("limit", "10");
        other.record("read_file", asNumber, "same file");
        check("a padded path and a stringly number are the same call",
            other.check("read_file", asText, false).replay != null);
    }

    private static void repeatedReads() {
        RunGuards guards = fresh();
        JSONObject args = path("notes.txt");

        guards.record("read_file", args, "nothing useful");
        check("the first repeat is replayed",
            guards.check("read_file", args, false).replay != null);
        check("the second repeat is replayed",
            guards.check("read_file", args, false).replay != null);

        RunGuards.Verdict third = guards.check("read_file", args, false);
        check("the third repeat is refused as a loop", !third.allowed && third.replay == null);
        check("and the refusal tells the model to change course",
            third.reason.contains("something different"));
    }

    // --- the loop the ledger could not see ------------------------------------

    private static void repeatedFailures() {
        RunGuards guards = fresh();
        JSONObject args = path("Alarms/life.js");

        // A mutating call is never replayed, so nothing used to count this.
        guards.record("write_file", args, "Failed: there is nothing at that path.");
        check("one failure does not block a retry",
            guards.check("write_file", args, true).allowed);

        guards.record("write_file", args, "Failed: there is nothing at that path.");
        RunGuards.Verdict verdict = guards.check("write_file", args, true);
        check("the same failure twice stops the third attempt", !verdict.allowed);
        check("and the model is told to go and look",
            verdict.reason.contains("read the file or list the folder"));
        check("and told not to resend it", verdict.reason.contains("Do not send it again"));

        // Changing the arguments is a genuinely different attempt.
        check("a different path is still allowed",
            guards.check("write_file", path("life.js"), true).allowed);
    }

    private static void transientFailures() {
        RunGuards guards = fresh();
        JSONObject args = path("notes.txt");

        // A failure must never be remembered as though it were the file.
        guards.record("read_file", args, "Failed: it took too long to answer.");
        RunGuards.Verdict verdict = guards.check("read_file", args, false);
        check("a failed read is not replayed as content", verdict.replay == null);
        check("and it may be tried once more", verdict.allowed);

        // Once it works, the real answer is what gets kept.
        guards.record("read_file", args, "the actual contents");
        check("the successful answer is the one remembered",
            "the actual contents".equals(guards.check("read_file", args, false).replay));

        RunGuards empty = fresh();
        empty.record("read_page", new JSONObject(), "");
        check("an empty observation counts as a failure",
            empty.check("read_page", new JSONObject(), false).replay == null);
    }

    private static void freshRuns() {
        RunGuards guards = fresh();
        JSONObject args = path("notes.txt");

        // Exhaust every counter.
        guards.record("write_file", args, "Failed: no.");
        guards.record("write_file", args, "Failed: no.");
        check("the failure cap is reached", !guards.check("write_file", args, true).allowed);

        guards.begin();
        check("a new job forgets the old job's failures",
            guards.check("write_file", args, true).allowed);

        RunGuards replayed = fresh();
        replayed.record("read_file", args, "x");
        replayed.check("read_file", args, false);
        replayed.check("read_file", args, false);
        replayed.check("read_file", args, false);
        replayed.begin();
        replayed.record("read_file", args, "x");
        check("a new job forgets the old job's replay count",
            replayed.check("read_file", args, false).replay != null);
    }

    private static void budgets() {
        RunGuards guards = new RunGuards(2, 60, 5);
        guards.begin();
        guards.record("read_file", path("a"), "one");
        guards.record("read_file", path("b"), "two");
        RunGuards.Verdict verdict = guards.check("read_file", path("c"), false);
        check("the tool-call budget is enforced", !verdict.allowed);
        check("and it says so as a limit", verdict.reason.contains("limit"));
    }

    /**
     * The two halves have to agree on what a failure looks like.
     *
     * <p>Recovery writes the failure messages; RunGuards decides which
     * observations are failures by reading their wording. If Recovery is ever
     * reworded without RunGuards being told, failures silently stop being
     * counted and the repeat guard quietly switches itself off -- so the
     * agreement is asserted rather than assumed.
     */
    private static void recoveryMessagesCount() {
        Throwable[] every = {
            new java.io.FileNotFoundException("/storage/emulated/0/x"),
            new SecurityException("Permission denied"),
            new java.net.SocketTimeoutException("timed out"),
            new java.net.UnknownHostException("nowhere.example"),
            new java.io.IOException("ENOSPC: no space left"),
            new java.io.IOException("Network is unreachable"),
            new IllegalStateException("something unrecognised"),
            new OutOfMemoryError("heap"),
        };
        RunGuards guards = fresh();
        JSONObject args = path("notes.txt");
        for (Throwable error : every) {
            String message = Recovery.from("read_file", error);
            guards.begin();
            guards.record("read_file", args, message);
            check("a " + error.getClass().getSimpleName() + " message is treated as a failure",
                guards.check("read_file", args, false).replay == null);
        }

        // And a call to a tool that does not exist is likewise not an answer.
        guards.begin();
        guards.record("reed_file", args,
            Recovery.unknownTool("reed_file", java.util.Arrays.asList("read_file")));
        check("an unknown-tool message is treated as a failure",
            guards.check("reed_file", args, false).replay == null);

        // The other direction: ordinary content must not be mistaken for one.
        guards.begin();
        guards.record("read_file", args, "There is nothing wrong with this file's contents.");
        check("prose that merely resembles a failure is still content",
            guards.check("read_file", args, false).replay != null);
    }

    // --- helpers --------------------------------------------------------------

    private static RunGuards fresh() {
        RunGuards guards = new RunGuards(50, 600, 20);
        guards.begin();
        return guards;
    }

    private static JSONObject path(String value) {
        JSONObject args = new JSONObject();
        args.put("path", value);
        return args;
    }

    private static void check(String what, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  pass  " + what);
        } else {
            failed++;
            System.out.println("  FAIL  " + what);
        }
    }
}
