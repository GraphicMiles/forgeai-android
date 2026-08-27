package ai.luna.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * OpenAI-compatible chat completions, used for the "My computer" (Ollama) and
 * "Cloud" tabs.
 *
 * The reply streams. A cloud answer used to land in one lump at the end, which
 * made a fast provider feel identical to a stalled one; now the words arrive as
 * the provider sends them, the same as an on-device model.
 */
public final class CloudProvider {

    /** A configured endpoint: Ollama on the LAN, or a hosted key. */
    public static final class Config {
        public final String baseUrl;
        public final String apiKey;
        public final String model;

        public Config(String baseUrl, String apiKey, String model) {
            this.baseUrl = baseUrl == null ? "" : baseUrl.replaceAll("/+$", "");
            this.apiKey = apiKey == null ? "" : apiKey;
            this.model = model == null ? "" : model;
        }
    }

    /** Called for each piece of the answer as it arrives. */
    public interface TokenSink {
        void onToken(String text);
    }

    /** Asked between frames. Stop has to reach a cloud call too. */
    public interface Cancellation {
        boolean cancelled();
    }

    public static final class Reply {
        public final String text;
        public final String error;

        Reply(String text, String error) {
            this.text = text;
            this.error = error;
        }
    }

    private CloudProvider() {
    }

    /**
     * Streaming chat. Falls back to a single request when the provider will not
     * stream, so a provider that ignores the flag still answers.
     */
    public static Reply chatStreaming(Config config, List<JSONObject> messages, int maxTokens,
                                      TokenSink sink, Cancellation cancellation) {
        HttpURLConnection connection = null;
        BufferedReader reader = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", config.model);
            body.put("stream", true);
            body.put("max_tokens", maxTokens);
            JSONArray array = new JSONArray();
            for (JSONObject message : messages) {
                array.put(message);
            }
            body.put("messages", array);

            connection = (HttpURLConnection) new URL(config.baseUrl + "/chat/completions").openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(20000);
            connection.setReadTimeout(180000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "text/event-stream");
            if (!config.apiKey.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            }

            OutputStream output = connection.getOutputStream();
            try {
                output.write(body.toString().getBytes(StandardCharsets.UTF_8));
                output.flush();
            } finally {
                WorkspaceStore.closeQuietly(output);
            }

            int code = connection.getResponseCode();
            if (code >= 400) {
                String payload = readAll(connection.getErrorStream());
                return new Reply("", "The provider answered " + code + ": " + trim(payload, 200));
            }

            String contentType = connection.getContentType();
            if (contentType != null && !contentType.contains("event-stream")) {
                // The provider ignored the flag and sent one JSON document.
                String payload = readAll(connection.getInputStream());
                String text = firstChoice(payload);
                if (!text.isEmpty()) {
                    sink.onToken(text);
                }
                return new Reply(text, null);
            }

            StringBuilder whole = new StringBuilder();
            reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancellation != null && cancellation.cancelled()) {
                    // Whatever arrived is kept; the rest is abandoned.
                    return new Reply(whole.toString(), null);
                }
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.isEmpty() || data.equals("[DONE]")) {
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    continue;
                }
                try {
                    JSONObject frame = new JSONObject(data);
                    JSONArray choices = frame.optJSONArray("choices");
                    if (choices == null || choices.length() == 0) {
                        continue;
                    }
                    JSONObject delta = choices.getJSONObject(0).optJSONObject("delta");
                    String piece = delta == null ? "" : delta.optString("content", "");
                    if (!piece.isEmpty()) {
                        whole.append(piece);
                        sink.onToken(piece);
                    }
                } catch (Exception badFrame) {
                    // One malformed frame does not end the answer.
                }
            }
            if (whole.length() == 0) {
                return new Reply("", "The provider streamed nothing back.");
            }
            return new Reply(whole.toString(), null);
        } catch (Exception error) {
            return new Reply("", "Could not reach the provider: " + error.getMessage());
        } finally {
            WorkspaceStore.closeQuietly(reader);
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /**
     * What this provider says it serves today. Used instead of a list baked into
     * the app, which goes stale the moment a provider retires a model.
     */
    public static JSONArray listModels(Config config) throws Exception {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(config.baseUrl + "/models").openConnection();
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(20000);
            connection.setRequestProperty("Accept", "application/json");
            if (!config.apiKey.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
            }
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IllegalStateException("The provider answered " + code
                    + " when asked for its models.");
            }
            JSONObject json = new JSONObject(readAll(connection.getInputStream()));
            JSONArray data = json.optJSONArray("data");
            JSONArray out = new JSONArray();
            if (data == null) {
                return out;
            }
            for (int index = 0; index < data.length(); index++) {
                JSONObject item = data.optJSONObject(index);
                if (item == null) {
                    continue;
                }
                String id = item.optString("id", "");
                if (!id.isEmpty()) {
                    out.put(id);
                }
            }
            return out;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String firstChoice(String payload) {
        try {
            JSONObject json = new JSONObject(payload);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return "";
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            return message == null ? "" : message.optString("content", "");
        } catch (Exception error) {
            return "";
        }
    }

    /** Ask an Ollama server which models it is serving. */
    public static JSONArray ollamaModels(String endpoint) {
        HttpURLConnection connection = null;
        try {
            String base = endpoint.replaceAll("/+$", "");
            connection = (HttpURLConnection) new URL(base + "/api/tags").openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(6000);
            if (connection.getResponseCode() != 200) {
                return new JSONArray();
            }
            JSONObject json = new JSONObject(readAll(connection.getInputStream()));
            JSONArray models = json.optJSONArray("models");
            return models == null ? new JSONArray() : models;
        } catch (Exception error) {
            return new JSONArray();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String readAll(java.io.InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
        try {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                out.append(line).append('\n');
            }
            return out.toString();
        } finally {
            WorkspaceStore.closeQuietly(reader);
        }
    }

    private static String trim(String value, int limit) {
        if (value == null) {
            return "";
        }
        String single = value.replace('\n', ' ').trim();
        return single.length() > limit ? single.substring(0, limit) + "…" : single;
    }
}
