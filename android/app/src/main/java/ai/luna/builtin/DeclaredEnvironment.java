package ai.luna.builtin;

import ai.luna.contracts.BrowserProvider;
import ai.luna.contracts.ExecutionProvider;
import ai.luna.contracts.SecretProvider;
import ai.luna.contracts.StorageProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * An environment somebody has told Luna about but nothing can reach yet.
 *
 * <p>A laptop on the same network, a VPS over SSH, a Docker container. Each is
 * described — a name, a platform, the capabilities it would offer — and each
 * answers honestly that it is not connected. That honesty is the feature: the
 * registry can plan around a machine that could run a shell without anything
 * pretending a shell has run.
 *
 * <p>When the transport for one of these is built, it replaces this class and
 * nothing above it changes.
 */
public final class DeclaredEnvironment implements ExecutionProvider {

    private final String id;
    private final String name;
    private final String platform;
    private final List<String> capabilities;
    private final String problem;

    public DeclaredEnvironment(String id, String name, String platform,
                               List<String> capabilities, String problem) {
        this.id = id;
        this.name = name;
        this.platform = platform;
        this.capabilities = Collections.unmodifiableList(new ArrayList<>(capabilities));
        this.problem = problem;
    }

    /** Reads one out of settings or a plugin manifest. */
    public static DeclaredEnvironment fromJson(JSONObject json) {
        List<String> capabilities = new ArrayList<>();
        JSONArray array = json.optJSONArray("capabilities");
        if (array != null) {
            for (int index = 0; index < array.length(); index++) {
                String value = array.optString(index, "");
                if (!value.isEmpty()) {
                    capabilities.add(value);
                }
            }
        }
        String name = json.optString("name", json.optString("id", "A machine"));
        return new DeclaredEnvironment(
            json.optString("id", "").toLowerCase(Locale.US),
            name,
            json.optString("platform", "server"),
            capabilities,
            json.optString("problem", name + " is not connected yet."));
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String displayName() {
        return name;
    }

    @Override
    public String platform() {
        return platform;
    }

    @Override
    public boolean local() {
        return false;
    }

    @Override
    public List<String> capabilities() {
        return capabilities;
    }

    @Override
    public StorageProvider storage() {
        return null;
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
        return false;
    }

    @Override
    public String problem() {
        return problem;
    }
}
