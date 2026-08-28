package ai.luna.builtin;

import ai.luna.contracts.ToolProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The providers that ship with the runtime.
 *
 * <p>Four of them, in prompt order. They are registered the same way a plugin
 * would be; the only thing "built-in" means here is that nobody had to install
 * them.
 */
public final class Builtins {

    /** The name each built-in tool will carry once the short ids are retired. */
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

    private Builtins() {
    }

    public static List<ToolProvider> all() {
        return Arrays.<ToolProvider>asList(
            new FilesystemTools(),
            new BrowserTools(),
            new GithubTools(),
            new UserTools());
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
}
