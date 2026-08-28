package ai.luna.runtime;

import ai.luna.contracts.MemoryKind;
import ai.luna.contracts.MemoryProvider;
import ai.luna.contracts.MemoryRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Memory that is meant to be lost.
 *
 * <p>Working memory that survives a crash is not working memory, it is a
 * half-finished job pretending to be a fact. This holds a bounded number of
 * records per kind in RAM and drops the least important when it is full — the
 * oldest of the least important, so something marked as mattering outlives a
 * dozen notes about a file that has since been deleted.
 */
public final class EphemeralMemory implements MemoryProvider {

    private final Map<String, List<MemoryRecord>> byKind = new LinkedHashMap<>();
    private final List<String> kinds;
    private final int limit;
    private final String id;

    public EphemeralMemory() {
        this("memory.ephemeral", Arrays.asList(MemoryKind.WORKING, MemoryKind.CONVERSATION), 200);
    }

    public EphemeralMemory(List<String> kinds, int limit) {
        this("memory.ephemeral", kinds, limit);
    }

    /** Named, because two of these in one runtime are two different places. */
    public EphemeralMemory(String id, List<String> kinds, int limit) {
        this.id = id;
        this.kinds = Collections.unmodifiableList(new ArrayList<>(kinds));
        this.limit = Math.max(10, limit);
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public List<String> kinds() {
        return kinds;
    }

    @Override
    public synchronized void write(MemoryRecord record) {
        if (record == null || !kinds.contains(record.kind)) {
            return;
        }
        List<MemoryRecord> held = byKind.get(key(record.kind, record.agentId));
        if (held == null) {
            held = new ArrayList<>();
            byKind.put(key(record.kind, record.agentId), held);
        }
        held.add(record);
        while (held.size() > limit) {
            held.remove(leastWorthKeeping(held));
        }
    }

    @Override
    public synchronized List<MemoryRecord> all(String kind, String agentId) {
        List<MemoryRecord> held = byKind.get(key(kind, agentId));
        if (held == null) {
            return new ArrayList<>();
        }
        List<MemoryRecord> out = new ArrayList<>(held);
        Collections.reverse(out);
        return out;
    }

    @Override
    public synchronized boolean forget(String id) {
        for (List<MemoryRecord> held : byKind.values()) {
            for (int index = 0; index < held.size(); index++) {
                if (held.get(index).id.equals(id)) {
                    held.remove(index);
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public synchronized int clear(String kind, String agentId) {
        List<MemoryRecord> held = byKind.remove(key(kind, agentId));
        return held == null ? 0 : held.size();
    }

    private int leastWorthKeeping(List<MemoryRecord> held) {
        int worst = 0;
        for (int index = 1; index < held.size(); index++) {
            MemoryRecord candidate = held.get(index);
            MemoryRecord current = held.get(worst);
            if (candidate.importance < current.importance
                || (candidate.importance == current.importance && candidate.at < current.at)) {
                worst = index;
            }
        }
        return worst;
    }

    private String key(String kind, String agentId) {
        return kind + "/" + (agentId == null || agentId.isEmpty() ? "luna" : agentId);
    }
}
