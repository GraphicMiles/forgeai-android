package ai.luna.runtime;

import ai.luna.contracts.SkillDefinition;
import ai.luna.contracts.ToolContext;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Which of an agent's skills belong in this turn's prompt.
 *
 * <p>Context is not free. A 0.5B model with a 2k window cannot be handed every
 * skill anybody ever installed, and a skill that talks about tools this
 * environment does not offer is worse than useless: it invites a call that
 * cannot succeed. So the resolver drops a skill when the resources are missing,
 * when none of its tools are available, or when the message is plainly about
 * something else — and stops adding once the budget is spent.
 */
public final class SkillResolver {

    /** Characters of skill text one prompt may carry. */
    private final int budget;

    public SkillResolver() {
        this(4000);
    }

    public SkillResolver(int budget) {
        this.budget = budget < 400 ? 400 : budget;
    }

    public List<SkillDefinition> resolve(List<SkillDefinition> candidates, ToolContext context,
                                         ToolRegistry tools, String message) {
        boolean workspace = context != null && context.hasStorage();
        boolean browser = context != null && context.hasBrowser();
        List<String> available = tools == null
            ? new ArrayList<String>()
            : tools.availableIds(context);

        List<SkillDefinition> chosen = new ArrayList<>();
        for (SkillDefinition skill : candidates) {
            if (skill.instructions.isEmpty()) {
                continue;
            }
            if (!skill.fits(workspace, browser)) {
                continue;
            }
            if (!applies(skill, message, available)) {
                continue;
            }
            if (!skill.tools.isEmpty() && !anyAvailable(skill, available)) {
                continue;
            }
            chosen.add(skill);
        }

        Collections.sort(chosen, new Comparator<SkillDefinition>() {
            @Override
            public int compare(SkillDefinition left, SkillDefinition right) {
                return left.order - right.order;
            }
        });

        List<SkillDefinition> out = new ArrayList<>();
        int spent = 0;
        for (SkillDefinition skill : chosen) {
            int cost = skill.instructions.length();
            if (!skill.always && spent + cost > budget) {
                continue;
            }
            spent += cost;
            out.add(skill);
        }
        return out;
    }

    /**
     * Three ways in: it is always on; the message is about it; or the tools it
     * talks about are on the table. A skill with neither triggers nor tools is
     * its own condition — {@code requires} and {@code unless} already decided,
     * and a rule about having no folder cannot wait to be mentioned by name.
     */
    private boolean applies(SkillDefinition skill, String message, List<String> available) {
        if (skill.always) {
            return true;
        }
        if (skill.triggers.isEmpty() && skill.tools.isEmpty()) {
            return true;
        }
        return skill.triggeredBy(message) || toolIsRelevant(skill, available);
    }

    /**
     * A skill about tools that are on the table is worth including even when
     * the message never named them: the model has the tool, it should have the
     * instructions that go with it.
     */
    private boolean toolIsRelevant(SkillDefinition skill, List<String> available) {
        return !skill.tools.isEmpty() && anyAvailable(skill, available);
    }

    private boolean anyAvailable(SkillDefinition skill, List<String> available) {
        for (String tool : skill.tools) {
            if (available.contains(tool)) {
                return true;
            }
        }
        return false;
    }
}
