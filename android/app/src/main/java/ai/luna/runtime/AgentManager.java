package ai.luna.runtime;

import ai.luna.contracts.AgentDefinition;
import ai.luna.contracts.SkillDefinition;
import ai.luna.contracts.ToolContext;
import ai.luna.contracts.ToolDefinition;

import java.util.ArrayList;
import java.util.List;

/**
 * Which agent is running, and what that means for this turn.
 *
 * <p>The registries know everything the runtime has. The manager narrows that
 * to one agent: these tools, this knowledge, this budget. Narrowing is all it
 * does — an agent cannot ask its way into a capability the environment does not
 * offer, so nothing here can widen what {@link ToolRegistry} already decided.
 */
public final class AgentManager {

    private final AgentRegistry agents;
    private final ToolRegistry tools;
    private final SkillRegistry skills;

    private volatile String activeId = "";

    public AgentManager(AgentRegistry agents, ToolRegistry tools, SkillRegistry skills) {
        this.agents = agents;
        this.tools = tools;
        this.skills = skills;
    }

    public AgentDefinition active() {
        AgentDefinition agent = agents.get(activeId);
        return agent == null ? agents.fallback() : agent;
    }

    public String activeId() {
        AgentDefinition agent = active();
        return agent == null ? "" : agent.id;
    }

    /** Switches agent. An unknown id leaves the current one alone. */
    public boolean activate(String id) {
        if (id == null || !agents.has(id)) {
            return false;
        }
        activeId = id;
        return true;
    }

    /** The tools this agent may use here: available, and on its list. */
    public List<ToolDefinition> tools(ToolContext context) {
        AgentDefinition agent = active();
        List<ToolDefinition> out = new ArrayList<>();
        for (ToolDefinition definition : tools.available(context)) {
            if (agent == null || agent.allows(definition.id)) {
                out.add(definition);
            }
        }
        return out;
    }

    public List<String> toolIds(ToolContext context) {
        List<String> out = new ArrayList<>();
        for (ToolDefinition definition : tools(context)) {
            out.add(definition.id);
        }
        return out;
    }

    /** True when this agent may call this tool at all. */
    public boolean canUse(String toolId) {
        AgentDefinition agent = active();
        return tools.has(toolId) && (agent == null || agent.allows(toolId));
    }

    /** The prompt lines for exactly those tools. */
    public List<String> promptLines(ToolContext context) {
        List<String> out = new ArrayList<>();
        for (ToolDefinition definition : tools(context)) {
            out.add(definition.promptLine());
        }
        return out;
    }

    /** The knowledge this agent has: registered, enabled, and on its list. */
    public List<SkillDefinition> skills() {
        AgentDefinition agent = active();
        List<SkillDefinition> out = new ArrayList<>();
        for (SkillDefinition skill : skills.enabled()) {
            if (agent == null || agent.knows(skill.id)) {
                out.add(skill);
            }
        }
        return out;
    }

    /** Steps this run may take, the agent narrowing the app's own limit. */
    public int steps(int appLimit) {
        AgentDefinition agent = active();
        if (agent == null || agent.maxSteps <= 0) {
            return appLimit;
        }
        return Math.min(appLimit, agent.maxSteps);
    }

    public int seconds(int appLimit) {
        AgentDefinition agent = active();
        if (agent == null || agent.maxSeconds <= 0) {
            return appLimit;
        }
        return Math.min(appLimit, agent.maxSeconds);
    }
}
