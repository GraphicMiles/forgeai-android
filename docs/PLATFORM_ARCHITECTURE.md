# Luna Platform — architecture and migration plan

The decision: **Luna is not rebuilt.** The runtime it already has is kept, and
the hardcoded parts become contracts with an extension layer around them.

> Today: the app contains an agent.
> Target: the app contains an agent runtime that can load agents, skills, tools,
> workflows, models, memory providers and execution backends.

The product is *an open-source runtime for building, installing, composing and
running autonomous agents.* Luna is the default agent shipped with it.

---

## 1. Target architecture

```
                          LUNA PLATFORM
                              │
                    ┌─────────▼─────────┐
                    │    Agent Runtime  │
                    │ planner           │
                    │ context           │
                    │ memory            │
                    │ execution         │
                    │ permissions       │
                    │ recovery          │
                    └─────────┬─────────┘
                              │
          ┌───────────────────┼────────────────────┐
          ▼                   ▼                    ▼
      AGENTS               SKILLS              WORKFLOWS
     personas            tools/actions       pipelines
     configs             instructions        conditions
     memory              validators          subagents
     policies            dependencies        checkpoints
          └───────────────────┼────────────────────┘
                              │
                     ┌────────▼────────┐
                     │  PROVIDER LAYER │
                     │ Local models    │
                     │ Ollama          │
                     │ Cloud APIs      │
                     │ Remote inference│
                     │ Custom servers  │
                     └────────┬────────┘
                              │
                     ┌────────▼────────┐
                     │ EXECUTION LAYER │
                     │ Android         │
                     │ Desktop         │
                     │ Docker          │
                     │ VPS             │
                     │ GPU workers     │
                     └─────────────────┘
```

And the critical change:

```
                             CORE
                              │
          ┌───────────────────┼───────────────┐
       built-in            community        private
        plugins             plugins         plugins
```

The core must not know whether a capability came from Luna itself, GitHub, a
user's filesystem or a community developer.

The conceptual move:

```
      LUNA                              LUNA RUNTIME
        │                                    │
   ┌────┴────┐              ┌────────────────┼─────────────────┐
 Agent     Tools         AGENTS           SKILLS          WORKFLOWS
   │         │              └────────────────┼─────────────────┘
Models    Files                         CAPABILITIES
          Browser         ┌────────────────┼─────────────────┐
          GitHub        TOOLS            MEMORY           MODELS
                          └────────────────┼─────────────────┘
                                    PROVIDER LAYER
                          ┌──────────────┼──────────────┐
                        PHONE           VPS           CLOUD
```

Every layer can be replaced. "I don't want Luna's browser, install Playwright."
"Use my VPS, not the cloud." "Fork this research workflow." "I built a better
coding agent." None of those may require modifying the core.

---

## 2. What is kept

Roughly 70 % of the hard agent-runtime foundation already exists. Preserved
heavily, close to intact:

`AgentEngine` · `RunGuards` · `ToolPolicy` · `WorkspaceStore` ·
`CredentialVault` · `HeadlessBrowser` · `CloudProvider` · `OnDeviceRuntime` ·
`NetworkTargets` · `ReadableText` · `ErrorLog` · `DownloadService` ·
transcript/recovery · approvals · cancellation · watchdog · run ledger · model
probing · provider abstraction · event stream · snapshot state model.

The existing **stop → resume → approval → timeout → replay → recovery** chain is
the foundation. The problem is only that these components assume Luna is the
only agent and its tools are the only tools.

---

## 3. The changes, in the order they were specified

### Tools become a registry (§3)
`Tools.run(env, tool, args)` with a fixed universe does not scale. Replace with a
`ToolRegistry` the agent queries: `registry.availableTools(agent, context)`.
Native, plugin, remote, MCP, workflow and sub-agent tools all implement one
contract.

### A real tool contract (§4)
`{id, name, description, input, permissions, risk, timeout, requires, supports}`
plus `ToolDefinition`, `ToolExecutor`, `ToolValidator`, `ToolPermission`,
`ToolResult`. One of the most important abstractions in the project.

### Capability declarations (§5)
`filesystem.read`, `filesystem.write`, `network.request`, `browser.navigate`,
`shell.execute`, `process.spawn`, `credential.read`, `github.write`,
`deployment.create`. A plugin declares what it needs; the user is shown it in a
sentence.

### ToolPolicy becomes a policy engine (§6)
Capability policy · agent policy · plugin policy · user policy · execution mode ·
sandbox policy · risk level. `filesystem.read` → LOW → RUN. `filesystem.write` →
MEDIUM → ASK. `shell.execute` → HIGH → ASK/SANDBOX. `credential.export` →
CRITICAL → DENY.

