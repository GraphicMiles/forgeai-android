package ai.luna.contracts;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a capability is called.
 *
 * <p>A capability is the unit a permission is granted in. A tool declares the
 * capabilities it needs; a plugin declares the capabilities its tools may ask
 * for; the policy engine decides. Nothing in the platform is allowed to ask for
 * "access" in the abstract — it asks for one of these names, and the person can
 * be shown exactly that sentence before they agree to it.
 *
 * <p>Names are dotted and lower case: {@code area.verb}. The area is the thing
 * being touched, the verb is what is done to it.
 */
public final class Capability {

    // --- files ---------------------------------------------------------------
    public static final String FILESYSTEM_READ = "filesystem.read";
    public static final String FILESYSTEM_WRITE = "filesystem.write";
    public static final String FILESYSTEM_DELETE = "filesystem.delete";

    // --- the network ---------------------------------------------------------
    public static final String NETWORK_REQUEST = "network.request";
    public static final String BROWSER_NAVIGATE = "browser.navigate";
    public static final String BROWSER_READ = "browser.read";

    // --- code hosting --------------------------------------------------------
    public static final String GITHUB_READ = "github.read";
    public static final String GITHUB_WRITE = "github.write";

    // --- secrets -------------------------------------------------------------
    public static final String CREDENTIAL_READ = "credential.read";
    public static final String CREDENTIAL_WRITE = "credential.write";
    /** Reading a secret back out of the device. Never granted to a plugin. */
    public static final String CREDENTIAL_EXPORT = "credential.export";

    // --- the machine ---------------------------------------------------------
    public static final String SHELL_EXECUTE = "shell.execute";
    public static final String PROCESS_SPAWN = "process.spawn";
    public static final String DEPLOYMENT_CREATE = "deployment.create";

    // --- the person ----------------------------------------------------------
    public static final String USER_ASK = "user.ask";

    // --- the platform itself -------------------------------------------------
    public static final String AGENT_SPAWN = "agent.spawn";
    public static final String PLUGIN_MANAGE = "plugin.manage";

    /** Everything the platform currently understands. */
    public static final List<String> ALL = Collections.unmodifiableList(Arrays.asList(
        FILESYSTEM_READ, FILESYSTEM_WRITE, FILESYSTEM_DELETE,
        NETWORK_REQUEST, BROWSER_NAVIGATE, BROWSER_READ,
        GITHUB_READ, GITHUB_WRITE,
        CREDENTIAL_READ, CREDENTIAL_WRITE, CREDENTIAL_EXPORT,
        SHELL_EXECUTE, PROCESS_SPAWN, DEPLOYMENT_CREATE,
        USER_ASK, AGENT_SPAWN, PLUGIN_MANAGE));

    private static final Set<String> KNOWN = new HashSet<>(ALL);

    /**
     * Capabilities a third-party plugin may never hold, whatever its manifest
     * says. Exporting a secret and managing plugins are the two that would let
     * an install undo every other decision on this list.
     */
    private static final Set<String> NEVER_FOR_PLUGINS = new HashSet<>(Arrays.asList(
        CREDENTIAL_EXPORT, PLUGIN_MANAGE));

    private Capability() {
    }

    public static boolean isKnown(String name) {
        return name != null && KNOWN.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    public static boolean grantableToPlugin(String name) {
        return isKnown(name) && !NEVER_FOR_PLUGINS.contains(name.trim().toLowerCase(Locale.ROOT));
    }

    /** The half of the name before the dot: what is being touched. */
    public static String area(String name) {
        if (name == null) {
            return "";
        }
        int dot = name.indexOf('.');
        return dot < 0 ? name : name.substring(0, dot);
    }

    /**
     * One sentence a person can read on an install screen. Deliberately plain:
     * "network.request" means nothing to anybody outside this file.
     */
    public static String describe(String name) {
        if (name == null) {
            return "";
        }
        switch (name) {
            case FILESYSTEM_READ:
                return "Read files in the folder you grant";
            case FILESYSTEM_WRITE:
                return "Write and change files in that folder";
            case FILESYSTEM_DELETE:
                return "Delete files in that folder";
            case NETWORK_REQUEST:
                return "Make requests over the network";
            case BROWSER_NAVIGATE:
                return "Open web pages";
            case BROWSER_READ:
                return "Read the page that is open";
            case GITHUB_READ:
                return "Read from your GitHub";
            case GITHUB_WRITE:
                return "Change things on your GitHub";
            case CREDENTIAL_READ:
                return "Use a saved key without seeing it";
            case CREDENTIAL_WRITE:
                return "Save a key of its own";
            case CREDENTIAL_EXPORT:
                return "Read your saved keys back out";
            case SHELL_EXECUTE:
                return "Run commands";
            case PROCESS_SPAWN:
                return "Start programs";
            case DEPLOYMENT_CREATE:
                return "Deploy things on your behalf";
            case USER_ASK:
                return "Ask you a question and wait";
            case AGENT_SPAWN:
                return "Start other agents";
            case PLUGIN_MANAGE:
                return "Install and remove plugins";
            default:
                return name;
        }
    }
}
