# Luna — full technical report

Branch `luna-rebuild`. Head `4b06311`, green in CI (run 33132703599).
Everything below describes the code as it stands in this repository, feature by
feature, with the file and method that implements it.

---

## 1. What the app is

Luna is a local utility agent for Android. The UI is Flutter/Dart, the core is
Java with a small C++ (JNI) layer for llama.cpp. There is one Activity, one
method channel and one event stream. Nothing is a web view except the headless
browser tool, and nothing runs on a server.

Four screens: **Chat**, **Files**, **Model Zoo**, **Settings**. A debug panel
floats above all four.

Three ways to get a model: download a checksummed GGUF and run it on the phone,
point at Ollama on your own machine, or add a cloud provider key. No account, no
telemetry, no analytics SDK.

Sizes:

| Layer | Lines |
| --- | --- |
| Java core (21 files) | ~7 100 |
| Dart UI (11 files) | ~6 200 |
| C++ JNI | 1 file |

APK: 29.09 MiB unpacked, budget 50 MiB, enforced on every build.

---

## 2. Build, test and release

### 2.1 Workflow `.github/workflows/android-apk.yml`

Runs on every push to `luna-rebuild` and `main`, and on demand. Steps in order:

1. `actions/checkout@v4`
2. `setup-java@v4`, Temurin 17
3. `subosito/flutter-action@v2`, Flutter **3.47.2**, cached
4. `scripts/bootstrap-llama-cpp.sh` — clones llama.cpp into
   `third_party/llama.cpp` pinned to commit `0e4a0362239713ea95a6864a17a8de4b0ad90d62`
5. `sdkmanager "ndk;26.1.10909125" "cmake;3.22.1"`
6. `flutter pub get`
7. `flutter analyze --no-fatal-infos --no-fatal-warnings`
8. `bash scripts/logic-tests.sh` — the JVM test suite (§2.3)
9. Restore the upload key from the `ANDROID_KEYSTORE_BASE64` secret into
   `android/luna-release.jks`; if the secret is absent the build falls back to
   the debug key and says so
10. `flutter build apk --release --target-platform android-arm64 --tree-shake-icons`
11. `apksigner verify --print-certs`, printed into the job summary
12. `rm -f android/luna-release.jks` with `if: always()` — the key never
    survives the job
13. `python3 scripts/apk-size.py <apk> 50` — the size gate
14. Upload artifact `luna-release-apk`

Whole run: about five minutes.

### 2.2 The size gate `scripts/apk-size.py`

Sums `file_size` (not `compress_size`) of every zip entry, because that is what
the phone actually stores. Prints a table of download size, unpacked size,
budget and the ten largest entries into the step summary, then exits non-zero if
unpacked is over the limit. The limit is a workflow env var,
`APK_UNPACKED_LIMIT_MIB: 50`.

What keeps it small: arm64 only, `--tree-shake-icons`, three subsetted fonts
(1.2 MB total), no image assets, the mascot drawn in a `CustomPainter`, and the
browser being the system WebView rather than a bundled engine.

### 2.3 Logic tests `scripts/logic-tests.sh`