### AgentEngine stops being Luna (§7, §8)
`AgentDefinition` (id, name, version, description, icon, author, model policy,
system instructions, skills, workflows, memory, permissions, runtime
requirements, dependencies) loaded by a generic `AgentRuntime`. An agent becomes
configuration and is portable as a folder: `manifest.json`, `instructions.md`,
`skills/`, `workflows/`, `memory/`, `assets/`.

### Plugin runtime (§9, §10)
`PluginManager` (discover, install, verify, enable, disable, update, uninstall,
rollback) and `PluginRegistry`. **Plugins are declarative first** — manifest,
skill markdown, workflow JSON, tool definitions, tests. No arbitrary Java in the
process. Sandboxed code execution comes later.

### Skills (§11, §12, §13)
Extract prompt knowledge out of `systemPrompt()` into composable skills
(manifest, instructions, tools, workflows, examples, validators, requirements)
with a `SkillRegistry` and **skill relevance** — never dump every installed skill
into every prompt. `SmallTalk` grows into an `IntentRouter`: conversation,
question, tool_use, workflow, research, coding, file_operation,
agent_delegation, plugin_management, system_action.

### Workflows (§14, §15)
A `WorkflowEngine` with node types LLM, TOOL, CONDITION, LOOP, PARALLEL,
APPROVAL, HUMAN_INPUT, SUB_AGENT, TRANSFORM, VALIDATE, WAIT, END. Eventually the
agent loop itself is a workflow.

### Sub-agents (§16, §17)
`AgentSpawner`, `AgentContext`, `AgentBudget`, `AgentResult`. Each sub-agent gets
isolated context, limited permissions and its own budget; the parent receives a
result, not a transcript. `RunGuards` grows into `ExecutionBudget`: maxSteps,
maxTime, maxTokens, maxCloudCost, maxToolCalls, maxSubAgents, maxNetworkRequests,
maxParallelTasks.

### Memory (§18, §19)
`MemoryProvider`: LocalTranscript, SQLite, Postgres, Vector DB, Remote,
Encrypted, None. Separate conversation memory, working memory, long-term memory,
knowledge/RAG and execution memory. They must not collapse into one context
window.

### Inference and routing (§20, §21)
`InferenceProvider` — `generate`, `stream`, `cancel`, `listModels`, `probe`,
`capabilities` — implemented by llama.cpp, Ollama, OpenAI, Anthropic, Gemini,
OpenRouter, remote vLLM, custom HTTP. Then an `InferenceRouter` choosing on task
type, model capability, RAM, latency, cost, privacy, context size and
availability.

### Execution providers (§22–§24)
Separate from inference: inference is where it thinks, execution is where the
tool runs. `ExecutionProvider`, `FileSystemProvider` (AndroidSAF, local, git,
Docker volume, remote, cloud storage), `BrowserProvider` (WebView, Chromium,
Playwright, remote, Browserless).

### GitHub becomes a plugin (§25)
`github.search/read/write/issue/pr/commit/release`. The core keeps only generic
capabilities.

### Secrets (§26)
`SecretProvider` with per-plugin namespaces. Plugin A cannot reach plugin B's
secrets.

### Events, runs and replay (§27–§29)
A formal `AgentEvent` schema; the transcript becomes a structured execution log
(user_input, plan, model_call, tool_call, approval, tool_result,
subagent_started, subagent_result, checkpoint, final) still rendered as a chat.
That buys debugging, replay, auditing, visualisation, crash recovery, evaluation
and developer tooling — including "replay from step 14".

### Packaging and trust (§30–§33)
A manifest (`id, name, version, author, type, runtime.minLuna, permissions,
skills, tools, dependencies, platforms`), a package format `.lunapkg`
(`manifest.json`, `skills/`, `workflows/`, `tools/`, `agents/`, `assets/`,
`tests/`), publisher/version/hash/signature/source/licence metadata, trust tiers
(Verified, Community, Local, Unsigned) and a plugin sandbox behind a permission
broker — designed *before* any marketplace.

### Layering and stores (§34, §35)
`Flutter → Platform API → Luna Runtime {Agent Manager, Plugin Manager, Workflow
Engine, Tool Registry, Model Registry, Memory Registry, Policy Engine, Execution
Manager}`. `LunaBridge` only translates UI calls and events. `Prefs` splits into
SettingsStore, AgentStore, PluginStore, ModelStore, ProviderStore, WorkflowStore,
MemoryStore, SecretStore, WorkspaceStore.

### Registries and UI (§36–§41)
Model Zoo becomes a Resources hub (models, inference servers, agents, skills,
plugins, workflows, memory providers, execution providers). Add an
`AgentRegistry` and an `EnvironmentRegistry` (this phone, laptop, VPS, office
server, cloud — each exposing capabilities, models, tools, storage, browser,
GPU, memory, limits, latency). The screens become **Home, Agents, Workspace,
Resources, Runs, Settings**; Files grows into Workspace (files, projects,
repositories, connections, environments). A developer SDK follows:
`luna create skill|plugin|agent|workflow`, `luna dev|test|package|publish`.

