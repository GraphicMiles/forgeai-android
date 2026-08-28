package ai.luna.runtime;

import ai.luna.contracts.InferenceProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which brain answers this one.
 *
 * <p>Luna already had three ways to think and one rule for choosing between
 * them: use the local model, and fall back to the cloud if it will not load.
 * That rule is fine until the questions differ — a long document that does not
 * fit in a 2k window, a private file that must not leave the phone, a provider
 * that has been returning 500s for the last ten minutes.
 *
 * <p>So the choice becomes explicit, and it explains itself. Every route comes
 * with the sentence that justified it, which is what goes in the debug panel
 * when somebody asks why the cloud answered a question they thought was local.
 */
public final class InferenceRouter {

    /** How long a provider that just failed is left alone. */
    private static final long COOLDOWN_MS = 120000L;

    /** What one job needs from whoever answers it. */
    public static final class Need {

        /** Roughly how much context this prompt wants. */
        public final int contextTokens;

        /** True when the prompt must not leave the device, whatever it costs. */
        public final boolean private_;

        /** The model the agent or the person asked for, or empty. */
        public final String preferred;

        /** False when the person has not allowed anything to leave the device. */
        public final boolean mayLeaveDevice;

        public Need(int contextTokens, boolean private_, String preferred,
                    boolean mayLeaveDevice) {
            this.contextTokens = Math.max(0, contextTokens);
            this.private_ = private_;
            this.preferred = preferred == null ? "" : preferred;
            this.mayLeaveDevice = mayLeaveDevice;
        }
    }

    /** One thing that could answer, and what it can take. */
    public static final class Candidate {

        public final String id;
        public final String label;
        public final boolean remote;
        public final int contextTokens;

        /** Lower is preferred when everything else is equal. */
        public final int rank;

        public Candidate(String id, String label, boolean remote, int contextTokens, int rank) {
            this.id = id;
            this.label = label;
            this.remote = remote;
            this.contextTokens = contextTokens;
            this.rank = rank;
        }
    }

    /** The decision, and the sentence that justifies it. */
    public static final class Route {

        public final Candidate chosen;
        public final String reason;

        Route(Candidate chosen, String reason) {
            this.chosen = chosen;
            this.reason = reason;
        }

        public boolean any() {
            return chosen != null;
        }

        public String id() {
            return chosen == null ? "" : chosen.id;
        }
    }

    private final Map<String, InferenceProvider> providers = new LinkedHashMap<>();
    private final Map<String, Long> restingUntil = new LinkedHashMap<>();
    private final Map<String, Integer> failures = new LinkedHashMap<>();
    private final Map<String, Integer> successes = new LinkedHashMap<>();

    public InferenceRouter register(InferenceProvider provider) {
        if (provider != null && !providers.containsKey(provider.id())) {
            providers.put(provider.id(), provider);
        }
        return this;
    }

    public InferenceProvider provider(String id) {
        return providers.get(id);
    }

    public List<InferenceProvider> providers() {
        return Collections.unmodifiableList(new ArrayList<>(providers.values()));
    }

    /**
     * Chooses.
     *
     * <p>In order: something the person explicitly asked for, if it is usable;
     * anything local that fits, because the device is free and private; then a
     * remote one, but only if the work is allowed to leave. A candidate that is
     * resting after a failure is skipped unless it is the only one left — a
     * degraded answer beats no answer, and saying so is part of the reason.
     */
    public Route choose(List<Candidate> candidates, Need need) {
        if (candidates == null || candidates.isEmpty()) {
            return new Route(null, "There is no model set up yet.");
        }

        Candidate preferred = named(candidates, need.preferred);
        if (preferred != null && usable(preferred, need) && !resting(preferred.id)) {
            return new Route(preferred, "You chose " + preferred.label + ".");
        }

        Candidate local = best(candidates, need, false, true);
        if (local != null) {
            return new Route(local, local.label + " runs on the phone, so the work stays here.");
        }

        if (need.private_) {
            return new Route(null, "That has to stay on the phone, and no model here can take "
                + "a job this size.");
        }
        if (!need.mayLeaveDevice) {
            return new Route(null, "Nothing on the phone can take a job this size, and you have "
                + "not allowed anything to leave the device.");
        }

        Candidate remote = best(candidates, need, true, true);
        if (remote != null) {
            return new Route(remote, why(candidates, need, remote));
        }

        Candidate tired = best(candidates, need, true, false);
        if (tired != null) {
            return new Route(tired, tired.label + " has been failing, but nothing else can take "
                + "this, so it is worth one more try.");
        }
        Candidate tiredLocal = best(candidates, need, false, false);
        if (tiredLocal != null) {
            return new Route(tiredLocal, tiredLocal.label + " has been failing, but it is all "
                + "there is.");
        }
        return new Route(null, "No model here can take a job this size.");
    }

