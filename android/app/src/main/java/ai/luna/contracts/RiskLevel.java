package ai.luna.contracts;

import java.util.Locale;

/**
 * How much a call could cost you if it were wrong.
 *
 * <p>Risk is a property of the tool, not of the mood the app is in. The policy
 * engine reads it alongside the capabilities, the agent's own policy and the
 * user's mode; nothing here decides anything on its own.
 */
public enum RiskLevel {

    /** Reads something already in front of the person. */
    LOW,

    /** Changes something that can be undone. */
    MEDIUM,

    /** Changes something outside the app, or runs code. */
    HIGH,

    /** Would hand over a secret or destroy something unrecoverable. */
    CRITICAL;

    public static RiskLevel of(String raw) {
        if (raw == null) {
            return LOW;
        }
        switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "medium":
                return MEDIUM;
            case "high":
                return HIGH;
            case "critical":
                return CRITICAL;
            default:
                return LOW;
        }
    }

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public boolean atLeast(RiskLevel other) {
        return ordinal() >= other.ordinal();
    }
}