### Testing and CI (§42, §43)
Plugin tests ship with plugins and run before installation: manifest valid,
dependencies resolved, permissions valid, tools registered, tests passed,
signature verified. CI gains plugin contract tests, security tests, workflow
tests, agent runtime tests, permission tests, replay/recovery tests, and a
compatibility matrix across Android, desktop and server.

---

## 4. Not to be built yet (§44)

Marketplace · social network · remote GPU marketplace · decentralised inference ·
multi-user collaboration · scheduled agents · biometric lock · visual workflow
editor · custom scripting language · dozens of model providers.

First make the runtime extensible.

---

## 5. Migration order (§45) — the plan of record

| Phase | Work | State |
| --- | --- | --- |
| 1 | Extract contracts: ToolProvider, InferenceProvider, StorageProvider, BrowserProvider, SecretProvider, ExecutionProvider | **done** |
| 2 | Tool Registry; existing tools become built-in plugins `core.filesystem`, `core.browser`, `core.user`, `core.github` | next |
| 3 | Skill system: SkillRegistry, SkillDefinition, SkillResolver | |
| 4 | Agent system: AgentDefinition, AgentRegistry, AgentManager — Luna becomes an installed agent | |
| 5 | Plugin system: PluginManager, PluginRegistry, PluginManifest, PluginInstaller, PluginVerifier (declarative only) | |
| 6 | Workflow engine: WorkflowDefinition, WorkflowNode, WorkflowExecutor | |
| 7 | Memory abstraction: MemoryProvider, MemoryRegistry; split conversation / working / long-term / execution | |
| 8 | Inference abstraction + InferenceRouter | |
| 9 | Execution environments: ExecutionProvider, EnvironmentRegistry | |
| 10 | Sub-agents: AgentSpawner, AgentContext, AgentBudget, AgentResult | |
| 11 | Marketplace / registry — only once the runtime is stable | |

### The ten to touch first

| Priority | Component | Change |
| --- | --- | --- |
| 1 | Tools | Tool Registry + Tool Contract |
| 2 | AgentEngine | Agent runtime independent of Luna |
| 3 | ToolPolicy | Capability-based permissions |
| 4 | `systemPrompt()` | Skill system |
| 5 | CloudProvider / runtime | InferenceProvider interface |
| 6 | Workflow in AgentEngine | Workflow engine |
| 7 | WorkspaceStore | Storage provider |
| 8 | HeadlessBrowser | Browser provider |
| 9 | Transcripts | Structured run/event model |
| 10 | New subsystem | Plugin manager + manifest + registry |

All ten before any marketplace UI.

---

## 6. Target repository shape (§46)

```
luna/
├── core/        agent, runtime, planning, context, memory, execution,
│                policy, recovery, events
├── contracts/   Tool, Skill, Agent, Workflow, Memory, Inference,
│                Execution, Provider
├── plugins/     manager, registry, installer, verifier, sandbox
├── builtin/     filesystem, browser, github, user
├── providers/   llama, ollama, openai, anthropic, gemini, remote
├── workflows/
├── agents/
├── android/
└── flutter/
```

---

## 7. Phase 1 — delivered

Package `ai.luna.contracts`, compiled into the app and into the JVM test run.

| Contract | What it fixes | First implementation |
| --- | --- | --- |
| `Capability` | Permissions were three hardcoded lists; they are now named, described in one sentence each, and some are ungrantable to plugins | 17 names |
| `RiskLevel` | Risk was implicit in "mutating" | LOW / MEDIUM / HIGH / CRITICAL |
| `ToolDefinition` | A tool was a switch case | 13 built-ins described as data |
| `ToolResult` | Tools returned bare strings | ok / failed / unfinished / denied, with timing |
| `ToolContext` | `Tools.Env` held concrete Android classes | providers + agent id + owner id + platform |
| `ToolProvider` | The tool list was fixed | `BuiltinTools` (`id() == "core"`) |
| `StorageProvider` | The filesystem *was* the Android grant | `WorkspaceStore` (`android.saf`) |
| `BrowserProvider` | The browser *was* the WebView | `HeadlessBrowser` (`android.webview`) |
| `SecretProvider` | The vault had one flat namespace | `CredentialVault` (`android.keystore`), core keys unmoved, plugins namespaced `plugin:<id>/<key>` |
| `InferenceProvider` | Three unrelated code paths | `LocalInference` (`local.llamacpp`), `CloudInference` (`cloud.<kind>`) |
| `ExecutionProvider` | Where a tool runs was never a question | `AndroidExecution` (`android.local`) |
| `Trace` | The log was `ErrorLog` by name | `ErrorLog` implements it |

Behaviour is unchanged: `AgentEngine` still calls `Tools.run` through the same
`Env`, which now holds contracts instead of concrete classes.

