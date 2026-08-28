package ai.luna.app;

import java.net.URI;
import java.util.Locale;

/**
 * What an address has to be before a key or a prompt is attached to it.
 *
 * <p>The rule is HTTPS, with one deliberate exception: a machine on your own
 * network. Ollama and LM Studio serve plain HTTP on the LAN and always will,
 * and refusing that would mean refusing the one setup where nothing leaves the
 * building. Everything else — anything with a public host — must be encrypted,
 * because the alternative is posting an API key in clear text.
 *
 * <p>Pure string and address logic, so it is tested on a plain JVM.
 */
public final class EndpointPolicy {

    private EndpointPolicy() {
    }

    /** A refusal in plain words, or null when the address is fit to use. */
    public static String reason(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "The base address is empty.";
        }
        final URI uri;
        try {
            uri = new URI(value);
        } catch (Exception malformed) {
            return "That is not a valid web address.";
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost();
        if (host.trim().isEmpty()) {
            return "The address needs a host, like https://api.example.com/v1.";
        }
        if (uri.getRawUserInfo() != null) {
            return "Put the key in the key field, not in the address.";
        }
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return "The base address must not carry a query string.";
        }
        if (scheme.equals("https")) {
            return null;
        }
        if (!scheme.equals("http")) {
            return "The address must start with https.";
        }
        if (isPrivateHost(host)) {
            // Your own machine, on your own network. Nothing crosses the
            // internet, so plain HTTP costs nothing.
            return null;
        }
        return "Plain http is only allowed for a machine on your own network. "
            + "Use https for anything on the internet.";
    }

    /** True when the address is usable. */
    public static boolean isUsable(String raw) {
        return reason(raw) == null;
    }

    /** Loopback, link-local, or one of the three private IPv4 ranges. */
    public static boolean isPrivateHost(String rawHost) {
        String host = rawHost == null ? "" : rawHost.trim().toLowerCase(Locale.ROOT);
        if (host.isEmpty()) {
            return false;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            host = host.substring(1, host.length() - 1);
        }
        if (host.equals("localhost") || host.equals("::1") || host.endsWith(".local")) {
            return true;
        }
        String[] parts = host.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        int[] octets = new int[4];
        for (int index = 0; index < 4; index++) {
            try {
                octets[index] = Integer.parseInt(parts[index]);
            } catch (NumberFormatException notAnAddress) {
                return false;
            }
            if (octets[index] < 0 || octets[index] > 255) {
                return false;
            }
        }
        if (octets[0] == 127 || octets[0] == 10) {
            return true;
        }
        if (octets[0] == 192 && octets[1] == 168) {
            return true;
        }
        if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) {
            return true;
        }
        return octets[0] == 169 && octets[1] == 254;
    }

    /** Trailing slashes off, so joining a path never doubles one. */
    public static String tidy(String raw) {
        return raw == null ? "" : raw.trim().replaceAll("/+$", "");
    }
}
