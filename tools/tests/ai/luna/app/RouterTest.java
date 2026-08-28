package ai.luna.app;

import ai.luna.runtime.InferenceRouter;

import java.util.ArrayList;
import java.util.List;

/**
 * Choosing which brain answers.
 *
 * <p>The old rule — local, then cloud if it will not load — is still the common
 * path, so most of these checks are about the cases it never handled: a job
 * bigger than the phone's window, a person who has not allowed anything to
 * leave, and a provider that has been failing for the last two minutes.
 */
public final class RouterTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        theOrdinaryCase();
        size();
        privacy();
        preference();
        health();
        explanations();
        nothingAvailable();

        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " FAILED, " + passed + " passed");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static void theOrdinaryCase() {
        InferenceRouter router = new InferenceRouter();
        InferenceRouter.Route route = router.choose(both(4096), need(1000, false, "", true));
        check("the phone answers when it can", route.id().equals("local.llamacpp"));
        check("and says why", route.reason.contains("stays here"));

        InferenceRouter.Route cloudOnly = router.choose(cloud(), need(1000, false, "", true));
        check("the cloud answers when there is nothing else",
            cloudOnly.id().startsWith("cloud:"));
        check("and says there is no local model",
            cloudOnly.reason.contains("no model on the phone"));
    }

    private static void size() {
        InferenceRouter router = new InferenceRouter();
        InferenceRouter.Route big = router.choose(both(2048), need(9000, false, "", true));
        check("a job too big for the phone goes to the cloud",
            big.id().startsWith("cloud:"));
        check("and the reason is the size, not a failure",
            big.reason.contains("bigger than the phone's model can hold"));

        InferenceRouter.Route fits = router.choose(both(8192), need(4000, false, "", true));
        check("a job that fits stays local", fits.id().equals("local.llamacpp"));
    }

    private static void privacy() {
        InferenceRouter router = new InferenceRouter();
        InferenceRouter.Route stays = router.choose(both(2048), need(9000, true, "", true));
        check("private work never goes to the cloud, however big", !stays.any());
        check("and says so plainly", stays.reason.contains("stay on the phone"));

        InferenceRouter.Route notAllowed = router.choose(both(2048), need(9000, false, "", false));
        check("nothing leaves when the person has not allowed it", !notAllowed.any());
        check("and the reason names the setting",
            notAllowed.reason.contains("not allowed anything to leave"));

        InferenceRouter.Route small = router.choose(both(8192), need(1000, true, "", false));
        check("private work that fits is simply done here",
            small.id().equals("local.llamacpp"));
    }

    private static void preference() {
        InferenceRouter router = new InferenceRouter();
        InferenceRouter.Route chosen = router.choose(both(8192),
            need(1000, false, "cloud:groq", true));
        check("what the person picked is what runs", chosen.id().equals("cloud:groq"));
        check("and the reason credits them", chosen.reason.startsWith("You chose"));

        InferenceRouter.Route impossible = router.choose(both(8192),
            need(1000, true, "cloud:groq", true));
        check("unless it cannot be honoured", impossible.id().equals("local.llamacpp"));

        InferenceRouter.Route missing = router.choose(both(8192),
            need(1000, false, "cloud:nobody", true));
        check("a preference for something absent falls back quietly",
            missing.id().equals("local.llamacpp"));
    }

    private static void health() {
        InferenceRouter router = new InferenceRouter();
        router.failed("local.llamacpp");
        check("a provider that just failed is resting", router.resting("local.llamacpp"));

        InferenceRouter.Route route = router.choose(both(8192), need(1000, false, "", true));
        check("and is passed over while it rests", route.id().startsWith("cloud:"));

        router.worked("local.llamacpp");
        check("one success wakes it up again", !router.resting("local.llamacpp"));
        check("and it is chosen again",
            router.choose(both(8192), need(1000, false, "", true)).id()
                .equals("local.llamacpp"));

        InferenceRouter alone = new InferenceRouter();
        alone.failed("local.llamacpp");
        InferenceRouter.Route lastResort = alone.choose(local(8192), need(1000, false, "", true));
        check("a failing provider is still used when it is the only one",
            lastResort.id().equals("local.llamacpp"));
        check("and the reason admits it", lastResort.reason.contains("has been failing"));

        alone.failed("local.llamacpp");
        check("the health list counts failures",
            alone.health().optJSONObject(0).optInt("failed") == 2);
        check("and says who is resting", alone.health().optJSONObject(0).optBoolean("resting"));
    }

    private static void explanations() {
        InferenceRouter router = new InferenceRouter();
        for (InferenceRouter.Need need : new InferenceRouter.Need[] {
            need(1000, false, "", true), need(90000, false, "", true),
            need(90000, true, "", true), need(90000, false, "", false)}) {
            InferenceRouter.Route route = router.choose(both(2048), need);
            check("every decision comes with a sentence", !route.reason.isEmpty());
        }
    }

    private static void nothingAvailable() {
        InferenceRouter router = new InferenceRouter();
        InferenceRouter.Route none = router.choose(new ArrayList<InferenceRouter.Candidate>(),
            need(1000, false, "", true));
        check("with nothing set up, nothing is chosen", !none.any());
        check("and the person is told to set something up",
            none.reason.contains("no model set up"));
    }

    // --- helpers --------------------------------------------------------------

    private static List<InferenceRouter.Candidate> both(int localContext) {
        List<InferenceRouter.Candidate> out = local(localContext);
        out.addAll(cloud());
        return out;
    }

    private static List<InferenceRouter.Candidate> local(int context) {
        List<InferenceRouter.Candidate> out = new ArrayList<>();
        out.add(new InferenceRouter.Candidate("local.llamacpp", "Qwen 1.5B", false, context, 0));
        return out;
    }

    private static List<InferenceRouter.Candidate> cloud() {
        List<InferenceRouter.Candidate> out = new ArrayList<>();
        out.add(new InferenceRouter.Candidate("cloud:groq", "Groq", true, 0, 1));
        return out;
    }

    private static InferenceRouter.Need need(int tokens, boolean isPrivate, String preferred,
                                             boolean mayLeave) {
        return new InferenceRouter.Need(tokens, isPrivate, preferred, mayLeave);
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
