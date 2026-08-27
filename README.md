# Luna

**A local utility agent that lives on your Android phone.**

Luna is not a chat window with tools bolted on. The device is her workplace: she reads and
writes the folders you grant her, runs commands in a private sandbox shell, works with Git
repositories, and looks things up on the web — using a model you choose, on-device or remote.

---

## The four pillars

Everything in this repository serves one of four things. Anything that did not was removed.

### 1. The agent
A single agentic loop (`src/agent/agenticLoop.js`) that plans, calls one tool at a time,
verifies its own writes, and finishes by responding.

- **Tools** — `src/agent/toolSchemas.js`: read/write files, create files and folders, delete,
  list, search code, read a single symbol, run terminal commands, Git (`status`, `diff`, `log`,
  `clone`, `commit`, `push`), web search and page fetch, ask the user, respond.
- **Approval policy** — `src/agent/toolPolicy.js`: read-only tools always run; mutating tools
  need approval unless the user has switched to unattended mode. A declined action is never retried.
- **Execution mode** — `src/agent/executionMode.js`: `ask` (default) or `auto`.
- **Context discipline** — `tokenBudget.js` (budgeted history), `codeSkeleton.js` (large files come
  back as an outline instead of raw text), `scratchpad.js` (working notes carried across turns).
- **Research** — `onlineResearch.js` plus native search/fetch providers.

### 2. The model zoo
Bring your own model, local or hosted.

- **On-device**: GGUF models run through the bundled llama.cpp runtime (`OnDeviceRuntime`),
  downloaded and managed inside the app (`src/components/ModelZoo.jsx`, `MyCollection.jsx`).
- **Local network**: any Ollama endpoint.
- **Cloud providers**: API-key providers are fully supported and stay supported
  (`src/providers/cloudProviderStore.js`), with automatic failover (`providerFailover.js`).
- Device capability checks (`useDeviceCapability`, `DeviceCapacity` plugin) stop the app from
  offering models the phone cannot actually load.

### 3. The SAF workspace
Real access to real user files, through Android's Storage Access Framework.

- The user picks a folder; `WorkspaceStorage.java` holds the persisted URI permission.
- `src/workspace/workspaceProvider.js` is the single I/O surface; `workspacePolicy.js` enforces
  size limits (2 MiB read/write), blocks sensitive paths, and hides internal `.luna-*` markers.
- `src/components/Workspace.jsx` + `src/editor/CodeEditor.jsx` give a file tree and an editor.

### 4. Minimal settings
Three tabs, no dials that do nothing (`src/components/Settings.jsx`):

- **Agent** — execution mode (ask first / run unattended, behind a confirmation).
- **Connections** — Ollama endpoint, cloud failover, GitHub token (stored in the Android Keystore
  via `CredentialVault.java`).
- **Data** — runtime info, error log, reset app data.

---

## Project layout

```
src/
  agent/        agentic loop, tool schemas, approval policy, execution mode, context tools
  components/   chat, workspace, model zoo, collection, settings, shared UI
  hooks/        useAgentPipeline, useWorkspace, useConversations, useInference, useModelCollection
  models/       model catalog, manifest, prompt profiles
  providers/    model provider abstraction, cloud provider store, failover
  workspace/    SAF workspace provider + policy
  research/     web search / fetch providers
  editor/       CodeMirror editor + formatting
  nativeBridge.js  the only file that talks to Capacitor plugins

android/app/src/main/java/ai/luna/app/
  WorkspaceStorage.java   SAF folder access, read/write/list/backup
  OnDeviceRuntime.java    llama.cpp bridge (cpp/ holds the native glue)
  TerminalRuntime.java    sandboxed shell
  GitRuntime.java         JGit clone/commit/push/status/diff/log
  ResearchRuntime.java    web search and page fetch
  CredentialVault.java    Keystore-backed token storage
  AutonomyRuntime.java    unattended-mode foreground service
  MainActivity.java
```

## Development

```bash
npm ci
npm run dev        # web shell (native features are unavailable in the browser)
npm run lint       # oxlint — must be 0 warnings / 0 errors
npm test           # node test suites
npm run build      # vite production build
```

Android:

```bash
npm run android:build   # bootstrap llama.cpp, preflight, build, cap sync, gradle assembleDebug
```

Or build from the phone with GitHub Actions — see [MOBILE_BUILD.md](MOBILE_BUILD.md).

App id `ai.luna.app`. See [docs/PRUNE_REPORT.md](docs/PRUNE_REPORT.md) for what was removed in the
rebuild and [docs/AUDIT_INVENTORY.md](docs/AUDIT_INVENTORY.md) for the pre-rebuild inventory.
