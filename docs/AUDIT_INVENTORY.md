# Luna — Full Capability Inventory & Pruning Audit

Generated from the code at `922e037` (HEAD of `main`). Baseline health: `npm ci` clean,
`npm test` — all 41 suites pass.

---

## 0. What this project is

| | |
|---|---|
| Product | Luna — a local-first AI coding agent that runs **on an Android phone** |
| UI | React 19 + Vite 6 + framer-motion + lucide-react, wrapped in **Capacitor 7** |
| On-device inference | GGUF models via **pinned llama.cpp compiled through JNI** (`OnDeviceRuntime.cpp` + `.java`), no Ollama/HTTP server needed on device |
| Browser dev preview | Same React app talking to a local **Ollama** endpoint |
| Cloud inference | 17 OpenAI-compatible providers + custom endpoint, with automatic failover |
| Code size | ~131 JS/JSX files in `src` (~19k LOC) + 8 Android Java plugins (~2.3k LOC) + 41 node test suites |
| CI | GitHub Actions builds a debug APK on every push to `main` |

---

## 1. What the agent can actually DO

### 1.1 Tools the model can call (`src/agent/toolSchemas.js`, 19 tools)

Exposed both as **native OpenAI function-calling** (`toOpenAITools`) and as a prompt-embedded
```` ```tool_call ```` JSON protocol for models without tool support. The parser also recovers
Groq/Llama `<function=name {...}>` malformed calls.

| Group | Tools |
|---|---|
| Files | `read_file` (returns a token-cheap *outline* for big files), `read_symbol` (single function body), `write_file`, `create_file`, `create_folder`, `delete_file`, `list_files`, `search_code` |
| Shell | `run_terminal` (app-sandbox only, no root) |
| Web | `search_web`, `fetch_page` |
| Git | `git_clone`, `git_status`, `git_commit`, `git_push`, `git_diff`, `git_log` (JGit, app-private clones) |
| Control | `ask_user`, `respond` |

The **agentic loop** (`src/agent/agenticLoop.js`, 623 LOC) runs read → plan → edit → verify → fix →
respond for up to **12 iterations**, with tool-result compaction, dynamic tool subsetting, a bounded
scratchpad, and write verification (re-reads the file after writing).

### 1.2 Native Android capabilities (Capacitor plugins, `android/.../ai/luna/app/`)

| Plugin | Capability |
|---|---|
| `OnDeviceRuntime` | load/unload GGUF, streaming generate, cancel (aborts during prefill too), delete, download w/ pause+cancel, benchmark info |
| `WorkspaceStorage` (949 LOC) | SAF folder picker, list/read/write/create/rename/delete/inspect, **backups + undo**, model & SKILL.md file pickers, GGUF import w/ SHA-256, resumable downloads |
| `GitRuntime` | clone, status, log, fetch, pull, checkout, commit, push, rebase (JGit) |
| `GithubRuntime` | authenticated GitHub REST API calls, repo archive import into workspace |
| `CredentialVault` | GitHub PAT encrypted with Android Keystore; never enters prompts or shell env |
| `TerminalRuntime` | execute/cancel shell commands inside app-private storage |
| `ResearchRuntime` | web search + URL fetch (bypasses browser CORS) |
| `AutonomyRuntime` | native mirror of the autonomy on/off flag |
| `DeviceCapacityPlugin` | RAM/CPU probing to gate which models can load |

### 1.3 Models

* **8 curated GGUF models**, 94 MB → 1.7 GB (SmolLM 135M/360M/1.7B, Qwen2.5 0.5B/1.5B, Qwen2.5-Coder 0.5B/1.5B/3B). Each pinned to an immutable HF revision with exact size, trusted SHA-256, license + prompt profile. `scripts/validate-release-catalog.js` enforces this.
* **User GGUF import** — header-validated, hash recorded, labelled *not publisher-verified*.
* **17 cloud providers**: Groq, Cerebras, Google Gemini, OpenRouter, Mistral, GitHub Models, Cloudflare Workers AI, DeepSeek, NVIDIA NIM, SambaNova, Cohere, Together, Fireworks, OpenAI, xAI/Grok, Nebius, Ollama Cloud + custom. Live model-list fetching per provider, quality-ranked automatic failover on quota/rate-limit/5xx.

### 1.4 The message pipeline (what happens on every prompt, `src/App.jsx` — 1,509 LOC)

1. Pending-intent resolution → entity resolution → bare-GitHub-URL detection
2. Conversation-context turn processing → vague-reference/pronoun resolution
3. Intent understanding (typo/shorthand normalisation, anchor resolution)
4. Adaptive **thinking budget** (decides which cognitive stages wake up)
5. Clarification check for very vague messages
6. **Zero-token fast paths**: date/time, arithmetic, device facts, JSON pretty/minify, case conversion, CSV→markdown
7. Filename-reuse for "create the file"
8. **RAG**: relevant workspace files retrieved, user consent dialog, budgeted injection
9. Context compression + episodic-memory recall + project memory + preference memory + mistake memory + cognitive directives
10. Route: patch-proposal path (`phase4Runner`) **or** agentic loop **or** plain quality-graded generation
11. Optional mission plan + evidence-based confidence check (clarify if < 0.5)
12. Optional online research with publisher extraction, ranked evidence, `[n]` citation chips, og:image thumbnails
13. Post: benchmark info, episodic memory store, follow-up suggestion chips

### 1.5 Memory & cognition modules

Episodic memory, semantic vector memory, context compressor, persistent memory, project memory
(user-editable), repository symbol/import/call indexer, mistake memory, preference memory, skeptic
(critiques changes), confidence engine, self-correction, mission planner, scratchpad, token budget,
code skeleton extraction, agent roles (planner/scout/coder/reviewer/verifier) + subagent budgets.

### 1.6 Safety & governance

Configurable safety policy (`config/safety_policy.json`), relative-paths-only workspace policy,
2 MiB read/write cap, secret-file and dependency-dir blocklists, approval gate for tool execution,
autonomy levels (restricted → full, opt-in), automation tiers, undo for write/rename/delete,
RAG disclosure before sending files to a cloud model, pluggable skill validators
(passthrough / basic syntax / strict security scanner) for imported `SKILL.md` skills.

### 1.7 UI surfaces

Chat (streaming, action cards, reasoning panel, activity log, task timeline, follow-up chips),
Model Zoo, My Collection (downloads/imports/benchmarks), Workspace (file tree + CodeMirror editor
with 13 languages, folding, search, Prettier formatting), Settings (providers, autonomy, automation,
research, GitHub, social, skills, experimental flags, custom prompt profiles, project memory,
repository index).

---

## 2. Complexity map — where the weight is

| Cluster | LOC | Verdict |
|---|---|---|
| `src/agent/*` (33 modules) | 5,476 | Core loop is ~1,100 LOC. The other ~4,400 is pre-loop heuristics + cognition layers. |
| `src/App.jsx` (single file) | 1,509 | God component: routing, RAG, autonomy, streaming, memory glue all inline. |
| Pre-loop heuristic routers (intentRouter, intentUnderstanding, conversationContext, deterministic*, phase4Runner, core.js, fullAutonomyRunner, responseQuality, followUpSuggestions, autonomousQueue) | 2,222 | Mostly regex/keyword gates that a tool-calling model makes redundant. |
| Cognition/memory stack (cognition, skeptic, thinkingBudget, mistake/preference memory, confidence, missionPlanner, persistentMemory, projectIndexer, memory/*, subagentOrchestrator, agentRoles) | 1,829 | Each is small and tested, but 12 layers all inject system prompts into a 0.5B model. |
| Social + browser automation + skills/validators subsystem | 1,395 | Mostly simulated/experimental; not part of a coding agent. |
| **Files unreachable from `src/main.jsx`** | **1,278** | Pure dead weight. |

### 2.1 Confirmed dead code (import-graph reachability from `main.jsx`)

```
src/agent/selfCorrection.js        331   src/terminal/RealTerminal.js       176
src/research/RealResearchProvider   83   src/terminal/NativeTerminal.js      83
src/social/RealSocialProvider.js    74   src/browser/RealBrowserAutomation   73
src/github/RealGitHubProvider.js    69   src/browser/NativeBrowserAutomation 68
src/memory/selfHealing.js           67   src/planning/multiStepPlanner.js    65
src/browser/BrowserAutomation.js    61   src/fs/fileSystemIntelligence.js    41
src/utils/parseFileActions.js       33   src/workspace/workspaceStore.js     21
src/workspace/safePath.js           19   src/research/researchTools.js       18
src/utils/onDeviceCapability.js     13
```
(`selfCorrection`, `safePath`, `onDeviceCapability` are referenced by tests only.)

### 2.2 Dead / never-invoked tools (per the project's own `docs/agent-capabilities.md`)

* `github:propose`, `github:run_maintenance` — registered, never surfaced to the model
* `research:query`, `research:scrape` — registered, never invoked by chat
* `fs:analyze` — trivial file counts, unwired
* `src/agent/core.js` keyword planner — "dead code in live chat, wired only in tests"

### 2.3 Simulated / non-functional features

* Browser automation (`navigate/click/...`) returns `{status:'simulated'}` unless an experimental flag + native runtime exist.
* Terminal is simulated unless Experimental → Real Terminal is ON.
* Social media posting: scaffolded providers, unverified per platform.

### 2.4 Documentation sprawl

9 docs + `LUNA_MASTER.md` (~1,200 lines), including `ENTERPRISE_FEATURES.md` (100 unbuilt feature
ideas), 4 overlapping phase-plan docs, and a desktop roadmap. Three of them describe pipelines that
no longer match the code.

---

## 3. Recommended pruning (highest value first)

**Tier 1 — free deletes, zero behaviour change (~1,300 LOC)**
Delete every file in §2.1, plus the dead tool registrations in §2.2. Drop the matching tests.

**Tier 2 — cut the non-agent product surface (~1,400 LOC)**
Remove social media (manager, providers, settings panel), browser automation, and the
skill-package/validator subsystem + its settings panels. None of it serves "an agent that edits code
on your phone", and each adds a settings screen, a storage key, and a security surface.

**Tier 3 — collapse the pre-loop heuristics into the loop (~1,500–2,000 LOC)**
This is the big quality win. Today the model's decision is second-guessed by ~8 regex routers
(`isCodeChangeRequest`, `isWorkspaceActionRequest`, `isAutonomousToolRequest`,
`isOnlineResearchRequest`, intent router, entity resolver, vague-reference resolver, pending intent).
With native function-calling the correct design is: **everything goes into the agentic loop, and the
model picks the tool.** Keep only the zero-token fast paths (date/math/format) and `ask_user`.
Retire `phase4Runner`'s parallel patch pipeline in favour of `write_file` + approval gate.

**Tier 4 — thin the cognition stack (~800 LOC)**
Twelve prompt-injecting layers on a 0.5B model is net-negative: they eat the context window that the
actual code needs. Keep **scratchpad, token budget, code skeleton, project index, episodic memory**.
Fold mission planner + confidence engine + skeptic + thinking budget + mistake/preference memory into
one small `cognition.js` with a single toggle, or drop to cloud-model-only.

**Tier 5 — split `App.jsx`**
Extract `useAgentPipeline()`, `useWorkspaceActions()`, `useProviders()`. Target < 400 LOC of JSX.

**Tier 6 — docs**
Keep `README.md`, `ARCHITECTURE.md`, `MOBILE_BUILD.md`, `agent-capabilities.md`. Archive or delete
the 4 phase docs, `ENTERPRISE_FEATURES.md`, `DESKTOP_ROADMAP.md`, `LUNA_MASTER.md`.

### Resulting lean core

```
UI (chat + workspace + models + settings)
  → agenticLoop (19 tools, 12 iterations, verify-after-write)
      → workspaceProvider (SAF / virtual)   → nativeBridge (llama.cpp, git, terminal, research)
      → approval gate + safety policy
  → providers (local GGUF | 17 cloud w/ failover)
  → memory: scratchpad · project index · episodic
```
Estimated ~19k → ~12k LOC in `src`, with the agent behaving *more* predictably because a single
decision-maker (the model) is in charge.
