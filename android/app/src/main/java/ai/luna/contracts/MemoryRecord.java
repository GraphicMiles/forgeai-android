package ai.luna.contracts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** One thing remembered. */
public final class MemoryRecord {

    public final String id;
    public final String kind;

    /** Who remembered it. Memory is per agent, or it is a shared delusion. */
    public final String agentId;

    public final String text;

    /** Words a recall can match on, beyond the text itself. */
    public final List<String> tags;

    /** 0–100. Higher survives pruning and outranks a merely recent memory. */
    public final int importance;

    public final long at;

    private MemoryRecord(String id, String kind, String agentId, String text, List<String> tags,
                         int importance, long at) {
        this.id = id;
        this.kind = kind;
        this.agentId = agentId;
        this.text = text;
        this.tags = Collections.unmodifiableList(tags);
        this.importance = importance < 0 ? 0 : Math.min(100, importance);
        this.at = at;
    }

    public static MemoryRecord of(String kind, String agentId, String text) {
        return new MemoryRecord(newId(), kind, agentId, text == null ? "" : text,
            new ArrayList<String>(), 50, System.currentTimeMillis());
    }

    public MemoryRecord tagged(String... tags) {
        return new MemoryRecord(id, kind, agentId, text, new ArrayList<>(Arrays.asList(tags)),
            importance, at);
    }

    public MemoryRecord weighing(int importance) {
        return new MemoryRecord(id, kind, agentId, text, new ArrayList<>(tags), importance, at);
    }

    /** Every word this record can be found by, lower case. */
    public List<String> terms() {
        List<String> out = new ArrayList<>();
        for (String word : text.toLowerCase(Locale.US).split("[^a-z0-9]+")) {
            if (word.length() > 2) {
                out.add(word);
            }
        }
        for (String tag : tags) {
            out.add(tag.toLowerCase(Locale.US));
        }
        return out;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("kind", kind);
        out.put("agent", agentId);
        out.put("text", text);
        out.put("tags", new JSONArray(tags));
        out.put("importance", importance);
        out.put("at", at);
        return out;
    }

    public static MemoryRecord fromJson(JSONObject json) {
        List<String> tags = new ArrayList<>();
        JSONArray array = json.optJSONArray("tags");
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                String tag = array.optString(index, "");
                if (!tag.isEmpty()) {
                    tags.add(tag);
                }
            }
        }
        return new MemoryRecord(
            json.optString("id", newId()),
            json.optString("kind", MemoryKind.LONG_TERM),
            json.optString("agent", "luna"),
            json.optString("text", ""),
            tags,
            json.optInt("importance", 50),
            json.optLong("at", System.currentTimeMillis()));
    }

    private static String newId() {
        return "m" + System.nanoTime();
    }
}
