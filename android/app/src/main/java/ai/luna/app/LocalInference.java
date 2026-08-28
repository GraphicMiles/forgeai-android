package ai.luna.app;

import ai.luna.contracts.InferenceProvider;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * llama.cpp on this phone, behind the platform's inference contract.
 *
 * <p>The runtime below is unchanged. What this adds is the translation the
 * platform needs: a list of role messages in, ChatML out, because a local model
 * takes one string and every other provider takes a conversation.
 */
public final class LocalInference implements InferenceProvider {

    private final OnDeviceRuntime runtime;
    private final ModelStore models;

    public LocalInference(OnDeviceRuntime runtime, ModelStore models) {
        this.runtime = runtime;
        this.models = models;
    }

    @Override
    public String id() {
        return "local.llamacpp";
    }

    @Override
    public String displayName() {
        return "This phone";
    }

    @Override
    public boolean remote() {
        return false;
    }

    @Override
    public List<String> capabilities() {
        return Arrays.asList("stream", "cancel", "offline");
    }

    /** What is actually on the disk, not what the catalogue offers. */
    @Override
    public List<String> listModels() {
        List<String> out = new ArrayList<>();
        for (ModelStore.Entry entry : ModelStore.CATALOG) {
            if (models != null && models.isInstalled(entry)) {
                out.add(entry.id);
            }
        }
        return out;
    }

    @Override
    public String probe(String modelId) {
        if (modelId == null || modelId.isEmpty()) {
            return "No model is chosen.";
        }
        ModelStore.Entry entry = ModelStore.find(modelId);
        if (entry == null) {
            return "That model is not in the catalogue.";
        }
        if (models != null && !models.isInstalled(entry)) {
            return "That model is not downloaded yet.";
        }
        return null;
    }

    @Override
    public Reply generate(Request request, final TokenSink sink, Cancellation cancellation) {
        ModelStore.Entry entry = ModelStore.find(request.modelId);
        if (entry == null) {
            return Reply.failed("That model is not in the catalogue.");
        }
        if (!runtime.isLoaded() || !entry.id.equals(runtime.loadedModelId())) {
            if (models == null || !runtime.load(entry.id, models.fileFor(entry))) {
                return Reply.failed("That model would not load.");
            }
        }
        OnDeviceRuntime.Result result = runtime.generate(
            chatMl(request.messages),
            request.maxTokens,
            entry.contextTokens,
            DeviceCapacity.suggestedThreads(),
            new OnDeviceRuntime.TokenSink() {
                @Override
                public void onToken(String text) {
                    if (sink != null) {
                        sink.onToken(text);
                    }
                }
            });
        String failure = result.failure();
        if (failure != null) {
            return Reply.failed(failure);
        }
        return new Reply(result.text, null, result.promptTokens, result.outputTokens,
            result.tokensPerSecond());
    }

    @Override
    public void cancel() {
        runtime.cancel();
    }

    /** ChatML, which every model in the catalogue was trained on. */
    static String chatMl(List<JSONObject> messages) {
        StringBuilder out = new StringBuilder();
        if (messages != null) {
            for (JSONObject message : messages) {
                String role = message.optString("role", "user");
                String content = message.optString("content", "");
                out.append("<|im_start|>").append(role).append('\n')
                    .append(content).append("<|im_end|>\n");
            }
        }
        out.append("<|im_start|>assistant\n");
        return out.toString();
    }
}
