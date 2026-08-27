package ai.luna.app;

import java.net.URI;
import java.util.Locale;

/**
 * Where the browser is allowed to go.
 *
 * A model that can name a URL can name any URL, including the router on the
 * same Wi-Fi or a metadata service. Only http and https are allowed, and only
 * to addresses that are not on this device or this network.
 */
public final class NetworkTargets {

    private NetworkTargets() {
    }

    /** Null when the address is fine, otherwise a plain reason it was refused. */
    public static String check(String url) {
        if (url == null || url.trim().isEmpty()) {
            return "no address was given";
        }
        URI parsed;
        try {
            parsed = new URI(normalise(url));
        } catch (Exception error) {
            return "that is not an address";
        }
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase(Locale.US);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return "only web pages can be opened";
        }
        String host = parsed.getHost() == null ? "" : parsed.getHost().toLowerCase(Locale.US);
        if (host.isEmpty()) {
            return "that address has no host";
        }
        if (host.equals("localhost") || host.endsWith(".local") || host.endsWith(".internal")) {
            return "that address is on this device or this network";
        }
        if (isPrivateAddress(host)) {
            return "that address is on this device or this network";
        }
        return null;
    }

    public static String normalise(String url) {
        String trimmed = url.trim();
        if (!trimmed.toLowerCase(Locale.US).startsWith("http://")
            && !trimmed.toLowerCase(Locale.US).startsWith("https://")) {
            return "https://" + trimmed;
        }
        return trimmed;
    }

    /** Loopback, link-local and the three private IPv4 ranges. */
    static boolean isPrivateAddress(String host) {
        if (host.equals("0.0.0.0") || host.startsWith("127.") || host.equals("::1")
            || host.startsWith("[::1")) {
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
            } catch (NumberFormatException notAnIp) {
                return false;
            }
            if (octets[index] < 0 || octets[index] > 255) {
                return false;
            }
        }
        if (octets[0] == 10) {
            return true;
        }
        if (octets[0] == 192 && octets[1] == 168) {
            return true;
        }
        if (octets[0] == 172 && octets[1] >= 16 && octets[1] <= 31) {
            return true;
        }
        if (octets[0] == 169 && octets[1] == 254) {
            return true;
        }
        return false;
    }
}
