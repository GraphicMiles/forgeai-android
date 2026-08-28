package ai.luna.app;

import ai.luna.builtin.DeclaredEnvironment;
import ai.luna.contracts.BrowserProvider;
import ai.luna.contracts.Capability;
import ai.luna.contracts.ExecutionProvider;
import ai.luna.contracts.RiskLevel;
import ai.luna.contracts.SecretProvider;
import ai.luna.contracts.StorageProvider;
import ai.luna.contracts.ToolContext;
import ai.luna.contracts.ToolDefinition;
import ai.luna.contracts.Trace;
import ai.luna.runtime.EnvironmentRegistry;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.List;

/**
 * Where work runs.
 *
 * <p>A phone cannot open a shell and never will. The point of this phase is
 * that the answer to "can Luna do this?" stops being "no" and becomes "not
 * here" — while nothing is allowed to pretend that a machine which is merely
 * described is a machine that is reachable.
 */
public final class EnvironmentTest {

    private static int passed;
    private static int failed;

    public static void main(String[] args) {
        thePhone();
        declaring();
        switching();
        routing();
        context();
        describing();

        System.out.println();
        if (failed > 0) {
            System.out.println(failed + " FAILED, " + passed + " passed");
            System.exit(1);
        }
        System.out.println("ALL PASS");
    }

    private static void thePhone() {
        EnvironmentRegistry registry = new EnvironmentRegistry().register(phone());
        check("the phone is registered", registry.has("android.local"));
        check("and is what work runs in", registry.activeId().equals("android.local"));
        check("it offers the capabilities it has",
            registry.capabilities().contains(Capability.FILESYSTEM_READ));
        check("and not the ones it does not",
            !registry.capabilities().contains(Capability.SHELL_EXECUTE));
    }

    private static void declaring() {
        JSONObject json = new JSONObject();
        try {
            json.put("id", "vps.ssh");
            json.put("name", "The build box");
            json.put("platform", "server");
            json.put("capabilities", new org.json.JSONArray(Arrays.asList(
                Capability.SHELL_EXECUTE, Capability.FILESYSTEM_READ,
                Capability.FILESYSTEM_WRITE)));
        } catch (Exception ignored) {
            // Strings only.
        }
        DeclaredEnvironment vps = DeclaredEnvironment.fromJson(json);
        check("a declared machine has a name", vps.displayName().equals("The build box"));
        check("and a platform", vps.platform().equals("server"));
        check("it is not this device", !vps.local());
        check("it is honest about not being reachable", !vps.available());
        check("and says so in words", vps.problem().contains("not connected"));
        check("it has no storage to hand out", vps.storage() == null);
    }

    private static void switching() {
        EnvironmentRegistry registry = registry();
        check("you cannot move work to a machine that is not there",
            !registry.activate("vps.ssh"));
        check("so the phone is still active", registry.activeId().equals("android.local"));
        check("nor to one nobody declared", !registry.activate("mars"));
        check("moving to a reachable one works", registry.activate("android.local"));
    }

    private static void routing() {
        EnvironmentRegistry registry = registry();

        ToolDefinition read = ToolDefinition.of("read_file", "Read a file")
            .capabilities(Capability.FILESYSTEM_READ)
            .risk(RiskLevel.LOW)
            .build();
        check("an ordinary tool runs on the phone",
            registry.where(read).id().equals("android.local"));
        check("so nothing is said about elsewhere", registry.elsewhere(read).isEmpty());

        ToolDefinition shell = ToolDefinition.of("shell_exec", "Run a command")
            .capabilities(Capability.SHELL_EXECUTE)
            .supports("server", "desktop")
            .risk(RiskLevel.CRITICAL)
            .build();
        check("a shell tool has nowhere to run while the box is offline",
            registry.where(shell) == null);
        check("and Luna does not offer it", registry.elsewhere(shell).isEmpty());

        EnvironmentRegistry connected = new EnvironmentRegistry()
            .register(phone())
            .register(reachableBox());
        check("with the box connected, the shell tool has a home",
            connected.where(shell).id().equals("vps.ssh"));
        check("and Luna can say where",
            connected.elsewhere(shell).contains("The build box"));
        check("while ordinary tools still stay on the phone",
            connected.where(read).id().equals("android.local"));
    }

