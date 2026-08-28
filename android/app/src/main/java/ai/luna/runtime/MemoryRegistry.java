package ai.luna.runtime;

import ai.luna.contracts.MemoryKind;
import ai.luna.contracts.MemoryProvider;
import ai.luna.contracts.MemoryRecord;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Where a memory goes, and what comes back when something is remembered.
 *
 * <p>Writing is routed by kind: whoever claimed that kind gets it. Reading is
 * the interesting half — recall has to be cheap, explainable and small enough
 * to fit in a prompt beside everything else, so it scores rather than searches:
 * how many of the words match, how important the record said it was, and how
 * recent it is. No embeddings, no index, no second product.
 */
public final class MemoryRegistry {

    private final Map<String, MemoryProvider> byKind = new LinkedHashMap<>();
    private final List<MemoryProvider> providers = new ArrayList<>();

    public MemoryRegistry register(MemoryProvider provider) {
        if (provider == null) {
            return this;
        }
        providers.add(provider);
        for (String kind : provider.kinds()) {
            if (MemoryKind.isKind(kind) && !byKind.containsKey(kind)) {
                byKind.put(kind, provider);
            }
        }
        return this;
    }

    public boolean holds(String kind) {
        return byKind.containsKey(kind);
    }

    public MemoryProvider providerFor(String kind) {
        return byKind.get(kind);
    }

    /** Remembers something. A kind nobody holds is quietly not remembered. */
    public void write(MemoryRecord record) {
        if (record == null) {
            return;
        }
        MemoryProvider provider = byKind.get(record.kind);
        if (provider != null) {
            provider.write(record);
        }
    }

    public void remember(String kind, String agentId, String text, int importance,
                         String... tags) {
        write(MemoryRecord.of(kind, agentId, text).tagged(tags).weighing(importance));
    }

    public List<MemoryRecord> all(String kind, String agentId) {
        MemoryProvider provider = byKind.get(kind);
        return provider == null ? new ArrayList<MemoryRecord>() : provider.all(kind, agentId);
    }

    public boolean forget(String id) {
        for (MemoryProvider provider : providers) {
            if (provider.forget(id)) {
                return true;
            }
        }
        return false;
    }

    public int clear(String kind, String agentId) {
        MemoryProvider provider = byKind.get(kind);
        return provider == null ? 0 : provider.clear(kind, agentId);
    }

    /** Everything a run should forget the moment it ends. */
    public int endOfRun(String agentId) {
        int dropped = 0;
        for (String kind : MemoryKind.ALL) {
            if (MemoryKind.transient_(kind)) {
                dropped += clear(kind, agentId);
            }
        }
        return dropped;
    }

    /**
     * What is worth telling the model, given what was just said.
     *
     * <p>Scored, not searched. A record earns a point per matching word, plus a
     * fifth of its own importance, plus a small bonus for being recent — so a
     * fact the person stated on purpose outranks a passing observation from an
     * hour ago, and nothing outranks actually being about the subject.
     */
    public List<MemoryRecord> recall(String about, String agentId, List<String> kinds, int limit) {
        Set<String> wanted = words(about);
        List<Scored> scored = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (String kind : kinds) {
            for (MemoryRecord record : all(kind, agentId)) {
                double score = 0;
                for (String term : record.terms()) {
                    if (wanted.contains(term)) {
                        // Being about the subject is worth more than being
                        // important: a vital fact about photographs still does
                        // not belong in an answer about invoices.
                        score += 3;
                    }
                }
                if (score == 0 && record.importance < 80) {
                    // Not about this, and not important enough to say anyway.
                    continue;
                }
                score += record.importance / 20.0;
                long ageHours = Math.max(0L, (now - record.at) / 3600000L);
                score += ageHours < 24 ? (24 - ageHours) / 24.0 : 0;
                scored.add(new Scored(record, score));
            }
        }
        Collections.sort(scored, new Comparator<Scored>() {
            @Override
            public int compare(Scored left, Scored right) {
                return Double.compare(right.score, left.score);
            }
        });
        List<MemoryRecord> out = new ArrayList<>();
        for (Scored one : scored) {
            if (out.size() >= limit) {
                break;
            }
            out.add(one.record);
        }
        return out;
    }

    /** Recalled memories as the lines a prompt can carry. */
    public List<String> recallLines(String about, String agentId, int limit) {
        List<String> out = new ArrayList<>();
        for (MemoryRecord record : recall(about, agentId,
            java.util.Arrays.asList(MemoryKind.LONG_TERM, MemoryKind.KNOWLEDGE), limit)) {
            out.add(record.text);
        }
        return out;
    }

    /** What a person is shown on a screen about what Luna remembers. */
    public JSONArray describe(String agentId) {
        JSONArray out = new JSONArray();
        for (String kind : MemoryKind.ALL) {
            try {
                JSONObject row = new JSONObject();
                row.put("kind", kind);
                row.put("description", MemoryKind.describe(kind));
                row.put("held", holds(kind));
                row.put("count", all(kind, agentId).size());
                row.put("provider", holds(kind) ? providerFor(kind).id() : "");
                out.put(row);
            } catch (Exception ignored) {
                // One unprintable row does not sink the list.
            }
        }
        return out;
    }

    private Set<String> words(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) {
            return out;
        }
        for (String word : text.toLowerCase(Locale.US).split("[^a-z0-9]+")) {
            if (word.length() > 2) {
                out.add(word);
            }
        }
        return out;
    }

    private static final class Scored {
        final MemoryRecord record;
        final double score;

        Scored(MemoryRecord record, double score) {
            this.record = record;
            this.score = score;
        }
    }
}