Guarded by `ContractsTest` (43 checks), which also pins the two layers together:
a definition's risk must agree with `ToolPolicy.isMutating`, its `requires`
must agree with `ToolPolicy.needsFolder`, every described tool must be one the
engine knows and vice versa, and every declared capability must be a real one.
The built-ins already carry their dotted platform names
(`read_file → filesystem.read`), so Phase 2 can rename without breaking a saved
chat.

---

## 8. Phase 2 — delivered

The tool list is no longer written anywhere in the engine. It is asked for.

### The plugins that ship with the runtime

`ai.luna.builtin` — four providers, each a plugin like any other, distinguished
only by being installed before the app starts.

| Provider | Tools | Capabilities | Needs |
| --- | --- | --- | --- |
| `core.filesystem` | list_files, read_file, search_code, write_file, create_file, create_folder, delete_file, rename_file | `filesystem.read/write/delete` | a granted folder |
| `core.browser` | open_page, read_page | `browser.navigate/read`, `network.request` | a browser |
| `core.github` | github_file | `github.read`, `network.request` | nothing |
| `core.user` | ask_user, respond | `user.ask` | nothing |

`BuiltinProvider` is the shared base: it credits every definition to its
provider, answers `owns()`, names the missing resource before a call is
attempted, and runs the call by handing a `ToolContext` to the existing
`Tools.run`. The old `BuiltinTools` class is gone; `Builtins.all()` returns the
four in prompt order and now also holds the legacy → dotted rename map.

### The door

`ai.luna.runtime.ToolRegistry`. One entry point, and everything it enforces is
enforced for a plugin exactly as for the core:

- **one owner per name** — a tool id belongs to whoever claimed it first, so an
  installed plugin cannot quietly become `delete_file`;
- **the platform has to match** — `runsOn(context.platform)`;
- **every capability must be granted by the environment** — `AndroidExecution`
  offers nine, so a tool wanting `shell.execute` is not merely refused at call
  time, it is never shown to the model;
- **resources must exist** — no folder, no file tools in the prompt; no
  browser, no page tools;
- **required arguments before approval** — a malformed call is rejected before
  a person is asked to allow it;
- **each tool's own clock** — `open_page` 20 s, `ask_user` 10 min, the rest 30 s,
  instead of one 90-second rule for everything;
- **a provider may misbehave** — a throw, a null, or a hang becomes a failed or
  unfinished `ToolResult`, never an ended run.

### What the engine gave up

| Was | Now |
| --- | --- |
| `ToolPolicy.isKnown(tool)` | `registry.has(tool)` |
| `ToolPolicy.isMutating` / `needsFolder` at the call site | read off the definition |
| `Tools.Env env = new Tools.Env(...)` | `ToolContext` built from the execution environment |
| `Tools.run(env, tool, args)` behind a fixed 90 s watchdog | `registry.run(context, tool, args, watchdog)`, the watchdog now a service given the tool's own limit |
| 14 hardcoded example lines in the system prompt | `registry.promptLines(context)` |
| `LunaBridge` reading `ToolPolicy.READ_ONLY` / `MUTATING` | `agent.toolIds(false/true)` |

A tool result that fails now shows as `failed` in the trace, which the Dart
timeline reads as a refusal row with "it did not work".

Guarded by `RegistryTest` (43 checks): registration, ownership, availability
with and without a folder or browser, a read-only capability grant, missing
arguments, a provider that throws, a provider that returns nothing, timeouts,
prompt lines, and an impostor plugin that tries to claim `delete_file`.

---

## 9. Phase 3 — delivered

Luna's competence left the engine. `AgentEngine.systemPrompt` was 55 lines of
English; it is now six lines that ask for a prompt.

### A skill is data

`SkillDefinition` — id, name, description, the instructions themselves, the
tools it talks about, the resources it needs (`requires`) or must not have
(`unless`), the words that bring it in (`triggers`), whether it is `always` on,
and where it sits in the order. It round-trips through JSON, because a plugin
ships knowledge as JSON and nothing else. There is no code in a skill; that is
what makes installing one safe.

### The seven Luna ships with

`ai.luna.builtin.CoreSkills`, each one a paragraph that used to be an `append`:

| Skill | Order | Condition |
| --- | --- | --- |
| `core.identity` — who and where she is | 0 | always |
| `core.restraint` — most messages need no tool | 10 | always |
| `core.files` — read before you write, relative paths | 30 | needs a folder |
| `core.no-folder` — say so and ask, don't waste a step | 30 | *unless* a folder |
| `core.web` — never invent an address | 40 | needs a browser |
| `core.asking` — stop when a guess would be expensive | 50 | needs `ask_user` |
| `core.reporting` — one tool per reply, no unsupported claims | 90 | always |

`core.small-talk` is held apart: it replaces the prompt rather than joining it,
so a greeting turn contains no tool list at all.

