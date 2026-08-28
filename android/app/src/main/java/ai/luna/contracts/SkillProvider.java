package ai.luna.contracts;

import java.util.List;

/**
 * Something that supplies knowledge.
 *
 * <p>Deliberately the smallest interface in the codebase: a plugin that teaches
 * Luna how to work with a particular kind of project ships a list of skills and
 * nothing executable. That is what makes an installed skill safe.
 */
public interface SkillProvider {

    /** Stable id of whoever supplies these. */
    String id();

    List<SkillDefinition> skills();
}
