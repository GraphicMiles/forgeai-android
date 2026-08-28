package ai.luna.app;

import ai.luna.contracts.InferenceProvider;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The cloud shapes, behind the platform's inference contract.
 *
 * <p>{@link CloudProvider} keeps doing the work — three wire formats, four auth
 * styles, one request builder shared by the probe and the run. This class only
 * says so in the language the runtime will use once a router is choosing
 * between a phone, a laptop and a server.
 */
public final class CloudInference implements InferenceProvider {

    private final CloudProvider.Config config;
    private final String label;
    private volatile boolean cancelled;

    public CloudInference(CloudProvider.Config config, String label) {
        this.config = config;
        this.label = label == null || label.isEmpty() ? "Cloud provider" : label;
    }

    @Override
    public String id() {
        return "cloud." + config.kind;
    }

    @Override
    public String displayName() {
        return label;
    }

    @Override
    public boolean remote() {
        return true;
    }

    @Override
    public List<String> capabilities() {
        return Arrays.asList("stream", "cancel", "list", "probe");
    }

    @Override
    public List<String> listModels() throws Exception {
        List<String> out = new ArrayList<>();
        org.json.JSONArray ids = CloudProvider.listModels(config.withModel(""));
        for (int index = 0; index < ids.length(); index++) {
            String id = ids.optString(index, "");
            if (!id.isEmpty()) {
                out.add(id);
            }
        }
        return out;
    }

    @Override
    public String probe(String modelId) {
        return CloudProvider.probe(config.withModel(modelId));
    }

    @Override
    public Reply generate(Request request, final TokenSink sink, final Cancellation cancellation) {
        cancelled = false;
        CloudProvider.Reply reply = CloudProvider.chatStreaming(
            config.withModel(request.modelId),
            request.messages,
            request.maxTokens,
            new CloudProvider.TokenSink() {
                @Override
                public void onToken(String text) {
                    if (sink != null) {
                        sink.onToken(text);
                    }
                }
            },
            new CloudProvider.Cancellation() {
                @Override
                public boolean cancelled() {
                    return cancelled || (cancellation != null && cancellation.cancelled());
                }
            });
        if (reply.error != null) {
            return Reply.failed(reply.error);
        }
        return Reply.of(reply.text);
    }

    @Override
    public void cancel() {
        cancelled = true;
    }

    /** The configuration this provider speaks to. */
    public CloudProvider.Config config() {
        return config;
    }

    /** A message in the shape {@link Request} wants. */
    public static JSONObject message(String role, String content) {
        JSONObject json = new JSONObject();
        try {
            json.put("role", role);
            json.put("content", content);
        } catch (org.json.JSONException ignored) {
            // Both values are strings; this cannot fail in practice.
        }
        return json;
    }
}
