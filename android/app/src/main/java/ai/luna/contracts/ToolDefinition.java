package ai.luna.contracts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * What a tool is, before anyone runs it.
 *
 * <p>Today Luna's tools are a switch statement and a paragraph in a prompt. A
 * platform cannot work that way: the runtime has to be able to say what a tool
 * needs, what it may touch, how long it is allowed to take and where it can run
 * — without executing it, and without being the code that implements it.
 *
 * <p>This is that description. It is data, so it can come from Java, from a
 * plugin manifest, or from a remote registry, and the runtime cannot tell the
 * difference.
 */
public final class ToolDefinition {

    /** {@code area.verb}, e.g. {@code filesystem.read}. Unique in the registry. */
    public final String id;

    /** What a person calls it. */
    public final String name;

    /** One line for the model and for the install screen. */
    public final String description;

    /** Parameter name to a one-line description, in prompt order. */
    public final JSONObject input;

    /** Names of the parameters that must be present. */
    public final List<String> required;

    /** Capability names this tool needs to be granted. */
    public final List<String> capabilities;

    public final RiskLevel risk;

    /** How long one call may take before the watchdog abandons it. */
    public final long timeoutMs;

    /** Resources that must exist first: {@code workspace}, {@code browser}, … */
    public final List<String> requires;

    /** Execution platforms this tool can run on: android, desktop, server. */
    public final List<String> supports;

    /** Which provider registered it. Filled in by the registry. */
    public final String providerId;

    private ToolDefinition(Builder builder) {
        this.id = builder.id;
        this.name = builder.name;
        this.description = builder.description;
        this.input = builder.input;
        this.required = Collections.unmodifiableList(builder.required);
        this.capabilities = Collections.unmodifiableList(builder.capabilities);
        this.risk = builder.risk;
        this.timeoutMs = builder.timeoutMs;
        this.requires = Collections.unmodifiableList(builder.requires);
        this.supports = Collections.unmodifiableList(builder.supports);
        this.providerId = builder.providerId;
    }

    public static Builder of(String id, String name) {
        return new Builder(id, name);
    }

    /** Does this tool change anything, or only look? */
    public boolean mutating() {
        return risk.atLeast(RiskLevel.MEDIUM);
    }

    public boolean needs(String capability) {
        return capabilities.contains(capability);
    }

    public boolean runsOn(String platform) {
        return supports.isEmpty() || supports.contains(platform);
    }

    /** A copy of this definition credited to a provider. */
    public ToolDefinition from(String owner) {
        Builder builder = new Builder(id, name)
            .description(description)
            .input(input)
            .required(required.toArray(new String[0]))
            .capabilities(capabilities.toArray(new String[0]))
            .risk(risk)
            .timeout(timeoutMs)
            .requires(requires.toArray(new String[0]))
            .supports(supports.toArray(new String[0]));
        builder.providerId = owner;
        return new ToolDefinition(builder);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("description", description);
        json.put("input", input);
        json.put("required", new JSONArray(required));
        json.put("permissions", new JSONArray(capabilities));
        json.put("risk", risk.wire());
        json.put("timeout", timeoutMs);
        json.put("requires", new JSONArray(requires));
        json.put("supports", new JSONArray(supports));
        json.put("provider", providerId);
        return json;
    }

    public static ToolDefinition fromJson(JSONObject json) {
        Builder builder = new Builder(json.optString("id", ""), json.optString("name", ""))
            .description(json.optString("description", ""))
            .risk(RiskLevel.of(json.optString("risk", "low")))
            .timeout(json.optLong("timeout", 30000L));
        JSONObject input = json.optJSONObject("input");
        if (input != null) {
            builder.input(input);
        }
        builder.required(strings(json.optJSONArray("required")));
        builder.capabilities(strings(json.optJSONArray("permissions")));
        builder.requires(strings(json.optJSONArray("requires")));
        builder.supports(strings(json.optJSONArray("supports")));
        builder.providerId = json.optString("provider", "");
        return new ToolDefinition(builder);
    }

    /** The one line the prompt shows for this tool. */
    public String promptLine() {
        StringBuilder out = new StringBuilder();
        out.append('{').append('"').append("tool").append('"').append(':')
            .append('"').append(id).append('"').append(',')
            .append('"').append("args").append('"').append(':').append('{');
        JSONArray names = input.names();
        for (int index = 0; names != null && index < names.length(); index++) {
            String key = names.optString(index, "");
            if (index > 0) {
                out.append(',');
            }
            out.append('"').append(key).append('"').append(':').append('"').append("…").append('"');
        }
        out.append('}').append('}');
        if (!description.isEmpty()) {
            out.append("  — ").append(description);
        }
        return out.toString();
    }

    private static String[] strings(JSONArray array) {
        if (array == null) {
            return new String[0];
        }
        List<String> out = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            String value = array.optString(index, "");
            if (!value.isEmpty()) {
                out.add(value);
            }
        }
        return out.toArray(new String[0]);
    }

    /** Built rather than constructed, because most fields have a sane default. */
    public static final class Builder {
        private final String id;
        private final String name;
        private String description = "";
        private JSONObject input = new JSONObject();
        private List<String> required = new ArrayList<>();
        private List<String> capabilities = new ArrayList<>();
        private RiskLevel risk = RiskLevel.LOW;
        private long timeoutMs = 30000L;
        private List<String> requires = new ArrayList<>();
        private List<String> supports = new ArrayList<>(Arrays.asList("android", "desktop", "server"));
        private String providerId = "";

        private Builder(String id, String name) {
            this.id = id == null ? "" : id.trim();
            this.name = name == null ? "" : name.trim();
        }

        public Builder description(String value) {
            this.description = value == null ? "" : value;
            return this;
        }

        public Builder input(JSONObject value) {
            this.input = value == null ? new JSONObject() : value;
            return this;
        }

        /** Convenience: alternating name and description. */
        public Builder input(String... pairs) {
            JSONObject json = new JSONObject();
            try {
                for (int index = 0; index + 1 < pairs.length; index += 2) {
                    json.put(pairs[index], pairs[index + 1]);
                }
            } catch (JSONException ignored) {
                // A malformed pair is dropped rather than failing the build.
            }
            this.input = json;
            return this;
        }

        public Builder required(String... names) {
            this.required = new ArrayList<>(Arrays.asList(names));
            return this;
        }

        public Builder capabilities(String... names) {
            this.capabilities = new ArrayList<>(Arrays.asList(names));
            return this;
        }

        public Builder risk(RiskLevel value) {
            this.risk = value == null ? RiskLevel.LOW : value;
            return this;
        }

        public Builder timeout(long millis) {
            this.timeoutMs = millis <= 0 ? 30000L : millis;
            return this;
        }

        public Builder requires(String... names) {
            this.requires = new ArrayList<>(Arrays.asList(names));
            return this;
        }

        public Builder supports(String... names) {
            this.supports = new ArrayList<>(Arrays.asList(names));
            return this;
        }

        public ToolDefinition build() {
            return new ToolDefinition(this);
        }
    }
}
