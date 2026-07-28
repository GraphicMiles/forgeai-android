# ForgeAI

A local-first coding assistant for Android, with a browser/Ollama development preview. Android production inference runs GGUF models directly through JNI and a pinned llama.cpp revision; it does not require Ollama or a local HTTP model server.

## Current capabilities

### Android

- Direct CPU llama.cpp inference from app-private GGUF runtime copies.
- Incremental JNI-to-Capacitor token streaming.
- Request-scoped cancellation, including llama.cpp CPU abort during prompt prefill.
- One loaded model and one active generation at a time.
- Stable native generation error codes and exactly-once terminal events.
- Immutable, checksummed official model catalog.
- SAF folder picker for a user-approved project workspace.
- Contained file CRUD through one workspace provider.
- Sensitive-file filtering, binary/size limits, RAG disclosure, and recoverable writes.
- Undo for writes, renames, and deletes.
- Device GGUF import with header validation and a recorded SHA-256.
- Local load/prefill/generation benchmark information.

### Browser development preview

- Ollama chat/pull/delete integration.
- A small virtual workspace persisted in browser storage.
- The same relative-path policy and recoverable workspace operations.

## Architecture

```text
Android
React/Capacitor UI
  → ModelProvider / WorkspaceProvider / ToolRegistry
  → OnDeviceRuntime + WorkspaceStorage Capacitor plugins
  → JNI
  → pinned llama.cpp
  → GGUF

Browser preview
React UI
  → OllamaProvider
  → user-configured Ollama endpoint
```

## Official Android models

The Model Zoo is generated from `src/models/catalog.js`. Every listed GGUF has:

- an immutable Hugging Face revision URL;
- exact file size;
- trusted SHA-256;
- source, revision, license, and quantization metadata;
- a runtime prompt/context profile.

User-imported GGUF files are labelled `hash-recorded`, not publisher-verified, unless they match a trusted manifest.

## Development

```bash
npm ci
npm run lint
npm test
npm run catalog:validate-release
npm run build
```

Browser preview:

```bash
npm run dev
```

## Android build

Requirements:

- Java 21+
- Android SDK platform 35
- Android Build Tools 35.0.0
- NDK 26.1.10909125
- CMake 3.22.1

```bash
npm run android:build
```

The debug APK is written to:

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions also builds and uploads `forgeai-debug-apk` on every push to `main` and on manual dispatch.

## Safety boundaries

- Android project access requires a user-selected SAF document tree.
- App tools accept relative workspace paths only.
- Writes, renames, deletes, and future execution require approval.
- Text reads/writes are capped at 2 MiB; RAG reads use a lower per-file cap.
- Common secret files and generated/dependency directories are blocked by default.
- Android shell execution is not implemented.
- Browser Ollama endpoints may be remote; the app discloses workspace files before adding them to a prompt.

## Remaining acceptance work

Automated tests and APK compilation do not replace physical-device acceptance. Before release, test on real ARM64 phones:

1. checksummed model download and import;
2. airplane-mode token streaming;
3. cancellation during prefill and generation;
4. model switch/unload/delete/restart;
5. workspace write/rename/delete undo across restart;
6. SAF behavior with multiple Android document providers;
7. benchmark and memory behavior under sustained generation.

Phase 4 will add structured model actions, unified diffs, reviewable patch application, code retrieval, history, and desktop-only approved validation commands. Phase 5 will add the Tauri desktop product and release hardening.

## License

MIT. See `LICENSE`. Model licenses are shown per catalog entry; the current official entries are Apache-2.0.
