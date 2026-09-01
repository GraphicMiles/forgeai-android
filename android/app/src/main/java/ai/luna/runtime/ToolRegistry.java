package ai.luna.runtime;

import ai.luna.contracts.Capability;
import ai.luna.contracts.ToolContext;
import ai.luna.contracts.ToolDefinition;
import ai.luna.contracts.ToolProvider;
import ai.luna.contracts.ToolResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Every tool the runtime can reach, and the one door they are all run through.
 *
 * <p>This replaces a switch statement with a question. Nothing in the engine
 * knows what tools exist any more: it asks {@link #available} what this agent
 * can use here, and the answer depends on the environment (is there a folder? a
 * browser? does this platform allow it?) and on the capabilities that
 * environment is willing to offer.
 *
 * <p>Everything the door enforces is enforced for every provider equally —
 * built-in, plugin or remote:
 *
 * <ul>
 *   <li>the tool exists, and exactly one provider owns it;</li>
 *   <li>it runs on this platform;</li>
 *   <li>every capability it declares is one the environment grants;</li>
 *   <li>required arguments are present before anybody is asked to approve it;</li>
 *   <li>it answers within its own declared timeout, or it is abandoned.</li>
 * </ul>
 */
public final class ToolRegistry {

    private final Map<String, ToolProvider> owners = new LinkedHashMap<>();
    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
    private final List<ToolProvider> providers = new ArrayList<>();

    /** Capabilities the environment is prepared to offer. Empty means all. */
    private Set<String> granted = new HashSet<>();

    /** Ids a small model tends to spell the way they sound. */
    private static final Map<String, String> SYNONYMS = new LinkedHashMap<>();
    static {
        SYNONYMS.put("readfile", "read_file");
        SYNONYMS.put("writefile", "write_file");
        SYNONYMS.put("listfiles", "list_files");
        SYNONYMS.put("ls", "list_files");
        SYNONYMS.put("list", "list_files");
        SYNONYMS.put("find", "search_code");
        SYNONYMS.put("grep", "search_code");
        SYNONYMS.put("createfile", "create_file");
        SYNONYMS.put("createfolder", "create_folder");
        SYNONYMS.put("mkdir", "create_folder");
        SYNONYMS.put("deletefile", "delete_file");
        SYNONYMS.put("delete", "delete_file");
        SYNONYMS.put("rm", "delete_file");
        SYNONYMS.put("renamefile", "rename_file");
        SYNONYMS.put("mv", "rename_file");
        SYNONYMS.put("openpage", "open_page");
        SYNONYMS.put("readpage", "read_page");
        SYNONYMS.put("searchweb", "search_web");
        SYNONYMS.put("lookup", "search_web");
        SYNONYMS.put("google", "search_web");
        SYNONYMS.put("githubfile", "github_file");
        SYNONYMS.put("github", "github_file");
        SYNONYMS.put("askuser", "ask_user");
        SYNONYMS.put("ask", "ask_user");
        SYNONYMS.put("answer", "respond");
    }

    /**
     * Adds a provider. A tool id belongs to whoever claimed it first: a plugin
     * cannot quietly replace {@code delete_file} by naming its own tool that.
     */
    public ToolRegistry register(ToolProvider provider) {
        if (provider == null) {
            return this;
        }
        providers.add(provider);
        for (ToolDefinition definition : provider.definitions()) {
            if (definition.id.isEmpty() || owners.containsKey(definition.id)) {
                continue;
            }
            owners.put(definition.id, provider);
            definitions.put(definition.id, definition);
        }
        return this;
    }

    /** What this environment allows at all. */
    public ToolRegistry grant(Collection<String> capabilities) {
        this.granted = capabilities == null ? new HashSet<String>() : new HashSet<>(capabilities);
        return this;
    }

    public List<ToolProvider> providers() {
        return Collections.unmodifiableList(providers);
    }

    /** Everything registered, in registration order. */
    public List<ToolDefinition> all() {
        return Collections.unmodifiableList(new ArrayList<>(definitions.values()));
    }

    public boolean has(String toolId) {
        return definitions.containsKey(toolId);
    }

    public ToolDefinition definition(String toolId) {
        return definitions.get(toolId);
    }

    /**
     * A tool id spelled the way a small model heard it: readfile, list-files,
     * "search". The exact id wins; then case, hyphens and spaces are flattened;
     * then a few names nobody types correctly are mapped. Anything still
     * unknown returns empty and the caller keeps the original, so the
     * "no such tool" message stays honest.
     */
    public String resolve(String toolId) {
        if (toolId == null) {
            return "";
        }
        String trimmed = toolId.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        if (definitions.containsKey(trimmed)) {
            return trimmed;
        }
        String flat = trimmed.toLowerCase(Locale.US).replace('-', '_').replace(' ', '_');
        if (definitions.containsKey(flat)) {
            return flat;
        }
        String synonym = SYNONYMS.get(flat);
        if (synonym != null && definitions.containsKey(synonym)) {
            return synonym;
        }
        return "";
    }

    public String ownerOf(String toolId) {
        ToolProvider provider = owners.get(toolId);
        return provider == null ? "" : provider.id();
    }

    /** How long this tool may take. Falls back to a minute and a half. */
    public long timeoutFor(String toolId) {
        ToolDefinition definition = definitions.get(toolId);
        return definition == null ? 90000L : definition.timeoutMs;
    }

    public boolean mutating(String toolId) {
        ToolDefinition definition = definitions.get(toolId);
        return definition != null && definition.mutating();
    }

    public boolean needsFolder(String toolId) {
        ToolDefinition definition = definitions.get(toolId);
        return definition != null && definition.requires.contains("workspace");
    }

    /**
     * The tools this agent can actually use right now.
     *
     * <p>Offering a tool that cannot work costs a turn, a refusal and the
     * person's confidence, so anything whose resource is missing is simply not
     * on the list.
     */
    public List<ToolDefinition> available(ToolContext context) {
        List<ToolDefinition> out = new ArrayList<>();
        for (ToolDefinition definition : definitions.values()) {
            if (!usable(definition, context)) {
                continue;
            }
            out.add(definition);
        }
        return out;
    }

    private boolean usable(ToolDefinition definition, ToolContext context) {
        if (context != null && !definition.runsOn(context.platform)) {
            return false;
        }
        if (!allowed(definition)) {
            return false;
        }
        if (context == null) {
            return true;
        }
        if (definition.requires.contains("workspace") && !context.hasStorage()) {
            return false;
        }
        if (definition.requires.contains("git") && !context.hasGit()) {
            return false;
        }
        return !definition.requires.contains("browser") || context.hasBrowser();
    }

    /** Every capability it needs has to be one the environment offers. */
    private boolean allowed(ToolDefinition definition) {
        if (granted.isEmpty()) {
            return true;
        }
        for (String capability : definition.capabilities) {
            if (!granted.contains(capability)) {
                return false;
            }
        }
        return true;
    }

    /** The first capability this tool needs and cannot have, or null. */
    public String missingCapability(String toolId) {
        ToolDefinition definition = definitions.get(toolId);
        if (definition == null || granted.isEmpty()) {
            return null;
        }
        for (String capability : definition.capabilities) {
            if (!granted.contains(capability)) {
                return capability;
            }
        }
        return null;
    }

    /** The lines a prompt shows for the tools that are usable here. */
    public List<String> promptLines(ToolContext context) {
        List<String> out = new ArrayList<>();
        for (ToolDefinition definition : available(context)) {
            out.add(definition.promptLine());
        }
        return out;
    }

    /** The ids that are usable here, for an observation naming the real ones. */
    public List<String> availableIds(ToolContext context) {
        List<String> out = new ArrayList<>();
        for (ToolDefinition definition : available(context)) {
            out.add(definition.id);
        }
        return out;
    }

    /** What the UI shows: read-only and mutating, from the definitions. */
    public List<String> idsByRisk(boolean mutating) {
        List<String> out = new ArrayList<>();
        for (ToolDefinition definition : definitions.values()) {
            if (definition.mutating() == mutating) {
                out.add(definition.id);
            }
        }
        return out;
    }

    /** Every tool the registry knows, as data a UI or a manifest can read. */
    public JSONArray describe() {
        JSONArray out = new JSONArray();
        for (ToolDefinition definition : definitions.values()) {
            try {
                out.put(definition.toJson());
            } catch (Exception ignored) {
                // One unprintable definition does not sink the list.
            }
        }
        return out;
    }

    /** An argument the tool says it needs and did not get, or null. */
    public String missingArgument(String toolId, JSONObject args) {
        ToolDefinition definition = definitions.get(toolId);
        if (definition == null) {
            return null;
        }
        for (String name : definition.required) {
            String value = args == null ? "" : args.optString(name, "");
            if (value.trim().isEmpty()) {
                return name;
            }
        }
        return null;
    }

    /**
     * Run one call, under the tool's own time limit.
     *
     * <p>Never throws: a provider that misbehaves produces a failed result, the
     * same as a tool that simply could not do the job.
     */
    public ToolResult run(final ToolContext context, final String toolId, final JSONObject args,
                          Watchdog watchdog) {
        final ToolProvider provider = owners.get(toolId);
        ToolDefinition definition = definitions.get(toolId);
        if (provider == null || definition == null) {
            return ToolResult.failed("There is no tool called " + toolId + ".");
        }
        if (context != null && !definition.runsOn(context.platform)) {
            return ToolResult.failed(toolId + " cannot run on " + context.platform + ".");
        }
        String missingCapability = missingCapability(toolId);
        if (missingCapability != null) {
            return ToolResult.denied(missingCapability);
        }
        String missingArgument = missingArgument(toolId, args);
        if (missingArgument != null) {
            return ToolResult.failed("That call is missing \"" + missingArgument + "\".");
        }

        final ToolContext owned = context == null ? null : context.ownedBy(provider.id());
        long timeout = definition.timeoutMs;
        try {
            if (watchdog == null) {
                return safely(provider, owned, toolId, args);
            }
            ToolResult result = watchdog.call(new Job() {
                @Override
                public ToolResult run() {
                    return safely(provider, owned, toolId, args);
                }
            }, timeout);
            return result == null ? ToolResult.unfinished(toolId, timeout) : result;
        } catch (Exception error) {
            return ToolResult.failed(String.valueOf(error.getMessage()));
        }
    }

    private static ToolResult safely(ToolProvider provider, ToolContext context, String toolId,
                                     JSONObject args) {
        try {
            ToolResult result = provider.run(context, toolId, args);
            return result == null ? ToolResult.failed("That tool returned nothing.") : result;
        } catch (Throwable error) {
            // A provider is allowed to be badly written. It is not allowed to
            // end the run.
            String message = error.getMessage();
            return ToolResult.failed(message == null ? String.valueOf(error) : message);
        }
    }

    /** One tool call, ready to be run somewhere with a clock on it. */
    public interface Job {
        ToolResult run();
    }

    /** Runs a job with a time limit. Returns null when it ran out of time. */
    public interface Watchdog {
        ToolResult call(Job job, long timeoutMs) throws Exception;
    }

    /** Capability names every registered tool asks for, deduplicated. */
    public List<String> capabilitiesUsed() {
        Set<String> seen = new HashSet<>();
        List<String> out = new ArrayList<>();
        for (ToolDefinition definition : definitions.values()) {
            for (String capability : definition.capabilities) {
                if (Capability.isKnown(capability) && seen.add(capability)) {
                    out.add(capability);
                }
            }
        }
        return out;
    }
}