No emulator and no Gradle. It finds `android.jar` under `ANDROID_HOME`, pulls
`org.json:json:20240303` (the platform jar's org.json throws `Stub!`), and
compiles **every** Java file in `ai/luna/app/` with `javac --release 11
-encoding UTF-8` against that jar plus ten hand-written stubs in
`tools/jvm-stubs/` (40 KB). Then it runs two suites:

- `MemoryRecoveryTest` — 17 checks. What survives a stop or a kill: transcript
  trimming (`AgentEngine.tail`), `resumable`, `dangling`, `lastUserInstruction`.
- `ProviderConfigTest` — 56 provider checks, plus 12 greeting checks
  (`SmallTalk`), 6 invented-address checks (`NetworkTargets`) and 10 gate checks
  (`ToolPolicy`).

It prints `ALL PASS`. It is also the fastest local check: ~3 s.

### 2.4 Signing

`keystore.yml` (manual dispatch only) generates the key. RSA-4096, 10 000 days,
alias `luna`, `CN=Luna, OU=Luna, O=GraphicMiles, L=Lagos, C=NG`. Secrets:
`ANDROID_KEYSTORE_BASE64`, `LUNA_KEYSTORE_PASSWORD`, `LUNA_KEY_PASSWORD`,
`LUNA_KEYSTORE_TRANSPORT`. `app/build.gradle` resolves signing config in the
order `key.properties` → environment variables → debug key.

### 2.5 Native build

`android/app/src/main/cpp/CMakeLists.txt` builds `libondevice_runtime.so`
against llama.cpp. Two constraints learned the hard way: `externalNativeBuild.
cmake.abiFilters "arm64-v8a"` is required (an `ndk { abiFilters }` block does
**not** constrain CMake, and armeabi-v7a fails on `vld1q_f16`), and llama.cpp
must not be passed `-DBUILD_SHARED_LIBS=OFF` or `-DGGML_STATIC=ON`.

---

## 3. Process architecture

### 3.1 `MainActivity`

The only Activity, a `FlutterActivity`. It:

- asks for `POST_NOTIFICATIONS` on API 33+ (downloads run behind a notification;
  without it the download still runs, it is just invisible)
- constructs `LunaBridge` in `configureFlutterEngine`
- forwards `ACTION_SEND` intents to `bridge.acceptShared(uri)` so a file shared
  from another app lands in the granted folder
- forwards `onActivityResult` (folder picker, GGUF import, bring-in, restore)
- disposes the bridge in `onDestroy`

### 3.2 `LunaBridge` — the seam

Method channel `ai.luna.app/core`, event channel `ai.luna.app/events`. Rules:

- nothing blocks the platform thread — anything touching storage, network or the
  model is handed to a two-thread `ExecutorService` and answers on the main
  looper via `reply(...)`
- calls that only read memory answer inline (`snapshot`, prefs setters, chat
  index)
- one `EventChannel.EventSink`; `push(JSONObject)` posts to the main looper and
  drops the event if nothing is listening
- every method call is written into the log by name (§13), never with its
  arguments

Owned objects: `Prefs`, `WorkspaceStore`, `ModelStore`, `OnDeviceRuntime`,
`CredentialVault`, `ErrorLog`, `HeadlessBrowser`, `AgentEngine`, plus a static
binding to `DownloadService`.

**Methods** (Dart → Java): `snapshot`, `pickFolder`, `setExecutionMode`,
`setEndpoint`, `setFailover`, `setActiveModel`, `sendMessage`, `answerQuestion`,
`setBudget`, `setWifiOnly`, `setBatteryGuard`, `setKeepWarm`, `setTheme`,
`setTextScale`, `setWalkthroughDone`, `errors`, `clearErrors`, `debugLog`,
`clearDebugLog`, `chats`, `searchChats`, `newChat`, `switchChat`, `deleteChat`,
`workspaceState`, `grants`, `useGrant`, `forgetGrant`, `pauseDownload`,
`resumeDownload`, `downloadState`, `importModel`, `bringInFile`,
`restoreSettings`, `resolveApproval`, `resumeRun`, `stopAgent`, `clearChat`,
`messages`, `cancelDownload`, `hasToken`, `resetAll`, `deviceCapacity`,
`listFolder`, `readFile`, `writeFile`, `createFile`, `createFolder`,
`renameFile`, `deleteFile`, `undo`, `catalog`, `downloadModel`, `deleteModel`,
`unloadModel`, `ollamaModels`, `addCloudProvider`, `removeCloudProvider`,
`updateCloudProvider`, `checkEndpoint`, `providerModels`, `probeModel`,
`exportChat`, `exportSettings`, `deleteImportedModel`, `importedModels`,
`storeToken`, `clearToken`.

**Events** (Java → Dart), one JSON string each: `run_started`, `thinking`,
`loading_model`, `token`, `step`, `approval`, `ask`, `prompt_cleared`,
`failover`, `speed`, `run_done`, `download`, `import`, `shared`, `log`.

`snapshot` is the single source of truth for screen state and is re-read
whenever anything consequential changes; events exist so the chat can move at
token speed without polling.

### 3.3 `LunaCore` (Dart)

A `ChangeNotifier` mirror of the Java state: mode, workspace, active model,
endpoint, failover, token, running, grants, download state, chats, messages,
catalog, cloud providers, device capacity, budgets, theme, text scale, pending
approval, pending question, trace steps, streaming text, tokens/second, elapsed.

`_applySnapshot` maps the map; `_onEvent` handles each event type. Two details
that matter:

- **`pendingPrompt`** is carried in the snapshot as well as in the `approval` /
  `ask` events, so a lost event cannot strand a run. The engine also re-emits
  the prompt every 2 s while it waits.
- The **token filter**: if the streamed text starts with `{`, `[` or a fence,
  the reply is treated as machine-shaped and is never typed into the thread —
  it belongs to the trace. This prevents half a brace being shown and then
  withdrawn.

`LunaCore.debug` is a separate `DebugLog` notifier (§13) so a log line does not
rebuild four screens.

---

## 4. Storage and secrets

### 4.1 `Prefs`

One `SharedPreferences` file. Keys and accessors:

| Area | Stored |
| --- | --- |
| Gate | `executionMode` (`ask`/`unattended`); `unattended()` |
| Budgets | `budgetSteps` 12, `budgetSeconds` 300, `budgetCloudCalls` 8 |
| Workspace | `workspaceUri`, `grants` (uri + name, most recent first) |
| Models | `activeModelId`, `importedModels[]`, `downloadState{}` |
| Cloud | `cloud_providers` (`KEY_CLOUD`) |
| Network | `endpoint` (Ollama), `failoverEnabled` |
| Downloads | `wifiOnly`, `batteryGuard` |
| Runtime | `keepWarm` |
| Look | `theme` (`system`/`light`/`dark`), `textScale` |
| Onboarding | `walkthroughDone` |
| Chats | `chats[]`, `activeChatId` |

`settleDownloadsAfterRestart()` runs in the constructor: anything recorded as
mid-flight when the process died is marked paused, because it is not mid-flight
now. `dropOldToolRules()` also runs there and deletes the retired
`KEY_TOOL_RULES` key — the per-tool permission model no longer exists.

`exportSettings()` / `importSettings()` back up everything except secrets.
`clearAll()` is the reset.

### 4.2 `CredentialVault`

AES-256-GCM through the Android keystore, alias `luna_credentials`. The key is
generated on first use and never leaves the secure element; only the IV and
ciphertext are written into a private `SharedPreferences` file. Named secrets:
`github` for the GitHub token, `cloud:<providerId>` for each API key.

`Prefs.cloudProviders(vault)` migrates any legacy row that still carries a
plaintext `apiKey` into the vault and strips the field.

### 4.3 `WorkspaceStore` — the granted folder

Storage Access Framework only. There is no path outside the granted tree, so
there is nothing to escape from.

- `pickFolderIntent()` → `ACTION_OPEN_DOCUMENT_TREE`; `persistGrant(uri)` takes
  a persistable read/write grant and remembers the name
- `rootState()` returns `none`, `ok` or `revoked` — a grant the system has taken
  back is detected and reported instead of failing mysteriously
- `list`, `readText`, `writeText`, `createFile`, `createFolder`, `rename`,
  `delete`, `search` (bounded recursion with a hit limit and line numbers)
- `isProtected(path)` refuses `.git`, keystores and similar
- **Backups and undo:** every write, rename and delete first copies the old
  bytes into an app-private backup directory with the operation recorded.
  `lastBackup()` reports the most recent one; `undo()` restores it. This is what
  makes "Deleted x. A backup was kept." a true sentence.
- `bringIn(uri, name)` copies a shared file into the folder
- Text reads are capped (2 MB, `maxFileBytes` in the snapshot)

### 4.4 Transcripts

`AgentEngine` writes one JSON file per chat in app storage. `chatIndex()` lists
them newest first with a title derived from the first user line; `searchChats`
greps them; `newChat`, `switchChat`, `deleteChat`, `exportChat` do what they
say. `loadTranscript()` calls `noteInterruptedTurn()` — if the last turn was a
user message with no reply, the chat says so and offers to carry on.

---

## 5. The agent loop

`AgentEngine.runTurn(userText)`, one single-thread executor, one turn at a time.

### 5.1 Setup

```
running=true, stopRequested=false, waitedMillis=0
conversationOnly = SmallTalk.matches(userText)
guards = new RunGuards(budgetSteps, budgetSeconds, budgetCloudCalls)
env = new Tools.Env(workspace, browser, vault, errors)
emit run_started
```

Model selection: active local entry, else active cloud provider. If neither, the
turn ends with `noModelReason()`. If a local model will not load and failover is
on, it emits `failover`, records an observation saying why, and continues on the
cloud provider; if failover is off it says the download is probably bad.

### 5.2 The loop, in order

1. `stopRequested` → "I stopped there." (state `stopped`)
2. elapsed > `budgetSeconds` → say so, mark the run cut short
3. `ask(local, cloud, guards)` — one model call, streaming
4. `parseToolCall(raw)` — pulls the first balanced `{…}` that names a tool,
   string- and escape-aware. Prose returns null. If a reply *starts* like JSON
   but does not parse, one repair observation is appended and the loop retries;
   a second failure is taken as prose so this cannot spin
5. `respond` → that text is the answer, turn ends
6. `conversationOnly` → one nudge asking for plain sentences, then a fixed
   friendly line. A greeting can never run a tool
7. unknown tool → observation listing the real ones
8. `ask_user` → blocks on `askUser(question)` (§5.4)
9. `path = args.path ?? args.url`
10. `ToolPolicy.needsFolder(tool)` and no folder → step `no_folder`, observation
    telling the model to ask for a folder in one sentence. The person is never
    asked to approve something that cannot work
11. `open_page` → `NetworkTargets.placeholderReason(url)`; a made-up host emits
    step `invented` and is turned back before an approval is spent
12. `guards.check(tool, args, mutating)` → `replayed` (the same read twice
    returns the remembered observation), or `blocked` with a reason; a reason
    containing "limit" ends the turn
13. `ToolPolicy.decide(tool, path, prefs)` → `ASK` emits step `held`, publishes
    the approval, and waits
14. step `running` → `runToolWithWatchdog(env, tool, args)` on a second executor
    with a **90 s** timeout; a timeout emits `unfinished` and tells the model to
    try a smaller piece
15. `guards.record(...)`, step `done`, observation appended

At the end: the answer is appended to the transcript (marked `stopped` if it was
cut short), the transcript is saved, and `run_done` carries `text`, `elapsedMs`
and `workMs` — elapsed minus the time a human spent deciding.

`finally`: running cleared, pending ids cleared, `browser.close()` (cookies and
history belong to the job), and the model unloaded unless "keep warm" is on.

### 5.3 Prompting

`systemPrompt(persona)` has two shapes.

- **Conversational** (`conversationOnly`): no tool list at all — there is
  nothing to be tempted by. Reply in one or two warm sentences, no JSON, do not
  claim to have done anything.
- **Working**: who Luna is, the granted folder name, the mode, then, *before*
  the tool list, the paragraph that says most messages need no tool. Then the
  one-object-per-reply JSON examples (file tools only when a folder exists),
  then: never invent an address; `open_page` then `read_page`; `ask_user` stops
  and waits; read before you write; one tool per reply; paths are relative;
  finish in plain sentences; never claim what a tool result does not show.

`buildPrompt(entry)` renders ChatML for local models; `cloudMessages()` renders
role messages for cloud, folding observations into `Tool result: …` user turns.
Both call the same `systemPrompt`.

`historyBudget(entry, system)` computes what actually fits: window − output
tokens − system − 128, times `CHARS_PER_TOKEN`, floor 1 200. `tail(...)` keeps
the newest messages that fit. Cloud history is capped at 12 000 characters. A
fixed 6 000 used to overflow a 2 048-token model and kill the turn instead of
forgetting the oldest part.

### 5.4 Waiting on a person

`requestApproval` builds `{id, tool, path, headline, consequence, preview}`
(preview clipped to 600 chars) and announces it **three ways**: the trace row
`held` carrying the whole question, the `approval` event, and the snapshot's
`pendingPrompt`. `waitForApproval()` polls a `BlockingQueue` in 500 ms hops for
up to 10 minutes; every 2 s it re-emits the prompt; `stopRequested` breaks out
immediately; the waiting time is banked into `waitedMillis`. `askUser` is the
same machinery with `ask`, and an unanswered question returns "" with an
observation telling the model to carry on or say what it needs.

Redundancy rather than a single event is deliberate: an event that goes missing
used to strand the run with no card and no way to answer.

### 5.5 Stop, resume, recovery

`stop()` sets the flag, cancels the native generation and disconnects the cloud
stream (`Cancellation`), so a Stop mid-stream closes the socket rather than
waiting for the last token. `resume()`/`canResume()` use `resumable(messages)`
and `dangling(messages)` — a turn that ended on a limit, a stop or a crash can
be picked up from the last user instruction. The chat shows that as one
"Carry on" affordance, never as a second Stop button.

### 5.6 `RunGuards`

Constructed per run from the budgets. `check(tool, args, mutating)` returns:

- `fromLedger(recorded)` when the same `signature(tool, args)` has already run —
  the remembered observation is replayed instead of the call
- `refuse(reason)` on the step limit, the time limit or the cloud-call cap
- `allow()` otherwise

`record(...)` writes the ledger, `checkCloudCall`/`recordCloudCall` meter the
paid calls, `describe()` produces the sentence used when a limit ends a turn.
Guards are monotonic: nothing resets them mid-run.

### 5.7 `SmallTalk`

Pure string logic, tested on the JVM. An exact list of ~50 openers and questions
about Luna herself. Anything containing `http`, `www.`, `/`, `.com`, `.md` or
`.txt` is a job. More than six words is a job. An opener plus pleasantries is
still chat, unless a verb of work appears (`read`, `write`, `open`, `list`,
`find`, `make`, `delete`, `summar`, `explain`, …). Getting this wrong one way
costs a step; the other way costs an answer, so it only fires on the
unmistakable cases.

---

## 6. The permission gate

One switch. `ToolPolicy.decide(tool, path, prefs)`:

```
prefs.unattended()            → RUN
tool == "ask_user"            → RUN
MUTATING.contains(tool)       → ASK
read_file on a sensitive path → ASK
otherwise                     → RUN
```

`Decision` is `{RUN, ASK}`. There are no per-tool rules anywhere in the app; the
old `toolRules` storage, bridge cases, Dart fields and Settings chips were all
deleted, and `Prefs` actively clears the old key.

- `READ_ONLY` = `list_files, read_file, search_code, respond`
- `MUTATING` = `write_file, create_file, create_folder, delete_file,
  rename_file, open_page, read_page, github_file, ask_user`
- `NEEDS_FOLDER` = the file tools
- `SENSITIVE` markers: `.env .pem .key .p12 .pfx .jks .keystore id_rsa
  id_ed25519 .ssh/ .netrc .git-credentials credential secret password passwd
  token apikey api_key wallet seed-phrase seedphrase shadow`

`describe(tool, path, content)` writes the headline on the approval card and
`consequence(tool, path)` the line under it, both in plain words.

---

## 7. Tools

`Tools.run(env, tool, args)` never throws: a failure is returned as a short
factual observation (`"Failed: …"`) and logged. Observations are clamped to
4 000 characters.

| Tool | What it does |
| --- | --- |
| `list_files` | Names, types and sizes in a folder under the grant |
| `read_file` | Text of one file, size-capped |
| `search_code` | Bounded recursive search, returns path + line number + line |
| `write_file` | Writes, after backing up the old bytes |
| `create_file` / `create_folder` | Creates under the grant |
| `delete_file` | Deletes, keeping a backup |
| `rename_file` | Renames in place |
| `open_page` | Loads a page in the headless browser |
| `read_page` | Returns the current page as readable text |
| `github_file` | Fetches one file from a repo via the API, using the vault token when present |
| `ask_user` | Stops and waits for a real answer |
| `respond` | Ends the turn with the given text |

### 7.1 `HeadlessBrowser`

The system WebView, created off-screen, never attached to a layout, destroyed
with its cookies when the job ends — so it costs nothing in the APK. Two rules
make it safe enough to hand to a model: `shouldInterceptRequest` refuses images,
fonts, media and anything that is not the document itself (a page cannot be used
to pull a payload), and every load has a timeout (default 20 s) so a page that
never settles fails instead of hanging the run. Text is extracted and cleaned by
`ReadableText.clean(raw, limit)` and capped at 40 000 characters.

### 7.2 `NetworkTargets`

`placeholderReason(url)` catches the twenty-odd hosts a model writes when it has
decided to browse but has nowhere to go (`example.com`, `yoursite.com`,
`test.com`, …) and returns a sentence instead of a page load. `check` and
`checkResolved` refuse private and loopback addresses, and `normalise` fixes a
missing scheme.

---

## 8. Models

### 8.1 On-device catalogue — `ModelStore`

Eight pinned GGUF builds, every one at an immutable revision URL with a SHA-256:

| id | Name | Params | Size | Ctx | Max out | Min RAM |
| --- | --- | --- | --- | --- | --- | --- |
| `smollm-135m-q3` | SmolLM 135M | 135M | 93.5 MB | 2048 | 128 | 2 GB |
| `smollm2-360m-q3` | SmolLM2 360M | 360M | 234.7 MB | 4096 | 384 | 2 GB |
| `qwen2.5-0.5b-q4` | Qwen2.5 0.5B | 0.5B | 491.4 MB | 4096 | 512 | 3 GB |
| `qwen2.5-coder-0.5b-q4` | Qwen2.5-Coder 0.5B | 0.5B | 491.4 MB | 4096 | 384 | 3 GB |
| `smollm2-1.7b-q4` | SmolLM2 1.7B | 1.7B | 1.06 GB | 4096 | 512 | 4 GB |
| `qwen2.5-1.5b-q4` | Qwen2.5 1.5B | 1.5B | 1.12 GB | 4096 | 512 | 4 GB |
| `qwen2.5-coder-1.5b-q4` | Qwen2.5-Coder 1.5B | 1.5B | 1.12 GB | 4096 | 512 | 4 GB |
| `qwen2.5-coder-3b-q3` | Qwen2.5-Coder 3B | 3B | 1.72 GB | 4096 | 640 | 6 GB |

Each entry carries the model's own system persona, which is appended to Luna's
prompt. Downloads are resumable (HTTP range), verified by SHA-256 on completion,
and a file that fails the check is **deleted, not kept**. `describeGguf(file)`
reads the header so an imported file can report its own parameter count;
`shortHash` renders the checksum for the UI.

### 8.2 `DownloadService`

A foreground `Service` so a download outlives the screen. Progress is reported
to the bound listener (the bridge, when the app is open) and to a notification
with a percentage. `heldBack()` explains why a download is waiting:

- **Wi-Fi only** is on and the phone is on mobile data → paused with that reason
- **Battery guard** is on and the battery is under 15 % and not charging →
  paused with that reason

`pause`, `resume` and `cancel` are real intents into the service. State is
mirrored into `Prefs.downloadState` so the Model Zoo can draw a truthful bar
after a restart.

### 8.3 `OnDeviceRuntime` + `cpp/OnDeviceRuntime.cpp`

One model in memory at a time. Native methods: `nativeLoad(path)`,
`nativeUnload()`, `nativeGenerate(runtime, promptUtf8, maxTokens, contextTokens,
threads, requestId)`, `nativeCancel(requestId)`. Statuses: `COMPLETE`,
`CANCELLED`, `MODEL_NOT_LOADED`, `PROMPT_TOO_LONG`.

The C++ side loads with `llama_model_load_from_file`, tokenizes with the model's
own vocab, creates a context sized to `contextTokens`, and decodes in a loop.
Three details worth naming:

- **UTF-8 safety.** Tokens are byte pieces; `complete_utf8_prefix` only emits a
  prefix that ends on a character boundary, so a multi-byte glyph is never split
  across two chunks and shown as garbage.
- **Cancellation.** `abort_callback` and a per-request id let a Stop interrupt
  decoding between tokens; the Java side also polls `isCancellationRequested`.
- **Metrics.** The result carries prompt tokens, output tokens, prefill micros
  and generation micros; `tokensPerSecond()` is derived from those and is what
  the `speed` event and the Models screen show.

`DeviceCapacity.suggestedThreads()` gives llama.cpp `min(6, cores − 1)`, leaving
a core for the UI. `DeviceCapacity.read()` reports total and available RAM,
total and available storage and the core count — used to warn before a model
that will not fit.

### 8.4 Ollama

`Prefs.endpoint` holds the address. `CloudProvider.ollamaModels(endpoint)` lists
what that machine serves. `EndpointPolicy` allows plain `http` **only** for a
private or loopback host — your own machine on your own network — and requires
`https` for anything on the internet, with no userinfo, query or fragment in the
base. Active id form: `ollama:<name>`.

### 8.5 Cloud providers — `CloudProvider`

A provider row is `{id, label, baseUrl, model, kind, authStyle, authName,
headers, checkedAt}` in `Prefs.KEY_CLOUD`; the key lives in the vault at
`cloud:<id>`; the active id is `cloud:<id>`.

**Three wire shapes**, all built by one function, `wireFor(config, messages,
maxTokens, stream)`:

| kind | Endpoint | Body / notes | Stream frame |
| --- | --- | --- | --- |
| `openai` | `POST {base}/chat/completions` | messages as-is | `choices[0].delta.content` |
| `anthropic` | `POST {base}/messages` | system hoisted out, same-role turns merged, `max_tokens` forced, `anthropic-version: 2023-06-01` | `content_block_delta.delta.text` |
| `gemini` | `POST {base}/models/{model}:streamGenerateContent?alt=sse` | roles `user`/`model`, `systemInstruction`, probe uses `:generateContent` | `candidates[0].content.parts[0].text` |

**Four auth styles**: `bearer` (`Authorization: Bearer …`), `header` (any header
name), `query` (any query parameter — how Gemini takes a key), `none`.
`defaultAuthStyle(kind)` and `defaultAuthName(kind, style)` fill sensible
defaults, and every one of those fields stays editable. Extra headers are a free
JSON map.

`readStream` handles both worlds: server-sent events when the provider streams,
and one whole JSON document when it ignores the flag — the difference is
invisible above that method. Cancellation is checked between lines, and whatever
arrived before a Stop is kept.

`listModels(config)` reads the provider's own catalogue (for Gemini, only
entries whose `supportedGenerationMethods` contains `generateContent`, with the
`models/` prefix stripped) and orders it through `ModelCatalog`.

