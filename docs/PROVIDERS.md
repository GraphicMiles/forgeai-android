# Connecting a provider

Luna speaks three request shapes. Everything else about a connection is yours
to set.

| Shape | Who speaks it | Where the key goes |
| --- | --- | --- |
| `openai` | OpenAI, Groq, OpenRouter, Together, DeepSeek, Mistral, xAI, Ollama, LM Studio, llama.cpp's server | `Authorization: Bearer …` |
| `anthropic` | Claude | `x-api-key`, plus `anthropic-version` |
| `gemini` | Google AI Studio | `?key=` in the address |

## What the sheet asks

Pick who you are connecting to and the shape and address are filled in for you.
Nothing that gets filled in is locked:

- **Name** — what the row is called.
- **Base address** — must be `https`, unless the host is on your own network,
  where plain `http` is allowed because nothing leaves the building.
- **Key** — goes straight to the device keystore, never to a file.
- **Model** — never typed from memory. Tap *Check models* and Luna asks the
  provider what your key can actually use, chat models first.

Under **Advanced**:

- **Request shape** — the three above.
- **How the key is sent** — bearer token, a header you name, a query parameter
  you name, or no key at all.
- **Extra headers** — one per line, written as `Name: value`. This is what makes
  an endpoint nobody has heard of yet work today.

## Why models are asked for rather than listed

A model id is the first thing a provider retires. A list baked into the app goes
stale and the 404 that follows reads like a bug in Luna, so the app ships no
model names at all.

## Failures

Someone else's HTTP code is turned into a sentence about what to do: a rejected
key, a model this key cannot see, a conversation that is too long, or a provider
having trouble at its end.

The pure parts of all of this — address rules, model ordering, shape defaults,
message merging and the failure sentences — run on a plain JVM in
`tools/tests/ai/luna/app/ProviderConfigTest.java`.
