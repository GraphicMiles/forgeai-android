package ai.luna.app;

import ai.luna.contracts.Capability;
import ai.luna.contracts.RiskLevel;
import ai.luna.contracts.ToolDefinition;
import ai.luna.contracts.ToolResult;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The contracts, checked on a plain JVM.
 *
 * <p>These are the tests that stop the platform layer and the app layer from
 * drifting apart: the same tool cannot be mutating in one file and harmless in
 * another, and no capability may be invented in passing.
 */
public final class ContractsTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        capabilities();
        definitions();
        agreementWithPolicy();
        serialisation();
        results();
        secretNamespaces();

        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " FAILED, " + passed + " passed");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    // --- capabilities ---------------------------------------------------------

    private static void capabilities() {
        check("a real capability is known", Capability.isKnown("filesystem.read"));
        check("an invented one is not", !Capability.isKnown("filesystem.everything"));
        check("case does not matter", Capability.isKnown("FileSystem.Read"));
        check("the area is the half before the dot",
            Capability.area(Capability.BROWSER_NAVIGATE).equals("browser"));
        check("a plugin may ask to read files",
            Capability.grantableToPlugin(Capability.FILESYSTEM_READ));
        check("a plugin may never export a secret",
            !Capability.grantableToPlugin(Capability.CREDENTIAL_EXPORT));
        check("a plugin may never manage plugins",
            !Capability.grantableToPlugin(Capability.PLUGIN_MANAGE));
        check("every capability has a sentence", everyCapabilityIsDescribed());
        check("risk parses", RiskLevel.of("HIGH") == RiskLevel.HIGH);
        check("an unknown risk is the safe reading", RiskLevel.of("banana") == RiskLevel.LOW);
        check("critical outranks high", RiskLevel.CRITICAL.atLeast(RiskLevel.HIGH));
    }

    private static boolean everyCapabilityIsDescribed() {
        for (String name : Capability.ALL) {
            String sentence = Capability.describe(name);
            if (sentence == null || sentence.equals(name) || sentence.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    // --- definitions ----------------------------------------------------------

    private static void definitions() {
        BuiltinTools tools = new BuiltinTools();
        List<ToolDefinition> all = tools.definitions();
        check("the built-ins are all described", all.size() == BuiltinTools.ids().size());
        check("every one is credited to core", everyOneOwnedBy(all, "core"));
        check("every one declares a capability or is respond", everyOneDeclares(all));
        check("every declared capability is real", everyCapabilityIsReal(all));
        check("the provider owns read_file", tools.owns("read_file"));
        check("the provider does not own a stranger", !tools.owns("deploy.vercel"));

        ToolDefinition read = tools.definition("read_file");
        check("read_file needs a workspace", read.requires.contains("workspace"));
        check("read_file is low risk", read.risk == RiskLevel.LOW);
        check("read_file only looks", !read.mutating());
        check("read_file runs on a server too", read.runsOn("server"));

        ToolDefinition delete = tools.definition("delete_file");
        check("delete is high risk", delete.risk == RiskLevel.HIGH);
        check("delete changes things", delete.mutating());
        check("delete names the delete capability",
            delete.needs(Capability.FILESYSTEM_DELETE));

        ToolDefinition ask = tools.definition("ask_user");
        check("asking a person may take ten minutes", ask.timeoutMs == 600000L);

        check("a built-in has a dotted platform name",
            BuiltinTools.canonical("read_file").equals("filesystem.read"));
        check("an unknown id is left alone",
            BuiltinTools.canonical("acme.deploy").equals("acme.deploy"));
        check("every built-in has a canonical name", everyOneIsRenamed());
        check("the canonical names are unique", canonicalNamesAreUnique());
    }

    private static boolean everyOneOwnedBy(List<ToolDefinition> all, String owner) {
        for (ToolDefinition definition : all) {
            if (!owner.equals(definition.providerId)) {
                return false;
            }
        }
        return true;
    }

    private static boolean everyOneDeclares(List<ToolDefinition> all) {
        for (ToolDefinition definition : all) {
            if (definition.capabilities.isEmpty() && !definition.id.equals("respond")) {
                return false;
            }
        }
        return true;
    }

    private static boolean everyCapabilityIsReal(List<ToolDefinition> all) {
        for (ToolDefinition definition : all) {
            for (String capability : definition.capabilities) {
                if (!Capability.isKnown(capability)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean everyOneIsRenamed() {
        for (String id : BuiltinTools.ids()) {
            String canonical = BuiltinTools.canonical(id);
            if (canonical.equals(id) || canonical.indexOf('.') < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean canonicalNamesAreUnique() {
        Set<String> seen = new HashSet<>();
        for (String id : BuiltinTools.ids()) {
            if (!seen.add(BuiltinTools.canonical(id))) {
                return false;
            }
        }
        return true;
    }

    // --- the two layers must agree -------------------------------------------

    /**
     * The definition's risk and the gate's idea of "mutating" are two ways of
     * saying the same thing. If they ever disagree, one of them is lying to the
     * person tapping Allow.
     */
    private static void agreementWithPolicy() {
        BuiltinTools tools = new BuiltinTools();
        boolean agreed = true;
        for (ToolDefinition definition : tools.definitions()) {
            if (definition.mutating() != ToolPolicy.isMutating(definition.id)) {
                agreed = false;
                System.out.println("  disagreement on " + definition.id);
            }
        }
        check("risk and the permission gate agree on every tool", agreed);

        boolean folders = true;
        for (ToolDefinition definition : tools.definitions()) {
            if (definition.requires.contains("workspace") != ToolPolicy.needsFolder(definition.id)) {
                folders = false;
                System.out.println("  folder disagreement on " + definition.id);
            }
        }
        check("the folder requirement agrees too", folders);

        boolean known = true;
        for (ToolDefinition definition : tools.definitions()) {
            if (!ToolPolicy.isKnown(definition.id)) {
                known = false;
            }
        }
        check("every described tool is one the engine knows", known);
        check("every tool the engine knows is described",
            tools.definitions().size()
                == ToolPolicy.READ_ONLY.size() + ToolPolicy.MUTATING.size());
    }

    // --- data in, data out ----------------------------------------------------

    private static void serialisation() {
        BuiltinTools tools = new BuiltinTools();
        try {
            JSONObject json = tools.definition("write_file").toJson();
            check("the id survives json", json.optString("id").equals("write_file"));
            check("permissions are named permissions in the manifest",
                json.optJSONArray("permissions").length() == 1);
            ToolDefinition back = ToolDefinition.fromJson(json);
            check("a definition round-trips", back.id.equals("write_file"));
            check("so does its risk", back.risk == RiskLevel.MEDIUM);
            check("so does its timeout", back.timeoutMs == 30000L);
            check("so do its capabilities", back.needs(Capability.FILESYSTEM_WRITE));
            check("so does what it requires", back.requires.contains("workspace"));
        } catch (Exception error) {
            check("a definition round-trips without throwing: " + error, false);
        }
        String line = tools.definition("read_file").promptLine();
        check("the prompt line names the tool", line.contains("\"read_file\""));
        check("the prompt line names its argument", line.contains("path"));
    }

    private static void results() {
        ToolResult ok = ToolResult.ok("Wrote notes.md.", 12L);
        check("a success is a success", ok.ok);
        check("a success carries no error", ok.error == null);
        check("a success keeps its timing", ok.tookMs == 12L);

        ToolResult bad = ToolResult.failed("the folder is gone");
        check("a failure is not ok", !bad.ok);
        check("a failure reads as one", bad.observation.startsWith("Failed: "));

        ToolResult slow = ToolResult.unfinished("read_page", 90000L);
        check("an abandoned call says how long it waited",
            slow.observation.contains("90 seconds"));

        ToolResult denied = ToolResult.denied(Capability.SHELL_EXECUTE);
        check("a refusal says what was missing", denied.observation.contains("run commands"));
    }

    private static void secretNamespaces() {
        check("the core keeps the names it always used",
            CredentialVault.scoped("core", "github").equals("github"));
        check("an empty owner is the core",
            CredentialVault.scoped("", "cloud:17").equals("cloud:17"));
        check("a plugin is namespaced",
            CredentialVault.scoped("acme", "token").equals("plugin:acme/token"));
        check("one plugin cannot name its way into another",
            !CredentialVault.scoped("acme", "../other/token")
                .equals(CredentialVault.scoped("other", "token")));
        check("two plugins with the same key do not collide",
            !CredentialVault.scoped("a", "k").equals(CredentialVault.scoped("b", "k")));
    }

    // --- plumbing -------------------------------------------------------------

    private static void check(String what, boolean condition) {
        if (condition) {
            passed++;
            System.out.println("  pass  " + what);
        } else {
            failed++;
            System.out.println("  FAIL  " + what);
        }
    }
}