The owner prefix is part of the id. Only Gemini reports `models/gemini-2.0-flash`
and wants the tail in the path; every OpenAI-shaped provider means the whole
string, so `openai/gpt-oss-120b` and `groq/compound-mini` are sent as they are.
Stripping them was a 404 that read like a bug in Luna.

`ModelCatalog.looksLikeChatModel(id)` filters on substrings — whisper, tts,
speech, voice, audio, transcribe, realtime, orpheus, playai, canary, kokoro,
bark, musicgen, embed, rerank, moderation, guard, prompt-guard, image, dall-e,
imagen, veo, stable-diffusion, sd3, flux, sora, clip, ocr and more.
`ordered(ids)` puts chat models first alphabetically and **keeps everything
else** below: nothing is hidden, the default is just sane.

`explain(config, code, payload)` turns 400/401/403/404/429 and the provider's
error body into a sentence a person can act on. 404 says the model is not
available on this key and to refresh the list.

**No model id is ever hardcoded.** That rule exists because Groq retiring
`llama-3.3-70b-versatile` turned every install with it baked in into a 404 that
read like an app bug.

### 8.6 Realtime availability — the probe

Being listed is not the same as being usable: a provider will list models a key
has no entitlement for, and the first sign is a 404 in the middle of a job.

`CloudProvider.probe(config)` sends one real, non-streamed request — `"Hi"`,
`max_tokens: 1`, 30 s read timeout — built by the same `wireFor`, so the probe
and the run can never disagree. It returns `null` when the model answers, or the
reason it cannot be used.

