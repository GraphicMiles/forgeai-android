package ai.luna.contracts;

import org.json.JSONObject;

import java.util.List;

/**
 * Something that can think.
 *
 * <p>Luna has three of these already — llama.cpp on the phone, Ollama on your
 * own machine, and the cloud shapes — and they are three unrelated pieces of
 * code. Behind one interface they become interchangeable, and a router can
 * choose between them per job: a greeting on the phone, a refactor on a VPS, a
 * private document never leaving the device.
 */
public interface InferenceProvider {

    /** {@code local.llamacpp}, {@code ollama}, {@code cloud.openai}, … */
    String id();

    String displayName();

    /** Does the work leave this device? */
    boolean remote();

    /** What it can do: {@code stream}, {@code tools}, {@code vision}, … */
    List<String> capabilities();

    /** Model ids this provider serves right now. Never a baked-in list. */
    List<String> listModels() throws Exception;

    /**
     * Ask one real question of one model, right now. Null when it answers,
     * otherwise the reason it cannot be used. Being listed is not the same as
     * being usable.
     */
    String probe(String modelId);

    /** Generate an answer, streaming tokens as they arrive. */
    Reply generate(Request request, TokenSink sink, Cancellation cancellation);

    /** Stop whatever is in flight. */
    void cancel();

    /** One request, in the platform's own terms rather than a provider's. */
    final class Request {
        public final String modelId;

        /** Role and content pairs, oldest first. Roles: system, user, assistant. */
        public final List<JSONObject> messages;

        public final int maxTokens;

        /** Provider-specific extras, empty by default. */
        public final JSONObject options;

        public Request(String modelId, List<JSONObject> messages, int maxTokens, JSONObject options) {
            this.modelId = modelId == null ? "" : modelId;
            this.messages = messages;
            this.maxTokens = maxTokens <= 0 ? 512 : maxTokens;
            this.options = options == null ? new JSONObject() : options;
        }
    }

    /** What came back, and what it cost. */
    final class Reply {
        public final String text;
        public final String error;
        public final long promptTokens;
        public final long outputTokens;
        public final double tokensPerSecond;

        public Reply(String text, String error, long promptTokens, long outputTokens,
                     double tokensPerSecond) {
            this.text = text == null ? "" : text;
            this.error = error;
            this.promptTokens = promptTokens;
            this.outputTokens = outputTokens;
            this.tokensPerSecond = tokensPerSecond;
        }

        public static Reply of(String text) {
            return new Reply(text, null, 0L, 0L, 0d);
        }

        public static Reply failed(String reason) {
            return new Reply("", reason, 0L, 0L, 0d);
        }

        public boolean ok() {
            return error == null;
        }
    }

    interface TokenSink {
        void onToken(String text);
    }

    interface Cancellation {
        boolean cancelled();
    }
}