### Choosing

`SkillResolver` drops a skill when the resources are missing, when none of the
tools it discusses are available, or when the turn is plainly about something
else — then stops adding once the character budget is spent. An `always` skill
is never cut; the core rules survive any amount of installed knowledge.

`SystemPrompt` assembles the result in a fixed order: identity, then the
situation (folder and mode, the only part that changes every run), then the
remaining skills, then the tool lines from `ToolRegistry`, then the model's own
persona.

### What this buys

Teaching Luna something no longer means editing Java. A plugin that knows how a
particular kind of project is laid out ships a JSON skill, it appears when the
message is about it, and it disappears on a phone with no folder granted.
Skills can be switched off (`Prefs.disabledSkills`), which means a person can
see and control everything their agent has been told.

Guarded by `SkillsTest` (49 checks): registration and ownership, JSON round
trips, the folder / no-folder pair being mutually exclusive, resolution with
and without resources, prompt order, the greeting prompt carrying no tools, an
installed plugin skill appearing only when relevant, an impostor failing to
overwrite it, and the budget cutting a chatty skill before a core one.

---

## 10. Phase 4 — delivered

Luna is now an installed agent. The app runs *an* agent; it is no longer *the*
agent.

### An agent is data

`AgentDefinition` — id, name, description, version, author, its own extra
instructions, the skills it has, the tools it may use, a preferred model, and a
budget in steps and seconds. `*` means "whatever the runtime offers". It
round-trips through JSON, so an agent is a document somebody can write.

`LunaAgent.DEFINITION` is that document for Luna: every skill, every tool,
`builtIn`, version 1.0.0. Nothing about her is special any more except that she
is registered first and is therefore the fallback.

### Narrowing, never granting

The rule that has to hold the moment anyone can write a definition: **an agent
list can only subtract.** `AgentManager` intersects the agent's tool list with
what `ToolRegistry` already decided this environment offers, so an agent that
asks for `shell_exec` on a phone gets nothing — not a refusal at call time, but
absence from the prompt. The same for budgets: `steps(appLimit)` returns the
smaller of the two, never the agent's number when it is larger.

| Question | Asked of |
| --- | --- |
| Does this tool exist and work here? | `ToolRegistry` |
| May *this agent* use it? | `AgentManager.canUse` |
| What goes in the prompt? | `AgentManager.promptLines` / `.skills()` |
| How long may this run be? | `AgentManager.steps` / `.seconds` |

`ToolContext` is now credited to the active agent id rather than the literal
string "luna", which is what will later let a sub-agent's spending and refusals
be attributed to it.

### Persistence

`Prefs.activeAgentId` (defaults to `luna`) and `Prefs.installedAgents`, stored
as the JSON they arrived as rather than parsed into columns — a definition is a
document, and a store that understood its fields would need migrating every
time one gained a field.

Guarded by `AgentsTest` (43 checks): Luna as an ordinary entry, installing from
JSON, an agent that cannot declare itself built in, an impostor that cannot take
Luna's id, switching, narrowing of tools and skills and prompt lines, budgets
that shrink but never grow, and a greedy definition asking for a shell and a
deployment on a phone and receiving neither.

Suite total: **309 checks across six files.**

---

## 11. Phase 5 — delivered

Plugins, declarative only. A plugin is a document, never a program: it carries
skills, agents and (from Phase 6) workflows, and nothing that executes. The
worst an installed plugin can do is tell the model something — and the model can
only act through tools the environment already allowed.

### The manifest

`PluginManifest`: format, id, name, version, author, description, the
capabilities its contents may ask for, the documents themselves, and a `signing`
block of digest, signature and public key. `canonicalContent()` rebuilds the
content in a fixed shape before hashing, so whitespace and key order cannot
change a plugin's identity.

### The verifier

`PluginVerifier` fails closed, in order, with a sentence a person can act on:

1. format is one this runtime understands;
2. the id is lower-case dotted, 3–64 characters, and is not `core*` or `luna`;
3. it has a version and carries something, and not more than 200 documents;
4. every capability exists **and** is grantable to a plugin — `credential.export`
   and `plugin.manage` are refused here, permanently;
5. every document it defines is named after it (`acme.invoices.filing`), so a
   plugin cannot redefine `core.identity` or claim an agent called `luna`;
6. no agent may claim `builtIn`;
7. the content matches its own SHA-256;
8. the signature verifies (`SHA256withRSA` over the digest) and, when the device
   keeps a trust list, belongs to a key on it.

Unsigned plugins are refused unless `Prefs.allowUnsignedPlugins` is on — a
developer setting. An unsigned plugin is not dangerous the way running code is
dangerous, but it is anonymous, and anonymous knowledge is worth refusing.

### Installing