Where the probe is enforced:

- **`_pickModel`** (Model Zoo): tapping a listed model probes it first
  (`subtitle: 'Checking it works…'`) and only writes it on success
- **the provider editor's list**: "Check models" fetches the list and *fills
  nothing in*; each row is picked by tapping, which probes
- **Save**: however the model got into the field, saving probes it and refuses
  with the provider's own sentence if it fails
- **mid-run**: when a reply says the model is not available on this key,
  `AgentEngine` calls `Prefs.clearCloudModel(id)` so a dead model does not stay
  selected, and tells the user to reopen the Model Zoo

---

## 9. Chat screen

`lib/screens/chat_screen.dart` (858 lines), with `lib/widgets/agent_response.dart`.

- The thread starts at the top and reads downward. The user speaks in a filled
  bubble that fits its contents (max width 84 %, radius 15/15/15/5). Luna's side
  is plain on the paper: no card, no avatar gutter. Agent indent 1.5 rem, column
  width 80 %, gap between a user message and the reply 1.5 rem.
- **`AgentTrace`** is the collapsible thread of steps. A row exists only because
  a step changed state. Vocabulary: `running`, `held`, `done`, `replayed`,
  `blocked`, `no_folder`, `invented`, `declined`, `unfinished`, `denied`. The
  header carries the mark, one label and the elapsed time; the chevron rotates
  0 → −0.25 turns over 300 ms; the running row auto-previews its own detail.
  Nothing is shown twice — there is no status row that repeats the header.
