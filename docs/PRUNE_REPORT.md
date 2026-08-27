# Rebuild report — ForgeAI → Luna

Status: **complete, verified, uncommitted.** Base commit `922e037`.

The directive was a complete rebuild: keep only the agent, the model zoo (including cloud/API
providers), the SAF workspace, and minimal settings; prune everything else because it will be
redesigned. The agent is renamed **Luna** and positioned as a *local utility native agent*.

## Headline numbers

| | |
|---|---|
| Files changed | 180 |
| Files deleted | 128 |
| Files renamed (package rename) | 9 |
| Lines removed | ~14,984 |
| Lines added | ~1,736 |
| Remaining `src` files | 69 (52 JS/JSX, ~10k LOC incl. CSS) |
| Test suites | 18 → 17, all passing |
| Lint | `oxlint` 0 warnings / 0 errors across 73 files |

## What was removed

**Whole subsystems** (directory deleted): `src/memory/` (episodic, semantic vector, compression,
self-healing), `src/skills/` + validators, `src/social/`, `src/github/` (higher-level automation),
`src/browser/`, `src/context/`, `src/planning/`, `src/patch/`, `src/safety/`, `src/fs/`,
`src/tools/` (superseded by `src/agent/toolSchemas.js`), `src/terminal/` wrappers, `eval/`,
`config/`.

**Agent modules**: cognition, confidence engine, skeptic, self-correction, mistake memory,
persistent/preference memory, intent router and intent understanding, mission planner,
sub-agent orchestrator, agent roles, autonomy policy, autonomous queue, full-autonomy runner,
phase-4 runner, deterministic answers/format, follow-up suggestions, response quality,
project indexer, plugin contract, thinking budget, automation tiers, action protocol, core.

**UI**: AutomationSettings, ExperimentalFeatures, GitHubAutomationSettings, ResearchSettings,
SkillValidatorSettings, SocialMediaSettings, ProjectMemoryPanel, RepositoryIndexPanel,
TaskTimeline, CustomProfileModal.

**Native**: `GithubRuntime.java` (GitHub REST/archive plugin) — the agent now reaches GitHub
through Git itself; `CredentialVault` still holds the token for authenticated push.

**Dead bridge surface** in `nativeBridge.js`: `fileSystem`, `statusBar`, `notifications`,
`getDeviceInfo`, `githubApi`, `importGithubArchive`, `gitFetch/gitPull/gitCheckout/gitRebase`,
`cancelTerminalCommand`, `getTerminalInfo`, `getFullAutonomyStatus`, `isIOS`, `isDesktop`,
the default export (635 → 366 lines). Dependencies `@capacitor/filesystem`,
`@capacitor/status-bar`, `@capacitor/local-notifications` dropped with them.

**Docs**: FORGEAI_MASTER.md and 9 phase/roadmap/enterprise documents.

## What was rewritten

- `src/agent/agenticLoop.js` — one identity block: *"You are Luna, a local utility agent that runs
  natively on the user's Android device. You are not a chat window with tools bolted on: the device
  is your workplace."* Plus behaviour rules (act, don't describe; read before write; complete file
  content; one tool at a time; never retry a declined action; verify; finish with `respond`).
- `src/agent/toolPolicy.js` (new) — read-only vs mutating classification, `decide(tool)`.
- `src/agent/executionMode.js` (new) — `ask` | `auto`, key `luna_execution_mode`, default `ask`.
- `src/components/Settings.jsx` — from a sprawl of toggles down to 3 tabs: Agent, Connections, Data.
- `src/App.jsx` — a 5-screen shell with no memory/safety/autonomy/queue/suggestion plumbing.
- `src/hooks/useAgentPipeline.js`, `src/workspace/workspacePolicy.js` — rewritten around the
  surviving surface.
- `README.md`, `MOBILE_BUILD.md` — rewritten for Luna's four pillars.

## Rename

`ai.forgeai.app` → `ai.luna.app` across Java packages (`git mv` of both package directories),
`capacitor.config.json`, `build.gradle` namespace/applicationId, `strings.xml`,
`capacitor.settings.gradle`, `assetlinks.json`, the device-capacity plugin
(`@luna/device-capacity`, `ai.luna.devicecapacity`, gradle module `:luna-device-capacity`),
storage keys (`forgeai_` → `luna_`) and workspace markers (`.forgeai-` → `.luna-`).
Zero `forgeai` matches remain outside `node_modules/` and `dist/`.

**Two consequences to handle before release:**
1. The application id change means new installs sit alongside old ones — no upgrade path.
2. `public/.well-known/assetlinks.json` now names `ai.luna.app` but still carries the old signing
   fingerprint; the pairing must be re-established.
3. The Android/Java side could not be compile-verified here (no SDK/NDK in this environment).

## Verification

```
node /tmp/broken.mjs   # no dangling imports
npx oxlint .           # 0 warnings, 0 errors, 73 files
npm test               # 17 suites pass
npm run build          # vite build ok
```

Nothing has been committed or pushed; the changes are staged in the working tree.