`PluginManager` verifies, then feeds the contents into the *same* registries the
built-ins use: skills to `SkillRegistry` credited to the plugin id, agents to
`AgentRegistry`. There is one code path for knowledge, one for agents, and a
plugin's skill is resolved, narrowed and budgeted exactly like Luna's own.
Manifests persist through a `Store` (`PrefsPluginStore`) and are **verified
again on restore** — what a device trusts can change between one run and the
next, and a phone that stops allowing unsigned plugins stops loading the ones it
accepted while it did.

Guarded by `PluginsTest` (44 checks) with a real RSA keypair generated in the
test: shape, tampering after signing, forged signatures, a signature from the
wrong key, trust lists, namespace squatting, ungrantable capabilities, double
installs, and a restore that refuses what the tightened rules no longer accept.

---

## 12. Phase 6 — delivered

Workflows: a job written down as steps instead of hoped for in a prompt. An
agent loop is a good way to handle a request nobody anticipated and a poor way
to do the same thing every Monday.

### The twelve kinds of step

`llm`, `tool`, `condition`, `loop`, `parallel`, `approval`, `human_input`,
`sub_agent`, `transform`, `validate`, `wait`, `end` — all of `WorkflowDefinition`
is data, so a workflow can arrive in a plugin.

Templating is `{{name}}`, filled from what the run knows. Conditions are
deliberately tiny — `key is value`, `not`, `contains`, `present`, `empty`, `>`,
`<` — and are split *before* filling, so `{{answer}} present` with nothing known
asks whether an empty thing is present rather than mistaking the word "present"
for the whole expression. `transform` has seven operations and no expression
language: a scripting language inside a workflow is a second product, and the
plan says not to build one.

`parallel` means independent, not simultaneous. A phone has one tool runner and
one model; running branches at once would mean two copies of a 3B model in
memory. The contract the node makes — these branches do not depend on each
other, and all of them must finish — is the part an author actually relies on.

### Refusing early

`WorkflowDefinition.problem()` is checked at registration and again before a run
starts: dangling targets, unknown step kinds, a tool step with no tool, a
condition with nothing to decide. A broken workflow fails **as a workflow**,
before it has changed anybody's files.

### The seam

`WorkflowHost` is the only route out: think, tool, approve, ask, sub-agent,
pause, stopped, event. `AgentEngine.EngineHost` implements it against machinery
that already existed — the tool registry with its watchdog, `RunGuards`,
`ToolPolicy`, the approval wait, the question wait — so **a workflow gets no
power the chat loop does not have**. `requestApproval` grew an overload that
takes the question as text, because a person should not be able to tell whether
a tool or a workflow is asking.

`askOnce` is a single model call with no transcript and no tool list: the two
existing paths (loaded local model, configured provider), streaming tokens to
the same chat.

### The trace

`WorkflowRun` is the structured record the plan asks for: every step with its
node id, kind, outcome and time, plus the variables. `WorkflowRun.resume` reads
one back and `run(workflow, input, fromNode)` starts from it — replay from a
checkpoint, working today rather than aspirational.

Budgets cannot be escaped through a loop: every pass costs from the same total,
so a loop with `max: 100` inside a workflow with `maxSteps: 5` stops at five.

Guarded by `WorkflowTest` (68 checks) against a recording host — no model, no
phone, no person, no clock.

---

## 13. Phase 7 — delivered

Memory, split into the five things the transcript was being asked to be at once.

| Kind | Lives | Held by |
| --- | --- | --- |
| `conversation` — what was said | with the chat | `EphemeralMemory` |
| `working` — notes for the job in hand | **dies with the run, on purpose** | `EphemeralMemory` |
| `long_term` — what the person told Luna | forever | `FileMemory` |
| `knowledge` — how their folder is laid out | forever | `FileMemory` |
| `execution` — what was actually done | forever | `FileMemory` |

`MemoryProvider` is a contract because the right answer differs by kind and by
device: working memory belongs in RAM, long-term memory in a file, and knowledge
about a large folder will one day belong in something with an index.
`FileMemory` writes one JSON file per kind under the app's own storage — not in
the person's folder, which is theirs and not a database.

### Recall, not carrying

`MemoryRegistry.recall` scores rather than searches: three points per matching
word, a fifth of the record's own importance, and a small bonus for being
recent. Being *about the subject* outweighs being important, so a vital fact
about photographs stays out of an answer about invoices — but a record marked 80
or above comes back even with no match, because some things should be said
regardless. No embeddings, no index, no second product.

The five best lines go into the prompt under `You already know:`, and nothing is
added when nothing is remembered.

### Pruning and forgetting

A full store drops the **least important** record, not the oldest: what somebody
deliberately told Luna outlives what she noticed in passing. A person can forget
one record or a whole kind, and `memoryCatalogue()` shows each kind, its
one-sentence description, its provider and its count.

Every run ends with `memory.endOfRun()`, which clears working memory. A note
about a job that has finished is not a fact about the person.

