package ai.luna.app;

import ai.luna.contracts.Capability;
import ai.luna.contracts.RiskLevel;
import ai.luna.contracts.ToolContext;
import ai.luna.contracts.ToolDefinition;
import ai.luna.contracts.ToolProvider;
import ai.luna.contracts.ToolResult;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Luna's own tools, described the way any other provider has to describe
 * itself.
 *
 * <p>Nothing here changes what the tools do — {@link Tools} still does the
 * work. What changes is that the core no longer knows the list: it asks a
 * provider what it offers, and this one happens to be built in. A plugin
 * answering the same interface is indistinguishable.
 *
 * <p>The ids are still the short ones the model has always been given
 * ({@code read_file}), because transcripts and prompts are full of them. The
 * dotted platform names ({@code filesystem.read}) are recorded here alongside
 * them, so the registry can move over without breaking a single saved chat.
 */
public final class BuiltinTools implements ToolProvider {

    /** The name each built-in tool will carry in the registry. */
    private static final Map<String, String> CANONICAL = new LinkedHashMap<>();

    static {
        CANONICAL.put("list_files", "filesystem.list");
        CANONICAL.put("read_file", "filesystem.read");
        CANONICAL.put("search_code", "filesystem.search");
        CANONICAL.put("write_file", "filesystem.write");
        CANONICAL.put("create_file", "filesystem.create");
        CANONICAL.put("create_folder", "filesystem.mkdir");
        CANONICAL.put("delete_file", "filesystem.delete");
        CANONICAL.put("rename_file", "filesystem.rename");
        CANONICAL.put("open_page", "browser.open");
        CANONICAL.put("read_page", "browser.read");
        CANONICAL.put("github_file", "github.file");
        CANONICAL.put("ask_user", "user.ask");
        CANONICAL.put("respond", "agent.respond");
    }

    private final List<ToolDefinition> definitions;
    private final Map<String, ToolDefinition> byId = new HashMap<>();

