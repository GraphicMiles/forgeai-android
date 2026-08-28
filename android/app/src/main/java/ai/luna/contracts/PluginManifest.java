package ai.luna.contracts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * What a plugin says about itself.
 *
 * <p>A plugin is a document. It carries knowledge (skills), agents, and the
 * capabilities its contents are allowed to ask for — and nothing that executes.
 * That restriction is the point: an installed plugin cannot run code in the
 * app's process, so installing one can never be worse than being told
 * something untrue.
 *
 * <p>The manifest is the whole of a {@code .lunapkg}: content plus the digest
 * and signature that say who wrote it and that it arrived intact.
 */
public final class PluginManifest {

    /** Manifest format this runtime understands. */
    public static final int FORMAT = 1;

    public final int format;
    public final String id;
    public final String name;
    public final String version;
    public final String author;
    public final String description;

    /** Capabilities the plugin's contents may ask for. Never more than granted. */
    public final List<String> capabilities;

    /** Skills, as {@link SkillDefinition} documents. */
    public final List<JSONObject> skills;

    /** Agents, as {@link AgentDefinition} documents. */
    public final List<JSONObject> agents;

    /** Workflows, as documents the workflow engine will read. */
    public final List<JSONObject> workflows;

    /** SHA-256 of the content, lower-case hex. */
    public final String digest;

    /** Base64 signature over the digest, or empty when unsigned. */
    public final String signature;

    /** Base64 X.509 public key the signature belongs to, or empty. */
    public final String publicKey;

    private PluginManifest(int format, String id, String name, String version, String author,
                           String description, List<String> capabilities, List<JSONObject> skills,
                           List<JSONObject> agents, List<JSONObject> workflows, String digest,
                           String signature, String publicKey) {
        this.format = format;
        this.id = id;
        this.name = name.isEmpty() ? id : name;
        this.version = version;
        this.author = author;
        this.description = description;
        this.capabilities = Collections.unmodifiableList(capabilities);
        this.skills = Collections.unmodifiableList(skills);
        this.agents = Collections.unmodifiableList(agents);
        this.workflows = Collections.unmodifiableList(workflows);
        this.digest = digest;
        this.signature = signature;
        this.publicKey = publicKey;
    }

    public static PluginManifest fromJson(JSONObject json) {
        if (json == null) {
            json = new JSONObject();
        }
        JSONObject signing = json.optJSONObject("signing");
        if (signing == null) {
            signing = new JSONObject();
        }
        return new PluginManifest(
            json.optInt("format", 0),
            json.optString("id", "").trim().toLowerCase(Locale.US),
            json.optString("name", ""),
            json.optString("version", ""),
            json.optString("author", ""),
            json.optString("description", ""),
            strings(json.optJSONArray("capabilities")),
            objects(json.optJSONArray("skills")),
            objects(json.optJSONArray("agents")),
            objects(json.optJSONArray("workflows")),
            signing.optString("digest", "").trim().toLowerCase(Locale.US),
            signing.optString("signature", "").trim(),
            signing.optString("publicKey", "").trim());
    }

    public boolean signed() {
        return !signature.isEmpty() && !publicKey.isEmpty();
    }

    /** How many documents this plugin carries. */
    public int contentCount() {
        return skills.size() + agents.size() + workflows.size();
    }

    /**
     * The bytes a digest and a signature are taken over.
     *
     * <p>Deliberately not the raw file: whitespace and key order must not change
     * the identity of a plugin, so the content is rebuilt in a fixed shape
     * before it is hashed.
     */
    public String canonicalContent() {
        StringBuilder out = new StringBuilder();
        out.append(format).append('\n');
        out.append(id).append('\n');
        out.append(version).append('\n');
        for (String capability : capabilities) {
            out.append("cap:").append(capability).append('\n');
        }
        append(out, "skill", skills, "id");
        append(out, "agent", agents, "id");
        append(out, "workflow", workflows, "id");
        return out.toString();
    }

    private void append(StringBuilder out, String kind, List<JSONObject> documents, String key) {
        List<String> lines = new ArrayList<>();
        for (JSONObject document : documents) {
            lines.add(kind + ":" + document.optString(key, "") + ":" + stable(document));
        }
        Collections.sort(lines);
        for (String line : lines) {
            out.append(line).append('\n');
        }
    }

    /** A document written with its keys in a fixed order. */
    private static String stable(JSONObject document) {
        List<String> keys = new ArrayList<>();
        for (java.util.Iterator<String> it = document.keys(); it.hasNext(); ) {
            keys.add(it.next());
        }
        Collections.sort(keys);
        StringBuilder out = new StringBuilder();
        for (String key : keys) {
            out.append(key).append('=').append(String.valueOf(document.opt(key))).append(';');
        }
        return out.toString();
    }

    public JSONObject toJson() throws JSONException {
        JSONObject out = new JSONObject();
        out.put("format", format);
        out.put("id", id);
        out.put("name", name);
        out.put("version", version);
        out.put("author", author);
        out.put("description", description);
        out.put("capabilities", new JSONArray(capabilities));
        out.put("skills", new JSONArray(skills));
        out.put("agents", new JSONArray(agents));
        out.put("workflows", new JSONArray(workflows));
        JSONObject signing = new JSONObject();
        signing.put("digest", digest);
        signing.put("signature", signature);
        signing.put("publicKey", publicKey);
        out.put("signing", signing);
        return out;
    }

    private static List<String> strings(JSONArray array) {
        List<String> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int index = 0; index < array.length(); index++) {
            String value = array.optString(index, "");
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }

    private static List<JSONObject> objects(JSONArray array) {
        List<JSONObject> out = new ArrayList<>();
        if (array == null) {
            return out;
        }
        for (int index = 0; index < array.length(); index++) {
            JSONObject value = array.optJSONObject(index);
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }
}
