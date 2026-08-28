package ai.luna.runtime;

import ai.luna.contracts.AgentBudget;
import ai.luna.contracts.AgentDefinition;
import ai.luna.contracts.AgentResult;
import ai.luna.contracts.Capability;

import java.util.ArrayList;
import java.util.List;

/**
 * Handing a piece of work to another agent.
 *
 * <p>This is the feature most likely to eat a phone battery, a cloud bill and a
 * person's trust at the same time, so almost all of it is refusals. A spawn has
 * to survive six questions before anything runs:
 *
 * <ol>
 *   <li>does the child exist;</li>
 *   <li>is the parent allowed to spawn at all ({@code agent.spawn});</li>
 *   <li>is there depth left — a child of a child of a child is a runaway;</li>
 *   <li>is this a loop: an agent spawning itself, or an ancestor;</li>
 *   <li>is there budget left to give away;</li>
 *   <li>does the child stay inside the parent's tools — a narrow agent must not
 *       be a way of getting hold of a wide one.</li>
 * </ol>
 *
 * <p>The work itself happens through {@link Runner}, which the engine supplies.
 * The spawner never talks to a model.
 */
public final class SubAgentSpawner {

    /** Runs one child, having been told it is allowed. */
    public interface Runner {
        AgentResult run(SubAgentContext context);
    }

    /** What a child is given: who asked, what for, and what it may spend. */
    public static final class SubAgentContext {

        public final String parentId;
        public final AgentDefinition agent;
        public final String task;
        public final AgentBudget budget;

        /** How many agents deep this is. The first child is 1. */
        public final int depth;

        /** Every agent above this one, so a loop can be seen. */
        public final List<String> ancestors;

        SubAgentContext(String parentId, AgentDefinition agent, String task, AgentBudget budget,
                        int depth, List<String> ancestors) {
            this.parentId = parentId;
            this.agent = agent;
            this.task = task;
            this.budget = budget;
            this.depth = depth;
            this.ancestors = ancestors;
        }
    }

    private final AgentRegistry agents;
    private final Runner runner;

    /** Agents currently running, innermost last. */
    private final List<String> stack = new ArrayList<>();

    public SubAgentSpawner(AgentRegistry agents, Runner runner) {
        this.agents = agents;
        this.runner = runner;
    }

    /**
     * Runs a child, or refuses in a sentence the parent can pass on.
     *
     * @param parent  the agent asking
     * @param childId the agent being asked
     * @param task    what it is being asked to do
     * @param left    what the parent still has to spend
     * @param granted capabilities the environment gave the parent
     */
    public AgentResult spawn(AgentDefinition parent, String childId, String task,
                             AgentBudget left, List<String> granted) {
        if (parent == null) {
            return AgentResult.refused(childId, "There is no agent asking for that.");
        }
        if (task == null || task.trim().isEmpty()) {
            return AgentResult.refused(childId, "There is nothing for another agent to do.");
        }
        if (granted != null && !granted.isEmpty() && !granted.contains(Capability.AGENT_SPAWN)) {
            return AgentResult.refused(childId,
                "Nothing here is allowed to hand work to another agent.");
        }
        AgentDefinition child = agents.get(childId);
        if (child == null) {
            return AgentResult.refused(childId, "There is no agent called " + childId + ".");
        }
        if (child.id.equals(parent.id)) {
            return AgentResult.refused(childId, parent.name + " cannot hand work to itself.");
        }
        if (stack.contains(child.id)) {
            return AgentResult.refused(childId,
                child.name + " is already working on something further up.");
        }
        if (left == null || !left.maySpawn()) {
            return AgentResult.refused(childId, left != null && left.exhausted()
                ? "There is nothing left of this job's budget to give away."
                : "That is already as many agents deep as this job may go.");
        }
        String wider = widerThanParent(parent, child);
        if (wider != null) {
            return AgentResult.refused(childId, child.name + " wants " + wider
                + ", which " + parent.name + " does not have to give.");
        }

        // A child gets a third of what is left, and one level less depth. A
        // third rather than all of it because the parent still has to finish
        // the job and tell somebody about it.
        AgentBudget budget = left.forChild(
            Math.max(1, left.steps / 3), Math.max(5, left.seconds / 3),
            Math.max(0, left.cloudCalls / 3));
        if (child.maxSteps > 0) {
            budget = budget.narrow(new AgentBudget(child.maxSteps,
                child.maxSeconds > 0 ? child.maxSeconds : budget.seconds,
                budget.cloudCalls, budget.depth));
        }

        List<String> ancestors = new ArrayList<>(stack);
        ancestors.add(parent.id);
        // The agent at the top of the tree is on the stack too, or a
        // grandchild could ask its grandparent to do the work again.
        boolean rooted = stack.isEmpty();
        if (rooted) {
            stack.add(parent.id);
        }
        stack.add(child.id);
        try {
            AgentResult result = runner.run(new SubAgentContext(parent.id, child, task.trim(),
                budget, ancestors.size(), ancestors));
            return result == null
                ? AgentResult.refused(childId, child.name + " came back with nothing.")
                : result;
        } catch (Throwable error) {
            // A child that throws is a child that failed, not a run that ended.
            String message = error.getMessage();
            return AgentResult.refused(childId, child.name + " stopped: "
                + (message == null ? String.valueOf(error) : message));
        } finally {
            stack.remove(child.id);
            if (rooted) {
                stack.remove(parent.id);
            }
        }
    }

    /** How deep the runtime currently is. Zero when nothing is nested. */
    public int depth() {
        return stack.size();
    }

    /**
     * The tool a child wants that the parent does not have, or null.
     *
     * <p>Without this, a locked-down agent could reach anything simply by
     * asking a wider one to do it — which is how privilege escalation reads in
     * every system that has ever had it.
     */
    private String widerThanParent(AgentDefinition parent, AgentDefinition child) {
        if (parent.tools.isEmpty() || parent.tools.contains(AgentDefinition.ALL)) {
            return null;
        }
        if (child.tools.isEmpty() || child.tools.contains(AgentDefinition.ALL)) {
            return "every tool";
        }
        for (String tool : child.tools) {
            if (!parent.allows(tool)) {
                return tool;
            }
        }
        return null;
    }
}
