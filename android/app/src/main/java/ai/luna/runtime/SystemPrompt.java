package ai.luna.runtime;

import ai.luna.contracts.SkillDefinition;
import ai.luna.contracts.ToolContext;

import java.util.List;

/**
 * The system prompt, assembled from parts nobody hardcoded.
 *
 * <p>Three sources, in this order: what the agent is (skills), where it is
 * (folder and mode), and what it can do right now (the registry's tool lines).
 * The engine no longer writes any of it — it supplies the situation and gets a
 * prompt back.
 */
public final class SystemPrompt {

    private final ToolRegistry tools;
    private final SkillRegistry skills;
    private final SkillResolver resolver;

    public SystemPrompt(ToolRegistry tools, SkillRegistry skills, SkillResolver resolver) {
        this.tools = tools;
        this.skills = skills;
        this.resolver = resolver == null ? new SkillResolver() : resolver;
    }

    /**
     * The greeting prompt: one skill, no tools, nothing to be tempted by.
     */
    public String conversational(SkillDefinition smallTalk, String persona) {
        StringBuilder out = new StringBuilder();
        out.append(smallTalk.instructions).append('\n');
        return withPersona(out, persona);
    }

    /**
     * The working prompt.
     *
     * @param message what the person just said, so the resolver can judge
     *                relevance
     */
    public String build(ToolContext context, String message, String folderName, boolean unattended,
                        String persona) {
        List<SkillDefinition> chosen = resolver.resolve(skills.enabled(), context, tools, message);

        StringBuilder out = new StringBuilder();
        // Identity, then where the agent is standing, then everything else it
        // knows. The situation goes second because it is the one part that
        // changes every single run.
        for (SkillDefinition skill : chosen) {
            if (skill.order <= 0) {
                out.append(skill.instructions).append("\n\n");
            }
        }
        situation(out, folderName, unattended);
        for (SkillDefinition skill : chosen) {
            if (skill.order > 0) {
                out.append(skill.instructions).append("\n\n");
            }
        }

        out.append("To use a tool, reply with one JSON object and nothing else:\n");
        for (String line : tools.promptLines(context)) {
            out.append(line).append('\n');
        }
        out.append("For respond you can also just write the sentences.\n");
        return withPersona(out, persona);
    }

    /** Where the agent is standing, which changes every run. */
    private void situation(StringBuilder out, String folderName, boolean unattended) {
        out.append("Folder granted: ")
            .append(folderName == null || folderName.isEmpty() ? "none yet" : folderName)
            .append('\n');
        out.append("Mode: ").append(unattended ? "unattended" : "ask before acting").append("\n\n");
    }

    private String withPersona(StringBuilder out, String persona) {
        if (persona != null && !persona.isEmpty()) {
            out.append('\n').append(persona).append('\n');
        }
        return out.toString();
    }
}
