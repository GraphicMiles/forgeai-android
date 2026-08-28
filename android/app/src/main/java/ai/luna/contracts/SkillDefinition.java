package ai.luna.contracts;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * A piece of knowledge an agent has, described as data.
 *
 * <p>Today Luna's competence is a wall of English inside {@code AgentEngine}: how
 * to treat a folder, what to do about web addresses, when to stop and ask. That
 * text is knowledge, and knowledge should be installable. A skill is that text
 * plus the three facts that decide whether it belongs in this turn's prompt:
 * the tools it needs, the resources it needs, and what it is about.
 *
 * <p>Everything here is serialisable, because a plugin ships skills as JSON and
 * nothing else. There is no code in a skill — that is the whole reason it is
 * safe to install one.
 */
public final class SkillDefinition {

    public final String id;
    public final String name;

    /** One sentence, for a list in the UI. */
    public final String description;

    /** The text that goes into the system prompt. */
    public final String instructions;

    /** Tool ids this skill talks about. If none are available, it is dropped. */
    public final List<String> tools;

    /** Resources that must be present: workspace, browser. */
    public final List<String> requires;

    /** Resources that must be absent. A skill about having no folder. */
    public final List<String> unless;

    /** Words that bring this skill in. Ignored when {@link #always} is true. */
    public final List<String> triggers;

    /** In every prompt, whatever the message says. */
    public final boolean always;

    /** Lower goes first. Identity is 0; manners are last. */
    public final int order;

    /** Who supplied it. {@code core} for the ones that ship with Luna. */
    public final String providerId;

    private SkillDefinition(Builder builder) {
        this.id = builder.id;
        this.name = builder.name.isEmpty() ? builder.id : builder.name;
        this.description = builder.description;
        this.instructions = builder.instructions;
        this.tools = Collections.unmodifiableList(new ArrayList<>(builder.tools));
        this.requires = Collections.unmodifiableList(new ArrayList<>(builder.requires));
        this.unless = Collections.unmodifiableList(new ArrayList<>(builder.unless));
        this.triggers = Collections.unmodifiableList(new ArrayList<>(builder.triggers));
        this.always = builder.always;
        this.order = builder.order;
        this.providerId = builder.providerId;
    }

    public static Builder of(String id, String name) {
        return new Builder(id, name);
    }

    /** The same skill, credited to whoever installed it. */
    public SkillDefinition from(String provider) {
        Builder builder = copy();
        builder.providerId = provider == null || provider.isEmpty() ? "core" : provider;
        return builder.build();
    }

    private Builder copy() {
        Builder builder = new Builder(id, name);
        builder.description = description;
        builder.instructions = instructions;
        builder.tools = new ArrayList<>(tools);
        builder.requires = new ArrayList<>(requires);
        builder.unless = new ArrayList<>(unless);
        builder.triggers = new ArrayList<>(triggers);
        builder.always = always;
        builder.order = order;
        builder.providerId = providerId;
        return builder;
    }

    /** True when the message reads like this skill's subject. */
    public boolean triggeredBy(String message) {
        if (always) {
            return true;
        }
        if (triggers.isEmpty() || message == null) {
            return false;
        }
        String text = message.toLowerCase(Locale.US);
        for (String trigger : triggers) {
            if (!trigger.isEmpty() && text.contains(trigger.toLowerCase(Locale.US))) {
                return true;
            }
        }
        return false;
    }

    /** True when this environment has what the skill talks about. */
    public boolean fits(boolean workspace, boolean browser) {
        for (String need : requires) {
            if ("workspace".equals(need) && !workspace) {
                return false;
            }
            if ("browser".equals(need) && !browser) {
                return false;
            }
        }
        for (String absent : unless) {
            if ("workspace".equals(absent) && workspace) {
                return false;
            }
            if ("browser".equals(absent) && browser) {
                return false;
            }
        }
        return true;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject out = new JSONObject();
        out.put("id", id);
        out.put("name", name);
        out.put("description", description);
        out.put("instructions", instructions);
        out.put("tools", new JSONArray(tools));
        out.put("requires", new JSONArray(requires));
        out.put("unless", new JSONArray(unless));
        out.put("triggers", new JSONArray(triggers));
        out.put("always", always);
        out.put("order", order);
        out.put("provider", providerId);
        return out;
    }

    /** Reads one back, which is how an installed plugin's skills arrive. */
    public static SkillDefinition fromJson(JSONObject json) {
        Builder builder = new Builder(json.optString("id", ""), json.optString("name", ""));
        builder.description = json.optString("description", "");
        builder.instructions = json.optString("instructions", "");
        builder.tools = strings(json.optJSONArray("tools"));
        builder.requires = strings(json.optJSONArray("requires"));
        builder.unless = strings(json.optJSONArray("unless"));
        builder.triggers = strings(json.optJSONArray("triggers"));
        builder.always = json.optBoolean("always", false);
        builder.order = json.optInt("order", 50);
        builder.providerId = json.optString("provider", "core");
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
        private String instructions = "";
        private List<String> tools = new ArrayList<>();
        private List<String> requires = new ArrayList<>();
        private List<String> unless = new ArrayList<>();
        private List<String> triggers = new ArrayList<>();
        private boolean always;
        private int order = 50;
        private String providerId = "core";

        private Builder(String id, String name) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name;
        }

        public Builder describe(String text) {
            this.description = text == null ? "" : text;
            return this;
        }

        public Builder says(String text) {
            this.instructions = text == null ? "" : text;
            return this;
        }

        public Builder tools(String... ids) {
            this.tools = new ArrayList<>(Arrays.asList(ids));
            return this;
        }

        public Builder requires(String... needs) {
            this.requires = new ArrayList<>(Arrays.asList(needs));
            return this;
        }

        public Builder unless(String... absent) {
            this.unless = new ArrayList<>(Arrays.asList(absent));
            return this;
        }

        public Builder triggers(String... words) {
            this.triggers = new ArrayList<>(Arrays.asList(words));
            return this;
        }

        public Builder always() {
            this.always = true;
            return this;
        }

        public Builder order(int value) {
            this.order = value;
            return this;
        }

        public SkillDefinition build() {
            return new SkillDefinition(this);
        }
    }
}