- **`ShimmerLabel`** for a live label (stops .35/.5/.65, right to left) and
  **`PixelLoader`** for the waiting mark.
- **`StreamedAnswer`** types the reply word by word, animating only the last
  three words, deliberately slower than the model produces them.
- **Approval card**: one filled card carrying the headline, the consequence, a
  preview of what would be written, and two buttons. It is drawn from the
  `approval` event *or* the `held` trace row *or* the snapshot, whichever
  arrives.
- **Question card**: the same, with a text field, for `ask_user`.
- **Carry on**: shown when the last turn was cut short; calls `resumeRun`.
- **Chats sheet**: search, switch, delete, export.
- **Attach** copies a file into the granted folder and names it in the message.
- **Composer** — the only Stop in the app: paperclip (disabled while running),
  the field (hint `Add to the job…` while running, otherwise `Tell Luna what to
  do…`), and one 34 px circle that is `arrow-up` → send when idle and `stop` →
  `core.stop` while running.

---

## 10. Files screen

`lib/screens/files_screen.dart` (504 lines). Breadcrumbs, a plain list with an
icon per file type, and per-file actions in a sheet: open, view, rename, delete,
and "ask Luna about this" which jumps to Chat with the path in the prompt.
Create file and create folder. An undo note appears whenever a backup exists.
Three states are handled explicitly: no folder granted (choose one), grant
revoked by the system (re-pick), and an error reading the tree (shown as a
note, not a crash).