Guarded by `MemoryTest` (37 checks).

---

## 14. Phase 8 — delivered

`InferenceRouter`: which brain answers, and the sentence that justifies it.

The old rule was "use the local model, fall back to the cloud if it will not
load". That is fine until the questions differ — a document that does not fit in
a 2k window, a private file that must not leave the phone, a provider that has
been returning errors for ten minutes.

### The order

1. what the person or the agent explicitly chose, if it is usable and healthy;
2. anything **local** that fits, because the device is free and private;
3. a remote provider — but only if the work is allowed to leave;
4. a resting provider as a last resort, saying openly that it has been failing.

Privacy is a hard stop, not a preference: a `private` job that no local model can
take is refused rather than sent, and so is any job when the person has not
turned failover on.

### Explaining itself

Every `Route` carries a reason: *"Qwen 1.5B runs on the phone, so the work stays
here"*, *"Groq answered, because this is bigger than the phone's model can
hold"*, *"That has to stay on the phone, and no model here can take a job this
size."* "It failed over" is not an explanation; these are. The reason is what
the failover event and the debug panel now show.

### Health

`failed(id)` rests a provider for two minutes; `worked(id)` wakes it immediately.
`AgentEngine` reports both outcomes from the paths that already existed — a local
generate failure, a cloud error, a successful reply — and `inferenceHealth()`
exposes the tally.

Guarded by `RouterTest` (28 checks): the ordinary case, size, privacy in both
directions, preferences that can and cannot be honoured, cooldown and recovery,
last-resort use of a failing provider, and a reason on every single decision.

---

## 15. Phase 9 — delivered

`EnvironmentRegistry`: everywhere a tool could run, with the phone as the first
entry rather than the only kind.

A laptop on the network, a VPS over SSH and a Docker container are all the same
shape of thing — a platform name, a set of capabilities, somewhere to put files,
maybe a browser, somewhere secrets live. `DeclaredEnvironment` is one that has
been *described* but not reached: it answers `available() == false` and gives a
sentence saying so.

That honesty is the feature. The registry plans around a machine that could run
a shell without anything pretending a shell has run:

- `activate(id)` refuses an environment that is not reachable — an agent
  stranded somewhere absent cannot even tell you why;
- `where(tool)` finds an environment whose platform and capabilities suit the
  tool;
- `elsewhere(tool)` produces *"shell_exec cannot run on this phone, but The
  build box could do it"* — but only when the box is actually connected, so
  Luna never dangles a machine that is offline.

`ToolContext` is now built by the registry from the active environment, and the
tool registry is granted **the active environment's** capabilities rather than
the phone's directly. The day a transport exists, `AndroidExecution` stops being
the only implementation and nothing above it changes.

`Prefs.declaredEnvironments` stores the machines a person has added.

Guarded by `EnvironmentTest` (25 checks).

---

## 16. Phase 10 — delivered

Sub-agents: handing a piece of work to a narrower agent, under the same budget.

This is the feature most likely to eat a battery, a cloud bill and a person's
trust at once, so most of `SubAgentSpawner` is refusals. A spawn survives six
questions before anything runs:

1. does the child exist;
2. is spawning allowed here at all (`agent.spawn`, from the environment);
3. is there depth left — a child of a child of a child is a runaway;
4. is this a loop — an agent spawning itself, or **any ancestor above it**;
5. is there budget left to give away;
6. does the child stay inside the parent's tools.

Number six is the security one. Without it a locked-down agent could reach
anything by asking a wider one to fetch it, which is how privilege escalation
reads in every system that has ever had it. A narrow agent asking for Luna
herself is told: *"Luna wants every tool, which Narrow does not have to give."*

### Budgets as a value

`AgentBudget` is what `RunGuards` was hiding: steps, seconds, cloud calls and
depth, as something that can be handed down. It only ever narrows —
`forChild` gives a **third** of what is left and one level less depth, because
the parent still has to finish the job and tell somebody about it. A child's own
`maxSteps` narrows it further; nothing widens it.

`AgentResult` carries the summary the parent can put in its own answer, what the
child actually spent, and the refusal when there is one. A child that throws is
a failed child, not a failed run; a child that returns nothing is refused rather
than believed.

### What a child can do today

It gets its own system prompt — its skills, its narrowed tool list, its own
recalled memories — and one model call. It cannot run tools yet: the chat loop
is still the only thing that can, and giving a child a second copy of that loop
before it is extracted would mean two loops disagreeing about what a run has
already spent. The seam (`SubAgentSpawner.Runner`) is where the extracted loop
will go.

Workflows reach this through the `sub_agent` node, which now spawns for real.

Guarded by `SubAgentTest` (43 checks).

---

## 17. Where the platform stands

