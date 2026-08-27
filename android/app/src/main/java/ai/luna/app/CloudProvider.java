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
 * "Cloud" tabs. One request, one answer — no streaming, so a slow provider
 * shows as a spinner rather than a stalled trickle.
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

    /** Blocking. Call from a worker thread. */
    public static Reply chat(Config config, List<JSONObject> messages, int maxTokens) {
        HttpURLConnection connection = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", config.model);
            body.put("stream", false);
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
            String payload = readAll(code >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (code >= 400) {
                return new Reply("", "The provider answered " + code + ": " + trim(payload, 200));
            }

            JSONObject json = new JSONObject(payload);
            JSONArray choices = json.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return new Reply("", "The provider returned no choices.");
            }
            JSONObject message = choices.getJSONObject(0).optJSONObject("message");
            String content = message == null ? "" : message.optString("content", "");
            return new Reply(content, null);
        } catch (Exception error) {
            return new Reply("", "Could not reach the provider: " + error.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
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
