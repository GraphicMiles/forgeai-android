# Code audit — every file, rated

Read line by line on 27 August 2026, against the build on `luna-rebuild`. A
rating is what I would sign my name to, not a compliment. Anything I found is
listed whether or not it was fixed in this pass, and anything still open says so
in plain words.

The scale: **A** = I cannot find a defect. **B** = correct, with a rough edge
that costs nothing today. **C** = works, but a real hole is still in it.

---

## Java — the core

| File | Lines | Rating | What was wrong, and what happened to it |
| --- | ---: | :---: | --- |
| `AgentEngine.java` | 1,079 | A− | Imported models were invisible to the loop (catalogue-only lookup) — **fixed**. A stuck native load wedged the engine and silently ate every later message — **fixed**, a turn now ends exactly once and Stop ends it. History was trimmed to a flat 6,000 characters regardless of the model's window, which overflowed 2k-context models — **fixed**, the budget is derived from the window. Ollama had no route into the loop — **fixed**. Failover was a switch that did nothing — **fixed**, a model that will not load now falls to the configured provider and says so in the trace. Remaining edge: a turn queued behind a wedged native load still waits for that load to return; nothing can pre-empt llama.cpp mid-load. |
| `LunaBridge.java` | 679 | A− | Every method returns on the right thread; the file-picker `Result` is single-slot. Added `resumeRun`, `canResume`, and a startup call that settles downloads left mid-flight. Remaining edge: if two picker intents were somehow in flight, the second would replace the first's pending result. Not reachable from the UI today. |
| `WorkspaceStore.java` | 686 | A | `..` rejected, deny-list applied on read, write, create, rename, delete and bring-in, 2 MiB ceiling, search skips protected and oversized files, deletes keep a backup. `createFolder` was the one path missing the deny-list check — **fixed**. |
| `Prefs.java` | 553 | A | Cloud keys migrate out of plain preferences into the keystore on read. Added `settleDownloadsAfterRestart()` so a download recorded as running at boot becomes paused rather than a bar that never moves. |
| `ModelStore.java` | 383 | A− | Resumable `.part` download, checksum verified, bad file deleted. Added `imported()` so a file you brought yourself is a first-class entry. Remaining edge: the eight catalogue sizes are hard-coded, so if a publisher re-uploads a file the size check fails and the model reads as "not downloaded" — the message now says exactly that instead of "pick a model". |
| `DownloadService.java` | 290 | B | Foreground service, notification, pause, cancel, Wi-Fi and battery guards all work. **Open:** a download parked by the Wi-Fi or battery guard says "waiting" and stays there — nothing retries it when Wi-Fi comes back. You have to press Resume. Honest, but a scheduler would be better. |
| `CloudProvider.java` | 278 | A− | Streaming SSE, single-document fallback for providers that ignore the flag, model listing from the provider. Stop could not interrupt a cloud read — **fixed**, cancellation is checked between frames and partial text is kept. Dead non-streaming `chat()` — **deleted**. Remaining edge: `max_tokens` is rejected by a few newest hosted models that want `max_completion_tokens`; the error is surfaced verbatim rather than retried. |
| `HeadlessBrowser.java` | 232 | A− | Off-screen system WebView, no APK cost, images and media refused before they leave, per-job cookie wipe. The field was not `volatile` across the worker and main threads — **fixed**. A timed-out page kept loading in the background — **fixed**, it is stopped and the stale URL cleared. Remaining edge: no `click`/`type`; it reads pages, it does not drive them. |
| `NetworkTargets.java` | 122 | A | Scheme, host and the private IPv4 ranges were covered. IPv6 unique-local and link-local literals were not, and a public name pointed at a private address passed — **both fixed**, with the DNS check on the page the model asked for only, not on every sub-resource. |
| `OnDeviceRuntime.java` | 199 | B+ | Load, generate, cancel, token callback, status codes that map to sentences. **Open:** `nativeLoad` is not interruptible, which is the root of the wedge that Stop now papers over. Fixing it properly means a cancellation hook inside the JNI layer. |
| `Tools.java` | 221 | A− | Every tool returns a sentence, never an exception. The GitHub token is read for one call. Remaining edge: `read_page` does not repeat the page title, so a model that skipped `open_page`'s reply has slightly less to go on. |
| `ToolPolicy.java` | 107 | A | Read is free, everything else is held, unknown tools are treated as mutating. The per-tool rule beats the global switch, and `never` really refuses. |
| `RunGuards.java` | 133 | A | Steps, wall clock and cloud calls, all monotonic. The ledger replays a repeated read instead of running it twice. |
| `ErrorLog.java` | 110 | A | Bounded, persisted, readable. |
| `CredentialVault.java` | 84 | A | AES/GCM in the AndroidKeyStore, one alias, no key ever written to a file or into a prompt. |
| `ReadableText.java` | 71 | A | Collapses furniture and says when it truncated. Only three unicode escapes were unescaped — **fixed** with a general one. |
| `DeviceCapacity.java` | 58 | A | Reports what the phone has, rounds nothing up. |
| `MainActivity.java` | 75 | A− | One activity, share intake, notification permission. Remaining edge: a file shared in during a cold start can emit its event before Dart is listening; the folder still gets the file, the toast is what is lost. |