| Phase | State |
| --- | --- |
| 1 — contracts | delivered |
| 2 — tool registry, built-ins as plugins | delivered |
| 3 — skills | delivered |
| 4 — agents, Luna as one of them | delivered |
| 5 — plugins, declarative and signed | delivered |
| 6 — workflows | delivered |
| 7 — memory | delivered |
| 8 — inference router | delivered |
| 9 — environments | delivered |
| 10 — sub-agents | delivered |
| 11 — registry / marketplace | **not started, deliberately** |

Phase 11 stays where the plan put it: last, and after everything above has been
used in anger. Nothing about a marketplace is hard except deciding what is worth
publishing, and that answer comes from living with the parts, not from building
a shop for them.

**The suite: 563 checks across 12 files, all passing on a plain JVM in about
four seconds.**

### Still hardcoded, and honest about it

- The chat turn loop is a method rather than a runnable object, which is why a
  sub-agent can think but not act.
- `Prefs` is still one store with eleven domains in it.
- `LunaBridge` is still the only door between Dart and Java.
- Environments other than the phone are declared, never reached: there is no
  transport yet.
- `ToolPolicy` remains a decision function rather than the capability-based
  policy engine the plan describes; the capabilities exist, the engine that
  reasons over them does not.
- There were no screens for any of it. That one is answered in §18.

---

## 18. The runtime, made visible

Ten phases built a platform nobody could see. Everything below Settings existed
only as method calls: plugins installed themselves at launch, skills went into
prompts, workflows waited to be started by an id nothing displayed. This section
closes that gap without adding a screen.

### Where it lives

The four-screen rule holds. The runtime is a **drill-down inside Settings**, one
page in front of the three tabs, reached from rows that say what they lead to:

| Tab | Row | Page |
| --- | --- | --- |
| Agent | Agents | who answers, and what that one may touch |
| Agent | Skills | the knowledge Luna is given, with a switch each |
| Agent | Plugins | what is installed, what it may ask for, and how to add more |
| Agent | Workflows | jobs whose steps are known in advance, with **Run** |
| Connections | Machines | where work can run, and what is wrong with each |
| Connections | Provider health | who has been answering, who is resting |
| Data | What Luna remembers | five kinds, counts, and a way to forget each |

`lib/screens/platform_pages.dart` holds all seven. They are built from the same
`SectionLabel` / `Group` / `LunaRow` / `PillButton` / `Note` vocabulary as the
rest of Settings, read their data when they open — none of it changes between
one message and the next — and say what happened exactly once, in a snackbar.

`LunaRow` gained `subtitleLines`, because a row that has to explain what a
workflow does cannot do it in one line. One by default: a settings list reads as
a list only while the rows are the same height.

### The door

Fifteen new cases in `LunaBridge`, all in `handleBlocking` rather than the fast
switch, because installing a plugin verifies an RSA signature and reading memory
touches a file. Nothing new was added to `AgentEngine`: every one of these
catalogues already existed and had no caller.

```
plugins · installPlugin · removePlugin
agents · activeAgent · activateAgent
skills · setSkillsDisabled
workflows · runWorkflow
memory · forgetMemory · remember
environments · inferenceHealth
```

### Something to test it with

Three example plugins ship inside the APK, signed, in `assets/plugins/`:

| Plugin | Carries | Shows off |
| --- | --- | --- |
| `example.tidy` | a naming skill, a folder-survey workflow | triggers, `requires: workspace`, a `tool` → `llm` → `end` chain |
| `example.reviewer` | a review skill, a read-only agent | an agent that subtracts tools — it cannot write, rename or delete |
| `example.standup` | a three-question workflow | `human_input`, a `condition`, a `transform`, and `{{templating}}` |

**Settings → Agent → Plugins → Install the examples** installs all three with no
setup at all, and **From your folder** installs any `*.lunapkg.json` sitting at
the top of the SAF folder you granted. Both go through the ordinary verifier: no
developer switch, no unsigned exception.

The packager is `tools/package/PluginPackager.java`, driven by
`scripts/luna-package.sh`. It is Java on purpose — it calls the app's own
`PluginManifest.canonicalContent()` and `PluginVerifier`, so what is hashed at
build time is by construction what is checked on the device. A reimplementation
in a scripting language would be a second definition of the truth, and the day
the two disagreed every plugin everywhere would stop installing. No private key
is in the repository: one is generated on first use, and a signature is checked
against the public key the package carries.

`ExamplesTest` (50 checks) holds the shipped examples to the strictest reading
of the rules — empty trust list, unsigned disallowed — installs all three into
real registries, checks every skill, agent and workflow loads and that the
reviewer really cannot write, and checks that changing a version, rewording an
instruction, stripping the signature or adding a capability each breaks the
digest.

**The suite is now 613 checks across 13 files.** `docs/PLUGINS.md` is the
author's side of all this: what a plugin is, how to write one, how to sign it,
and what it still cannot do.
