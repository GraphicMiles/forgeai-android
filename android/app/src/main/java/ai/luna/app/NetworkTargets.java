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

    /**
     * The same check, plus what the name actually resolves to. A public
     * hostname pointed at 192.168.x.x is the oldest trick there is, and the
     * cheap string check above cannot see it. Only used for the page the model
     * asked for, not for every sub-resource, because it costs a DNS lookup.
     */
    public static String checkResolved(String url) {
        String verdict = check(url);
        if (verdict != null) {
            return verdict;
        }
        try {
            String host = new URI(normalise(url)).getHost();
            if (host == null) {
                return "that address has no host";
            }
            for (java.net.InetAddress address : java.net.InetAddress.getAllByName(host)) {
                if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                    return "that address is on this device or this network";
                }
            }
        } catch (java.net.UnknownHostException unknown) {
            return "that address does not resolve";
        } catch (Exception ignored) {
            // A resolver failure is not proof of anything; the string checks stand.
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

    /** Loopback, link-local, unique-local and the three private IPv4 ranges. */
    static boolean isPrivateAddress(String host) {
        if (host.equals("0.0.0.0") || host.startsWith("127.") || host.equals("::1")
            || host.startsWith("[::1")) {
            return true;
        }
        String bare = host.startsWith("[") ? host.substring(1, Math.max(1, host.length() - 1)) : host;
        if (bare.contains(":")) {
            // IPv6 literal: fc00::/7 is unique-local, fe80::/10 is link-local.
            String lower = bare.toLowerCase(Locale.US);
            return lower.startsWith("fc") || lower.startsWith("fd")
                || lower.startsWith("fe8") || lower.startsWith("fe9")
                || lower.startsWith("fea") || lower.startsWith("feb")
                || lower.equals("::") || lower.startsWith("::ffff:127.")
                || lower.startsWith("::ffff:10.") || lower.startsWith("::ffff:192.168.");
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
