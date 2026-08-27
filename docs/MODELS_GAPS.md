# Models screen — three gaps

Recorded 2026-08-27. Nothing built yet.

## 1. No way to import a GGUF you already have

The catalogue is the only route to an on-device model. A `.gguf` sitting in
Downloads or on an SD card cannot be used at all.

Needs: an "Import a model" row on the On device tab, `ACTION_OPEN_DOCUMENT`
limited to `.gguf`, copy or hard-link into the models directory, read the GGUF
header for the real parameter count and context length, warn (do not block) if
the file is bigger than the device's RAM, and list it beside the downloaded
ones with a way to remove it. No SHA-256 gate for imports — there is nothing to
compare against; say so on the row.

## 2. The cloud tab is a bare form

Today it is four free-text fields. The user has to know the base URL, the exact
model id and the auth style for their provider.

Needs: pick the provider first — OpenAI, Anthropic, Groq, Mistral, OpenRouter,
Together, DeepSeek, or "Other (OpenAI-compatible)" — with the base URL filled
in and locked unless Other is chosen. Then the key, then the model. Keep the
form to one decision at a time.

## 3. No way to see a provider's current models

The model id is typed by hand, so it goes stale the moment a provider retires
one, and the failure only shows up mid-run.

Needs: a Check button that calls the provider's list endpoint (`GET /v1/models`
for the OpenAI-compatible ones, the provider's own for the rest) and shows what
came back as a pick list. Cache it, show when it was last refreshed, and mark
the saved model as missing if it stops appearing. Same treatment the Ollama tab
already gets — it discovers, the cloud tab does not.

## Also seen in the screenshots

- The memory stat clips: "880 MB of 3…". Shorten to "880 MB / 3.5 GB".
- On device opens straight into a download list when nothing is installed;
  there is no active-model card to anchor the screen.
