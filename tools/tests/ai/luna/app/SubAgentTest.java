package ai.luna.app;

import ai.luna.builtin.LunaAgent;
import ai.luna.contracts.AgentBudget;
import ai.luna.contracts.AgentDefinition;
import ai.luna.contracts.AgentResult;
import ai.luna.contracts.Capability;
import ai.luna.runtime.AgentRegistry;
import ai.luna.runtime.SubAgentSpawner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Handing work to another agent.
 *
 * <p>Almost every check here is a refusal, which is the right shape for this
 * feature: the ways sub-agents go wrong are a runaway family tree, a budget
 * that grows on the way down, and a locked-down agent reaching things it was
 * never given by asking a wider one to fetch them.
 */
public final class SubAgentTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        budgets();
        theOrdinaryCase();
        depth();
        loops();
        escalation();
        permission();
        misbehaviour();
        results();

        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " FAILED, " + passed + " passed");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static void budgets() {
        AgentBudget parent = AgentBudget.of(12, 600, 6);
        check("a budget narrows to the smaller of two",
            parent.narrow(AgentBudget.of(4, 60, 1)).steps == 4);
        check("and never to the larger",
            parent.narrow(new AgentBudget(90, 9000, 90, 9)).steps == 12);
        check("a child gets less depth", parent.forChild(4, 60, 1).depth == 0);
        check("and no more than is left", parent.forChild(90, 90, 90).steps == 12);
        check("what is spent comes off", parent.minus(AgentBudget.of(4, 60, 1)).steps == 8);
        check("an empty budget is exhausted", AgentBudget.none().exhausted());
        check("and may not spawn", !AgentBudget.none().maySpawn());
        check("a budget with no depth left may not either",
            !new AgentBudget(10, 10, 10, 0).maySpawn());
        check("a budget survives a round trip",
            AgentBudget.fromJson(parent.toJson()).seconds == 600);
    }

    private static void theOrdinaryCase() {
        Recorder runner = new Recorder();
        SubAgentSpawner spawner = spawner(runner);
        AgentResult result = spawner.spawn(LunaAgent.DEFINITION, "acme.reader",
            "Summarise the March invoice", AgentBudget.of(12, 600, 6), granted());

        check("the child runs", result.ok);
        check("and answers as itself", result.agentId.equals("acme.reader"));
        check("the task reaches it", runner.lastTask.equals("Summarise the March invoice"));
        check("it is told who asked", runner.lastParent.equals("luna"));
        check("it is one level deep", runner.lastDepth == 1);
        check("it gets a share of the budget, not all of it",
            runner.lastBudget.steps == 4 && runner.lastBudget.steps < 12);
        check("and cannot spawn again", runner.lastBudget.depth == 0);
        check("nothing is left running afterwards", spawner.depth() == 0);
    }

    private static void depth() {
        SubAgentSpawner spawner = spawner(new Recorder());
        AgentResult tooDeep = spawner.spawn(LunaAgent.DEFINITION, "acme.reader", "Do a thing",
            new AgentBudget(12, 600, 6, 0), granted());
        check("a run with no depth left cannot spawn", !tooDeep.ok);
        check("and says so", tooDeep.refusal.contains("as many agents deep"));

        AgentResult broke = spawner.spawn(LunaAgent.DEFINITION, "acme.reader", "Do a thing",
            AgentBudget.none(), granted());
        check("nor can one with nothing left to spend", !broke.ok);
        check("with a different reason", broke.refusal.contains("nothing left"));
    }

    private static void loops() {
        SubAgentSpawner spawner = spawner(new Recorder());
        AgentResult itself = spawner.spawn(LunaAgent.DEFINITION, "luna", "Do it again",
            AgentBudget.of(12, 600, 6), granted());
        check("an agent cannot hand work to itself", !itself.ok);
        check("and is told why", itself.refusal.contains("cannot hand work to itself"));

        // A child that tries to spawn the agent already running above it.
        final List<AgentResult> nested = new ArrayList<>();
        final SubAgentSpawner[] holder = new SubAgentSpawner[1];
        holder[0] = new SubAgentSpawner(registry(), new SubAgentSpawner.Runner() {
            @Override
            public AgentResult run(SubAgentSpawner.SubAgentContext context) {
                nested.add(holder[0].spawn(context.agent, "luna", "and again",
                    context.budget, granted()));
                return AgentResult.of(context.agent.id, "done", AgentBudget.none());
            }
        });
        holder[0].spawn(LunaAgent.DEFINITION, "acme.reader", "Start", AgentBudget.of(12, 600, 6),
            granted());
        check("a child cannot spawn the agent running above it",
            !nested.get(0).ok);
        check("which is how a loop is refused rather than discovered",
            nested.get(0).refusal.contains("further up"));

        AgentResult unknown = spawner.spawn(LunaAgent.DEFINITION, "acme.nobody", "Do a thing",
            AgentBudget.of(12, 600, 6), granted());
        check("an agent that does not exist is refused plainly",
            !unknown.ok && unknown.refusal.contains("no agent called"));
    }

    /** The security question: can a narrow agent borrow a wide one's reach? */
    private static void escalation() {
        SubAgentSpawner spawner = spawner(new Recorder());
        AgentDefinition narrow = AgentDefinition.of("acme.narrow", "Narrow")
            .tools("read_file", "respond")
            .build();

        AgentResult wide = spawner.spawn(narrow, "acme.writer", "Write this file",
            AgentBudget.of(12, 600, 6), granted());
        check("a narrow agent cannot borrow a wider one", !wide.ok);
        check("and the missing tool is named", wide.refusal.contains("write_file"));

        AgentResult everything = spawner.spawn(narrow, "luna", "Do everything",
            AgentBudget.of(12, 600, 6), granted());
        check("nor Luna herself", !everything.ok);
        check("with the reason spelled out", everything.refusal.contains("every tool"));

        AgentResult narrower = spawner.spawn(narrow, "acme.reader", "Read this",
            AgentBudget.of(12, 600, 6), granted());
        check("but a narrower one is fine", narrower.ok);
    }

    private static void permission() {
        SubAgentSpawner spawner = spawner(new Recorder());
        AgentResult refused = spawner.spawn(LunaAgent.DEFINITION, "acme.reader", "Do a thing",
            AgentBudget.of(12, 600, 6), Arrays.asList(Capability.FILESYSTEM_READ));
        check("an environment that does not allow spawning stops it", !refused.ok);
        check("and says what is not allowed",
            refused.refusal.contains("hand work to another agent"));

        AgentResult empty = spawner.spawn(LunaAgent.DEFINITION, "acme.reader", "  ",
            AgentBudget.of(12, 600, 6), granted());
        check("a child is never spawned with nothing to do", !empty.ok);
    }

    private static void misbehaviour() {
        SubAgentSpawner exploding = new SubAgentSpawner(registry(), new SubAgentSpawner.Runner() {
            @Override
            public AgentResult run(SubAgentSpawner.SubAgentContext context) {
                throw new IllegalStateException("fell over");
            }
        });
        AgentResult result = exploding.spawn(LunaAgent.DEFINITION, "acme.reader", "Do a thing",
            AgentBudget.of(12, 600, 6), granted());
        check("a child that throws is a failed child, not a failed run", !result.ok);
        check("and the parent is told what happened", result.refusal.contains("fell over"));
        check("the stack is left clean", exploding.depth() == 0);

        SubAgentSpawner silent = new SubAgentSpawner(registry(), new SubAgentSpawner.Runner() {
            @Override
            public AgentResult run(SubAgentSpawner.SubAgentContext context) {
                return null;
            }
        });
        check("a child that returns nothing is refused, not believed",
            !silent.spawn(LunaAgent.DEFINITION, "acme.reader", "Do a thing",
                AgentBudget.of(12, 600, 6), granted()).ok);
    }

    private static void results() {
        AgentResult ok = AgentResult.of("acme.reader", "Three invoices, all paid.",
            AgentBudget.of(2, 30, 1));
        check("a result carries something the parent can use",
            ok.observation().equals("Three invoices, all paid."));
        check("and what it cost", ok.spent.steps == 2);
        check("it serialises", ok.toJson().optBoolean("ok"));

        AgentResult no = AgentResult.refused("acme.reader", "Nothing to read.");
        check("a refusal reads as the reason", no.observation().equals("Nothing to read."));
        check("and spent nothing", no.spent.steps == 0);
    }

    // --- helpers --------------------------------------------------------------

    private static SubAgentSpawner spawner(SubAgentSpawner.Runner runner) {
        return new SubAgentSpawner(registry(), runner);
    }

    private static AgentRegistry registry() {
        AgentRegistry registry = new AgentRegistry().register(LunaAgent.DEFINITION);
        registry.register(AgentDefinition.of("acme.reader", "Reader")
            .tools("read_file", "respond")
            .budget(6, 120)
            .build());
        registry.register(AgentDefinition.of("acme.writer", "Writer")
            .tools("read_file", "write_file", "respond")
            .build());
        return registry;
    }

    private static List<String> granted() {
        return Arrays.asList(Capability.AGENT_SPAWN, Capability.FILESYSTEM_READ);
    }

    private static final class Recorder implements SubAgentSpawner.Runner {

        String lastTask = "";
        String lastParent = "";
        int lastDepth;
        AgentBudget lastBudget = AgentBudget.none();

        @Override
        public AgentResult run(SubAgentSpawner.SubAgentContext context) {
            lastTask = context.task;
            lastParent = context.parentId;
            lastDepth = context.depth;
            lastBudget = context.budget;
            return AgentResult.of(context.agent.id, "Had a look.", AgentBudget.of(1, 5, 1));
        }
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
