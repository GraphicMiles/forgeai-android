package ai.luna.contracts;

import java.util.List;

/**
 * Somewhere memories are kept.
 *
 * <p>A contract rather than a class because the answer differs by kind and by
 * device: working memory belongs in RAM and should not survive a crash;
 * long-term memory belongs in a file; knowledge about a big folder will one day
 * belong in something with an index. The rest of the runtime should not have to
 * know which.
 */
public interface MemoryProvider {

    String id();

    /** The kinds this provider is willing to hold. */
    List<String> kinds();

    void write(MemoryRecord record);

    /** Everything of this kind for this agent, newest first. */
    List<MemoryRecord> all(String kind, String agentId);

    boolean forget(String id);

    /** Drops everything of one kind. Returns how many went. */
    int clear(String kind, String agentId);
}