    /** Why the phone did not get this one. */
    private String why(List<Candidate> candidates, Need need, Candidate chosen) {
        boolean hadLocal = false;
        boolean tooSmall = false;
        for (Candidate candidate : candidates) {
            if (!candidate.remote) {
                hadLocal = true;
                if (candidate.contextTokens > 0 && candidate.contextTokens < need.contextTokens) {
                    tooSmall = true;
                }
            }
        }
        if (!hadLocal) {
            return chosen.label + " answered, because there is no model on the phone.";
        }
        if (tooSmall) {
            return chosen.label + " answered, because this is bigger than the phone's model can "
                + "hold.";
        }
        return chosen.label + " answered, because the model here could not.";
    }

    private Candidate named(List<Candidate> candidates, String id) {
        if (id.isEmpty()) {
            return null;
        }
        for (Candidate candidate : candidates) {
            if (candidate.id.equals(id)) {
                return candidate;
            }
        }
        return null;
    }

    private Candidate best(List<Candidate> candidates, Need need, boolean remote,
                           boolean healthyOnly) {
        Candidate best = null;
        for (Candidate candidate : candidates) {
            if (candidate.remote != remote || !usable(candidate, need)) {
                continue;
            }
            if (healthyOnly && resting(candidate.id)) {
                continue;
            }
            if (best == null || candidate.rank < best.rank) {
                best = candidate;
            }
        }
        return best;
    }

    private boolean usable(Candidate candidate, Need need) {
        if (candidate.remote && (need.private_ || !need.mayLeaveDevice)) {
            return false;
        }
        return candidate.contextTokens <= 0 || need.contextTokens <= 0
            || candidate.contextTokens >= need.contextTokens;
    }

    // --- health ---------------------------------------------------------------

    /** A provider that just failed is left alone for two minutes. */
    public void failed(String id) {
        failures.put(id, count(failures, id) + 1);
        restingUntil.put(id, System.currentTimeMillis() + COOLDOWN_MS);
    }

    public void worked(String id) {
        successes.put(id, count(successes, id) + 1);
        restingUntil.remove(id);
    }

    public boolean resting(String id) {
        Long until = restingUntil.get(id);
        if (until == null) {
            return false;
        }
        if (System.currentTimeMillis() >= until) {
            restingUntil.remove(id);
            return false;
        }
        return true;
    }

    /** Who has been working and who has not, for the debug panel. */
    public JSONArray health() {
        JSONArray out = new JSONArray();
        List<String> ids = new ArrayList<>(providers.keySet());
        for (String id : failures.keySet()) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        for (String id : successes.keySet()) {
            if (!ids.contains(id)) {
                ids.add(id);
            }
        }
        for (String id : ids) {
            try {
                JSONObject row = new JSONObject();
                row.put("id", id);
                row.put("worked", count(successes, id));
                row.put("failed", count(failures, id));
                row.put("resting", resting(id));
                out.put(row);
            } catch (Exception ignored) {
                // One unprintable row does not sink the list.
            }
        }
        return out;
    }

    private int count(Map<String, Integer> counts, String id) {
        Integer value = counts.get(id);
        return value == null ? 0 : value;
    }
}