    private static void context() {
        EnvironmentRegistry registry = registry();
        ToolContext context = registry.contextFor("luna", Trace.SILENT);
        check("the context comes from the active environment",
            context.platform.equals("android"));
        check("with its storage", context.hasStorage());
        check("its browser", context.hasBrowser());
        check("and the agent that asked", context.agentId.equals("luna"));
    }

    private static void describing() {
        EnvironmentRegistry registry = registry();
        check("both machines are listed", registry.describe().length() == 2);
        JSONObject phone = registry.describe().optJSONObject(0);
        check("the phone is shown as active", phone.optBoolean("active"));
        check("and as available", phone.optBoolean("available"));
        JSONObject box = registry.describe().optJSONObject(1);
        check("the other one is shown as unavailable", !box.optBoolean("available"));
        check("with the reason a person can read",
            box.optString("problem").contains("not connected"));
    }

    // --- helpers --------------------------------------------------------------

    private static EnvironmentRegistry registry() {
        return new EnvironmentRegistry()
            .register(phone())
            .register(new DeclaredEnvironment("vps.ssh", "The build box", "server",
                Arrays.asList(Capability.SHELL_EXECUTE, Capability.FILESYSTEM_READ),
                "The build box is not connected yet."));
    }

    private static ExecutionProvider phone() {
        return new Phone(true);
    }

    private static ExecutionProvider reachableBox() {
        return new Box();
    }

    /** The phone, without needing an Android runtime to make one. */
    private static final class Phone implements ExecutionProvider {

        private final boolean up;

        Phone(boolean up) {
            this.up = up;
        }

        @Override
        public String id() {
            return "android.local";
        }

        @Override
        public String displayName() {
            return "This phone";
        }

        @Override
        public String platform() {
            return "android";
        }

        @Override
        public boolean local() {
            return true;
        }

        @Override
        public List<String> capabilities() {
            return Arrays.asList(Capability.FILESYSTEM_READ, Capability.FILESYSTEM_WRITE,
                Capability.FILESYSTEM_DELETE, Capability.NETWORK_REQUEST,
                Capability.BROWSER_NAVIGATE, Capability.BROWSER_READ, Capability.GITHUB_READ,
                Capability.CREDENTIAL_READ, Capability.USER_ASK);
        }

        @Override
        public StorageProvider storage() {
            return new Fakes.FakeStorage();
        }

        @Override
        public BrowserProvider browser() {
            return new Fakes.FakeBrowser();
        }

        @Override
        public SecretProvider secrets() {
            return null;
        }

        @Override
        public boolean available() {
            return up;
        }

        @Override
        public String problem() {
            return up ? null : "This phone is unavailable, which would be surprising.";
        }
    }

    /** A machine that answers, for the one test that needs a reachable one. */
    private static final class Box implements ExecutionProvider {

        @Override
        public String id() {
            return "vps.ssh";
        }

        @Override
        public String displayName() {
            return "The build box";
        }

        @Override
        public String platform() {
            return "server";
        }

        @Override
        public boolean local() {
            return false;
        }

        @Override
        public List<String> capabilities() {
            return Arrays.asList(Capability.SHELL_EXECUTE, Capability.FILESYSTEM_READ,
                Capability.FILESYSTEM_WRITE, Capability.PROCESS_SPAWN);
        }

        @Override
        public StorageProvider storage() {
            return new Fakes.FakeStorage();
        }

        @Override
        public BrowserProvider browser() {
            return null;
        }

        @Override
        public SecretProvider secrets() {
            return null;
        }

        @Override
        public boolean available() {
            return true;
        }

        @Override
        public String problem() {
            return null;
        }
    }

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