## Dart — the interface

| File | Lines | Rating | What was wrong, and what happened to it |
| --- | ---: | :---: | --- |
| `core/luna_core.dart` | 800 | A− | One channel surface, one event switch, no business logic. Added carry-on detection, work-time accounting that excludes waiting on you, Ollama naming, failover step. Remaining edge: `messages` is re-read from Java on every `run_done` — correct, but it is a full read of the transcript each turn. |
| `screens/chat_screen.dart` | 790 | A− | Real attachments, approval card, ask-user card, chat switcher, trace. The chat-search controller leaked on every open — **fixed**. |
| `screens/models_screen.dart` | 830 | A− | Catalogue, imports, live downloads, providers, checksums. Ollama used to be registered as a fake cloud provider with the literal key "ollama", duplicated on every press — **fixed**, it is `ollama:<model>` with no keystore entry. Four key-sheet controllers leaked — **fixed**. A force-unwrap on the checksum string — **fixed**. |
| `screens/settings_screen.dart` | 640 | A− | Per-tool rules, budgets, guards, theme, text size, error log, backup. The token controller leaked — **fixed**. The endpoint field went stale when the address was changed in the Model Zoo — **fixed**, both screens now re-read it. |
| `screens/files_screen.dart` | 520 | A | Breadcrumbs, viewer, revoked-permission state, bring-in, undo. Two sheet controllers leaked — **fixed**. |
| `widgets/common.dart` | 724 | A | Every control has a Semantics label; the row announces its subtitle as a hint. `Glyph` took a colour default that could not be `const` under the new palette — **fixed** by making it nullable. |
| `widgets/agent_response.dart` | 526 | A− | Shimmer that stops dead when the work stops, a pixel loader, a collapsible trace, word-by-word answers. Remaining edge: settled words are plain text, but a very long answer still rebuilds a large `Wrap`. |
| `theme.dart` | 224 | A | One palette, swapped in one call. Text helpers take a nullable colour so they follow the swap. |
| `main.dart` | 400 | A | Theme follows the phone, text scale is applied app-wide, the first run explains itself. |

---

## End-to-end, feature by feature

Verified means I traced the whole path in code and, where it was possible off a
phone, executed it. Nothing below claims a device test — none of it has run on
your handset yet except what you tried yourself.

| Feature | State | Path |
| --- | --- | --- |
| On-device model | Works | `catalog → download → checksum → load → generate → stream` |
| Imported `.gguf` | **Fixed this pass** | `import → copy into models dir → imported entry → load → generate` |
| Ollama on your computer | **Fixed this pass** | `endpoint → /api/tags probe → Use → ollama:<model> → /v1/chat/completions streaming` |
| Cloud key | Works | `key in keystore → /v1/chat/completions streaming → cancellable` |
| Failover | **Fixed this pass** | on-device load fails → configured provider answers, and the trace says so |
| Headless browser | Works | `open_page → URL guard → off-screen WebView → read_page → cleaned text` |
| GitHub file | Works | token read from keystore for one call, 401/403/404 each get their own sentence |
| Folder work | Works | SAF grant, list, read, write, create, rename, delete with backup, undo |
| Approvals | Works | per-tool rule, global switch, fail-closed |
| Budgets and replay | Works | steps, seconds, cloud calls, repeated read replayed |
| Downloads | Works, one gap | pause, resume on the byte, survives the app; a Wi-Fi-parked download waits for you to press Resume |
| Stop and carry on | Works | tested on the JVM, 17 checks |
| Memory | Works | per chat, on disk, trimmed to the model's real window |

## What is still open, in order of how much it matters

1. `nativeLoad` cannot be interrupted. Stop ends the turn, but the native load
   keeps going until it finishes.
2. A download parked by the Wi-Fi or battery guard needs a manual Resume.
3. The browser reads pages; it cannot click or type.
4. Catalogue entries are pinned by exact size, so a re-uploaded file reads as
   not downloaded.
5. None of it has been run on a phone by me.