    public BuiltinTools() {
        List<ToolDefinition> all = new ArrayList<>();

        all.add(ToolDefinition.of("list_files", "List files")
            .description("What is in a folder")
            .input("path", "Folder, relative to the granted one. Empty means the root")
            .capabilities(Capability.FILESYSTEM_READ)
            .risk(RiskLevel.LOW)
            .requires("workspace")
            .build());

        all.add(ToolDefinition.of("read_file", "Read a file")
            .description("The text of one file")
            .input("path", "File, relative to the granted folder")
            .required("path")
            .capabilities(Capability.FILESYSTEM_READ)
            .risk(RiskLevel.LOW)
            .requires("workspace")
            .build());

        all.add(ToolDefinition.of("search_code", "Search")
            .description("Find text anywhere under the granted folder")
            .input("query", "What to look for")
            .required("query")
            .capabilities(Capability.FILESYSTEM_READ)
            .risk(RiskLevel.LOW)
            .requires("workspace")
            .build());

        all.add(ToolDefinition.of("write_file", "Write a file")
            .description("Replace a file's contents. The old bytes are kept for undo")
            .input("path", "File to write", "content", "What to write")
            .required("path", "content")
            .capabilities(Capability.FILESYSTEM_WRITE)
            .risk(RiskLevel.MEDIUM)
            .requires("workspace")
            .build());

        all.add(ToolDefinition.of("create_file", "Create a file")
            .description("Make an empty file")
            .input("path", "File to create")
            .required("path")
            .capabilities(Capability.FILESYSTEM_WRITE)
            .risk(RiskLevel.MEDIUM)
            .requires("workspace")
            .build());

        all.add(ToolDefinition.of("create_folder", "Create a folder")
            .description("Make a folder")
            .input("path", "Folder to create")
            .required("path")
            .capabilities(Capability.FILESYSTEM_WRITE)
            .risk(RiskLevel.MEDIUM)
            .requires("workspace")
            .build());

        all.add(ToolDefinition.of("delete_file", "Delete")
            .description("Remove a file. A backup is kept")
            .input("path", "File to delete")
            .required("path")
            .capabilities(Capability.FILESYSTEM_DELETE)
            .risk(RiskLevel.HIGH)
            .requires("workspace")
            .build());

        all.add(ToolDefinition.of("rename_file", "Rename")
            .description("Give a file a different name")
            .input("path", "File to rename", "newName", "The new name")
            .required("path", "newName")
            .capabilities(Capability.FILESYSTEM_WRITE)
            .risk(RiskLevel.MEDIUM)
            .requires("workspace")
            .build());

        all.add(ToolDefinition.of("open_page", "Open a page")
            .description("Load a web page in the windowless browser")
            .input("url", "The address. Never invent one")
            .required("url")
            .capabilities(Capability.BROWSER_NAVIGATE, Capability.NETWORK_REQUEST)
            .risk(RiskLevel.MEDIUM)
            .timeout(20000L)
            .requires("browser")
            .build());

        all.add(ToolDefinition.of("read_page", "Read the page")
            .description("The text of the page that is open")
            .capabilities(Capability.BROWSER_READ)
            .risk(RiskLevel.MEDIUM)
            .requires("browser")
            .build());

        all.add(ToolDefinition.of("github_file", "Read from GitHub")
            .description("One file out of a repository")
            .input("repo", "owner/name", "path", "Path in the repository")
            .required("repo", "path")
            .capabilities(Capability.GITHUB_READ, Capability.NETWORK_REQUEST,
                Capability.CREDENTIAL_READ)
            .risk(RiskLevel.MEDIUM)
            .build());

        all.add(ToolDefinition.of("ask_user", "Ask the person")
            .description("Stop and wait for a real answer")
            .input("question", "What to ask")
            .required("question")
            .capabilities(Capability.USER_ASK)
            .risk(RiskLevel.MEDIUM)
            .timeout(600000L)
            .build());

        all.add(ToolDefinition.of("respond", "Answer")
            .description("Finish the job and say what happened")
            .input("text", "The answer, in plain sentences")
            .risk(RiskLevel.LOW)
            .build());

        List<ToolDefinition> owned = new ArrayList<>(all.size());
        for (ToolDefinition definition : all) {
            ToolDefinition credited = definition.from(id());
            owned.add(credited);
            byId.put(credited.id, credited);
        }
        this.definitions = Collections.unmodifiableList(owned);
    }

    @Override
    public String id() {
        return "core";
    }

    @Override
    public List<ToolDefinition> definitions() {
        return definitions;
    }

    @Override
    public boolean owns(String toolId) {
        return byId.containsKey(toolId);
    }

    public ToolDefinition definition(String toolId) {
        return byId.get(toolId);
    }

    /** The dotted platform name for a built-in tool, or the id unchanged. */
    public static String canonical(String toolId) {
        String name = CANONICAL.get(toolId);
        return name == null ? toolId : name;
    }

    /** Every built-in id, in prompt order. */
    public static List<String> ids() {
        return Collections.unmodifiableList(new ArrayList<>(CANONICAL.keySet()));
    }

    @Override
    public ToolResult run(ToolContext context, String toolId, JSONObject args) {
        ToolDefinition definition = byId.get(toolId);
        if (definition == null) {
            return ToolResult.failed("There is no tool called " + toolId + ".");
        }
        if (definition.requires.contains("workspace") && !context.hasStorage()) {
            return ToolResult.failed("No folder is granted, so there is nothing to read or write.");
        }
        if (definition.requires.contains("browser") && !context.hasBrowser()) {
            return ToolResult.failed("There is no browser available on this device.");
        }
        long started = System.currentTimeMillis();
        Tools.Env env = new Tools.Env(context.storage, context.browser, context.secrets,
            context.trace);
        String observation = Tools.run(env, toolId, args == null ? new JSONObject() : args);
        long took = System.currentTimeMillis() - started;
        if (observation != null && observation.startsWith("Failed: ")) {
            return ToolResult.failed(observation.substring("Failed: ".length())).withTiming(took);
        }
        return ToolResult.ok(observation, took);
    }
}
