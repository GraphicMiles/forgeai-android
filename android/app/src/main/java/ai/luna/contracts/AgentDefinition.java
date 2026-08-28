package ai.luna.contracts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * An agent, described as data.
 *
 * <p>Until now "the agent" was the app: Luna's name, her instructions, her tool
 * list and her limits were spread across {@code AgentEngine}, {@code Tools} and
 * {@code Prefs}, and a second agent was unthinkable. This is the whole of an
 * agent written down — who it is, what it knows, what it may touch, which model
 * it prefers and what it may spend — so that Luna becomes one entry in a list
 * rather than the list itself.
 *
 * <p>An agent grants nothing. Its tool list can only narrow what the
 * environment already allows: an agent that asks for {@code shell.execute} on a
 * phone still gets nothing.
 */
public final class AgentDefinition {

    /** Everything the environment offers, in the tool list. */
    public static final String ALL = "*";

    public final String id;
    public final String name;
    public final String description;
    public final String version;
    public final String author;

    /** Extra instructions, on top of the skills. Usually empty. */
    public final String instructions;

    /** Skill ids this agent has. {@link #ALL} for every registered skill. */
    public final List<String> skills;

    /** Tool ids this agent may use. {@link #ALL} for whatever is available. */
    public final List<String> tools;

    /** Preferred model id, or empty to use whatever the person has selected. */
    public final String model;

    /** Steps and seconds one run may take. Zero means the app's own default. */
    public final int maxSteps;
    public final int maxSeconds;

    /** Shipped with the runtime rather than installed. */
    public final boolean builtIn;

    private AgentDefinition(Builder builder) {
        this.id = builder.id;
        this.name = builder.name.isEmpty() ? builder.id : builder.name;
        this.description = builder.description;
        this.version = builder.version;
        this.author = builder.author;
        this.instructions = builder.instructions;
        this.skills = Collections.unmodifiableList(new ArrayList<>(builder.skills));
        this.tools = Collections.unmodifiableList(new ArrayList<>(builder.tools));
        this.model = builder.model;
        this.maxSteps = builder.maxSteps;
        this.maxSeconds = builder.maxSeconds;
        this.builtIn = builder.builtIn;
    }

    public static Builder of(String id, String name) {
        return new Builder(id, name);
    }

    /** May this agent use this tool at all? Narrowing only, never granting. */
    public boolean allows(String toolId) {
        if (tools.isEmpty() || tools.contains(ALL)) {
            return true;
        }
        return tools.contains(toolId);
    }

    /** Does this agent have this skill? */
    public boolean knows(String skillId) {
        if (skills.isEmpty() || skills.contains(ALL)) {
            return true;
        }
        return skills.contains(skillId);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("name", name);
        out.put("description", description);
        out.put("version", version);
        out.put("author", author);
        out.put("instructions", instructions);
        out.put("skills", new JSONArray(skills));
        out.put("tools", new JSONArray(tools));
        out.put("model", model);
        out.put("maxSteps", maxSteps);
        out.put("maxSeconds", maxSeconds);
        out.put("builtIn", builtIn);
        return out;
    }

    public static AgentDefinition fromJson(JSONObject json) {
        Builder builder = new Builder(json.optString("id", ""), json.optString("name", ""));
        builder.description = json.optString("description", "");
        builder.version = json.optString("version", "1.0.0");
        builder.author = json.optString("author", "");
        builder.instructions = json.optString("instructions", "");
        builder.skills = strings(json.optJSONArray("skills"));
        builder.tools = strings(json.optJSONArray("tools"));
        builder.model = json.optString("model", "");
        builder.maxSteps = json.optInt("maxSteps", 0);
        builder.maxSeconds = json.optInt("maxSeconds", 0);
        builder.builtIn = json.optBoolean("builtIn", false);
        return builder.build();
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

    public static final class Builder {

        private final String id;
        private final String name;
        private String description = "";
        private String version = "1.0.0";
        private String author = "";
        private String instructions = "";
        private List<String> skills = new ArrayList<>();
        private List<String> tools = new ArrayList<>();
        private String model = "";
        private int maxSteps;
        private int maxSeconds;
        private boolean builtIn;

        private Builder(String id, String name) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
        }

        public Builder describe(String text) {
            this.description = text == null ? "" : text;
            return this;
        }

        public Builder version(String value) {
            this.version = value == null || value.isEmpty() ? "1.0.0" : value;
            return this;
        }

        public Builder by(String who) {
            this.author = who == null ? "" : who;
            return this;
        }

        public Builder says(String text) {
            this.instructions = text == null ? "" : text;
            return this;
        }

        public Builder skills(String... ids) {
            this.skills = new ArrayList<>(Arrays.asList(ids));
            return this;
        }

        public Builder tools(String... ids) {
            this.tools = new ArrayList<>(Arrays.asList(ids));
            return this;
        }

        public Builder model(String id) {
            this.model = id == null ? "" : id;
            return this;
        }

        public Builder budget(int steps, int seconds) {
            this.maxSteps = Math.max(0, steps);
            this.maxSeconds = Math.max(0, seconds);
            return this;
        }

        public Builder builtIn() {
            this.builtIn = true;
            return this;
        }

        public AgentDefinition build() {
            return new AgentDefinition(this);
        }
    }
}
