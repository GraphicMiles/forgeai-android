package ai.luna.builtin;

import ai.luna.contracts.AgentDefinition;

/**
 * Luna, as an installed agent.
 *
 * <p>She is the default and she ships in the box, but from here she is an entry
 * in a registry like any other: everything that used to make her special —
 * having tools, having knowledge, having a name — is now written in a
 * definition that anybody could write.
 *
 * <p>She asks for everything, which on a phone means everything the environment
 * offers and no more. A definition narrows; it never grants.
 */
public final class LunaAgent {

    public static final String ID = "luna";

    public static final AgentDefinition DEFINITION = AgentDefinition
        .of(ID, "Luna")
        .describe("A local utility agent that works on the files and pages on this phone.")
        .version("1.0.0")
        .by("Luna")
        .skills(AgentDefinition.ALL)
        .tools(AgentDefinition.ALL)
        .builtIn()
        .build();

    private LunaAgent() {
    }
}