---

## 11. Model Zoo

`lib/screens/models_screen.dart` (1 117 lines). Three sections.

- **On this phone** — the catalogue, each row showing size, context and whether
  it fits this device; download with a live progress bar, pause, resume, cancel;
  a checksum note after verification; import your own GGUF; delete. The active
  model gets a hero card with its stats and the last measured tokens/second.
- **Your computer** — the Ollama address, a probe button that lists what that
  machine serves, and selection from that list.
- **Cloud** — the provider list, plus a preset picker (OpenAI, Anthropic,
  Gemini, Groq, OpenRouter, Together, "Something else") which only pre-fills the
  shape and address. The editor exposes label, base URL, key (obscured, "saved —
  type to replace"), wire shape, auth style, auth header/parameter name, extra
  headers and the model, with "Check models" and Save both probing (§8.6).
  `_say` posts one plain line; `_plainError` strips
  `PlatformException(code, …)` down to the sentence — written with
  `startsWith`/`indexOf`/`substring` rather than a regex, after a doubled
  backslash through a patch script produced a regex that compiled and never
  matched.

---

## 12. Settings

`lib/screens/settings_screen.dart` (588 lines), in sections:

- **Before anything permanent** — the single "Ask me first" switch, plus one
  note explaining what it means either way. Off: Luna may act unsupervised. On:
  she asks before altering files, before deleting, and before reading sensitive
  material.
- **Limits on one job** — steppers for steps, seconds and cloud calls.
- **Your computer** — the Ollama address.
- **When the on-device model cannot cope** — the failover switch.
- **GitHub** — add or remove the token (kept in the keystore).
- **Downloads** — Wi-Fi only, pause under 15 %.
- **The model in memory** — keep the model warm.
- **Folders** — the current grant, and previous grants you can switch back to or
  forget.
- **Look and feel** — theme (system/light/dark) and text size.
- **Stored on this device** — chat size and clear, undo backups, the error log.
- **Backup** — export settings into the granted folder (keys excluded) and
  restore, then a reset that clears everything.

---

## 13. The debug panel

Added last; green at `4b06311`.

### 13.1 What is logged

`ErrorLog` is now two tiers in one class. Failures are written to disk (ring of
50, `errors.json`, survives a restart, this is what Settings shows). Everything
else lives in memory only (ring of 400) so a busy run does not write a megabyte
of noise to storage. Levels are `error`, `warn`, `info`; each line is
`{n, at, level, side, where, what}` with `what` clipped to 600 characters and
newlines flattened.

Sources:

| `where` | Written by |
| --- | --- |
| `call` | `LunaBridge.onMethodCall` — the method **name only**, never arguments |
| `http` | `CloudProvider.open` (verb, URL, model, shape) and the response code; the body of a failure, trimmed |
| `probe` | Availability probes: pass with the code, or fail with the code and body |
| `engine` | Every agent event except `token` and heartbeats: type, tool, state, path, reason |
| a tool name | `Tools.run` failures |
| `flutter`, `dart`, `events`, a method name | The Dart side: widget errors, uncaught async errors, event-stream errors, failed channel calls |

Static call sites (the provider layer is all static methods) reach the log
through `ErrorLog.tap/tapNote/tapFail`. `ErrorLog.safeUrl` truncates a `?key=`
query before it is ever written — a debug panel that leaks the key is a debug
panel that ends up in a screenshot.

### 13.2 How it reaches the screen

`ErrorLog.listen(...)` hands every new line to `LunaBridge`, which pushes it as
a `log` event. `LunaCore._onEvent` routes `log` straight into `DebugLog` and
returns — no screen state depends on it. `DebugLog` is its own `ChangeNotifier`
holding 500 lines, because a rebuild of four screens per log line would make the
panel the slowest thing in the app. On startup `pullLog()` seeds it with
whatever Java already had (`debugLog` method), merged newest-first.

### 13.3 The panel itself

`lib/widgets/debug_panel.dart`. `DebugOverlay` wraps the whole Shell, so it is
present on all four screens.

- **Closed:** a 27 px tab hugging the right edge, a third of the way up —
  clear of the composer (where the only Stop lives) and of every screen header.
  It fills with ink and shows a count when something has failed.
- **Open:** a sheet over the bottom 52 % of the screen, so the app is still
  visible behind it. Header: "Debug log", the line count, then three 34 px
  buttons — **copy**, **clear**, **close**.
- **Filter:** Everything / Errors.
- **Lines:** newest first, monospace, `hh:mm:ss.mmm` · source · text. Failures
  carry weight and a fill, never colour.
- **Copy** puts the whole visible log on the clipboard with a header line, a
  count, and one fixed-column line per entry, oldest last; the icon becomes a
  tick and the count reads "Copied" for 1.4 s. **Long-press** any single line to
  copy just that line.
- **Clear** clears memory and the file on both sides.

---

## 14. Design system

`lib/theme.dart` and `lib/widgets/common.dart`, ported from
`docs/design/luna-screens.html`.

- Monochrome. Four ink values, four surfaces, five values inside the one filled
  surface per screen. The mascot is the only object allowed colour.
- No box shadows, no outlines on surfaces, no sharp edges. Radii: 44 frame, 28
  sheet, 24 card, 22 group, 20 step, 18 note, 15 tile, 12 tile-small, pill.
- Type: Manrope for display, Inter for text, JetBrains Mono for code and the log
  — all subsetted, 1.2 MB together, loaded from `assets/fonts/`.
- State reads through fill and weight, never hue.
- Icons are FontAwesome (`FaIconData` — v11 renamed the type and it is *not* an
  `IconData` subtype, so every parameter is declared `FaIconData`). No emoji, no
  inline SVG. The mascot is the sole exception, drawn in a `CustomPainter`.
- Buttons fit their content; cards are compact.
- **Dark mode** swaps mutable statics in `LunaTheme.apply({dark})` and repaints
  the status and navigation bars, so there are no white strips framing a black
  app. It follows the system unless the setting overrides it.

Shared widgets: `Mark`, `Glyph`, `IconButtonSoft`, `ScreenTop`, `SectionLabel`,
`Group`, `PlainList`, `LunaRow`, `PillButton`, `Segmented`, `LunaSwitch`,
`Note`, `EmptyState`, `showLunaSheet`, `LunaField`, `ProgressBar`.

---

## 15. Privacy and safety, in one place

- No account, no telemetry, no analytics, no crash reporter.
- Files: nothing outside the SAF grant exists to Luna. Writes, renames and
  deletes are backed up and undoable.
- Secrets: keystore-backed AES-GCM. Keys are never logged, never exported, never
  put in a prompt. Gemini's query key is stripped before any URL is logged.
- Network: HTTPS enforced except for private hosts; no userinfo or query in a
  base URL; invented addresses refused; the browser fetches documents only and
  forgets everything at the end of the job.
- The gate: with "Ask me first" on, nothing is altered or deleted and nothing
  sensitive is read without a tap.
- Limits: steps, seconds and cloud calls per job, a repeat-call ledger and a
  90 s watchdog per tool.

---

## 16. Known remainder

- The composer hint "Add to the job…" promises queueing that `_send` does not do
  (it returns early while running) — wire it or reword it.
- Model Zoo has one duplicate import.
- Long tool observations can truncate mid-word.
- `DownloadService` does not auto-retry a dropped connection.
- `OnDeviceRuntime.load` is not interruptible once it starts.
- `keystore.yml` should go back to `workflow_dispatch` only.
- Docs not yet written up: the probe in `docs/PROVIDERS.md`, and the 01:29 and
  02:05 device runs in `docs/BUGS_FROM_DEVICE.md`.

Out of scope by decision: scheduled jobs, biometric lock.

---

## 17. File map

**Java** — `AgentEngine` 1391, `CloudProvider` 854, `LunaBridge` 820,
`WorkspaceStore` 685, `Prefs` 573, `ModelStore` 375, `DownloadService` 290,
`ErrorLog` 235, `HeadlessBrowser` 236, `Tools` 221, `OnDeviceRuntime` 199,
`NetworkTargets` 171, `ToolPolicy` 156, `RunGuards` 133, `ModelCatalog` 109,
`EndpointPolicy` 108, `SmallTalk` 91, `CredentialVault` 84, `MainActivity` 75,
`ReadableText` 72, `DeviceCapacity` 58.

**Dart** — `models_screen` 1117, `luna_core` 984, `chat_screen` 858,
`common` 699, `settings_screen` 588, `agent_response` 516, `files_screen` 504,
`main` 335, `debug_panel` 254, `providers` 208, `theme` 179, `debug_log` 140.

**Other** — `cpp/OnDeviceRuntime.cpp`, `cpp/CMakeLists.txt`,
`scripts/{logic-tests.sh, apk-size.py, bootstrap-llama-cpp.sh}`,
`tools/jvm-stubs/`, `tools/tests/`, `.github/workflows/{android-apk,keystore}.yml`,
`docs/{AUDIT_INVENTORY, CODE_AUDIT, PROVIDERS, BUGS_FROM_DEVICE, MODELS_GAPS,
BATCHES, REUSE_FROM_MR_NOBODY}.md`, `docs/design/{luna-screens, luna-fixes,
pipeline-mrnobody}.html`.
