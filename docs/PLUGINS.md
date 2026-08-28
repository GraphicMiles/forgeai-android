# Writing a plugin

A plugin is a **signed JSON document**. It carries knowledge (skills), agents and
workflows — and nothing that executes. Installing one can never be worse than
being told something untrue, because the runtime never runs a plugin's code:
there is no code. Whatever a plugin's agent wants to do, it still has to do
through a tool the person already allowed, under the same "Ask me first" switch
and the same step, time and cloud budgets.

That restriction is the whole design of phase 5, and it is why plugins can be
installed from a file in your own folder without any of the usual ceremony.

## What one looks like

```json
{
  "format": 1,
  "id": "example.tidy",
  "name": "Tidy folder",
  "version": "1.0.0",
  "author": "Luna examples",
  "description": "Knowledge about naming files, and a workflow that reads a folder.",
  "capabilities": ["filesystem.read"],
  "skills":    [ … ],
  "agents":    [ … ],
  "workflows": [ … ]
}
```

Rules the verifier enforces, in this order, failing closed with a sentence you
can act on:

| Rule | Why |
| --- | --- |
| `format` is 1 | A plugin built for another Luna is refused, not half-read. |
| `id` is lower case, dotted, 3–64 chars | Ids end up in prompts and in paths. |
| It may not start with `core` or be `luna` | Nothing may impersonate the runtime. |
| `version` is present, contents are non-empty, ≤ 200 documents | A limit is a defence. |
| Every capability is known **and** grantable to a plugin | `credential.export` and `plugin.manage` never are. |
| Everything it defines is named after it | `example.tidy` may define `example.tidy.naming` and nothing else. |
| No agent claims `builtIn` | A plugin cannot pretend to ship with Luna. |
| The content matches its digest | It was not changed on the way. |
| The signature verifies | And, if the device has a trust list, was made by a key on it. |

An unsigned plugin is refused unless the developer setting for unsigned plugins
is on, which is off by default. Everything is verified **again on every launch**,
because what a device trusts can change between one run and the next.

## The three parts

**A skill** is a paragraph of instruction plus when to use it. It is only put in
front of the model when it is relevant — when the job mentions one of its
triggers, or when the tools it names are the ones in play.

```json
{
  "id": "example.tidy.naming",
  "name": "Naming files",
  "description": "How this person likes files named.",
  "instructions": "Use lower case with hyphens, no spaces …",
  "tools": ["write_file", "rename_file"],
  "requires": ["workspace"],
  "triggers": ["rename", "tidy", "organise"],
  "order": 45
}
```

`always: true` includes it in every prompt. `order` decides where it sits in the
prompt; the core skills use 0–90. `unless` excludes it in named situations.

**An agent** is a name, instructions, the skills it knows and the tools it may
use. A tool list can only *subtract*: an agent can never reach further than Luna
herself. Leave `tools` out for everything, or name the few you want.

```json
{
  "id": "example.reviewer",
  "name": "Reviewer",
  "instructions": "You are a code reviewer. You read and you report …",
  "skills": ["core.identity", "core.restraint", "example.reviewer.style"],
  "tools": ["list_files", "read_file", "search_code", "ask_user", "respond"],
  "maxSteps": 12,
  "maxSeconds": 300
}
```

**A workflow** is a job whose steps are known in advance. Node kinds: `llm`,
`tool`, `condition`, `loop`, `parallel`, `approval`, `human_input`, `sub_agent`,
`transform`, `validate`, `wait`, `end`. Values move between steps by name —
`"as": "listing"` stores a result, `{{listing}}` fills it in later.

```json
{
  "id": "example.tidy.survey",
  "name": "Survey this folder",
  "description": "Lists the granted folder and writes a paragraph about it.",
  "start": "list",
  "maxSteps": 8,
  "nodes": [
    { "id": "list", "type": "tool",
      "config": { "tool": "list_files", "args": { "path": "" }, "as": "listing" },
      "next": "describe" },
    { "id": "describe", "type": "llm",
      "config": { "prompt": "Here is a folder listing:\n\n{{listing}}\n\n…", "as": "summary" },
      "next": "done" },
    { "id": "done", "type": "end", "config": { "message": "{{summary}}" } }
  ]
}
```

Conditions are deliberately small: `key is value`, `not`, `contains`, `present`,
`empty`, `>`, `<`, or a bare name for truthiness. A condition nobody can read is
a bug waiting for a bad day.

## Signing it

```bash
scripts/luna-package.sh examples/plugins/example.tidy.json
scripts/luna-package.sh                     # every example, into assets/plugins/
scripts/luna-package.sh --verify assets/plugins/*.lunapkg.json
```

The packager is `tools/package/PluginPackager.java`, and it is Java on purpose:
it calls `PluginManifest.canonicalContent()` and `PluginVerifier` — *the app's own
classes*. A second implementation of the canonical form in another language
would be a second definition of the truth, and the day the two disagreed every
plugin everywhere would stop installing.

The signature covers a canonical rebuild of the content, not the raw file, so
reformatting the JSON or reordering its keys does not change a plugin's
identity. Renaming it, rewording an instruction, or adding a capability does —
each of those is a test in `ExamplesTest`.

A key is generated on first use and written to the path you pass with `--key`
(or `$LUNA_SIGNING_KEY`). No private key lives in this repository. The device
checks a signature against the public key the package carries; a trust list, if
the device has one, narrows that to keys it already knows.

## Installing and testing it

In the app: **Settings → Agent → Plugins**.

- **Install the examples** — the three signed packages in `assets/plugins/`,
  shipped inside the APK. Zero setup, and the fastest way to see the whole
  machine working.
- **From your folder** — anything named `*.lunapkg.json` at the top of the SAF
  folder you granted. Copy your own package there and install it.

Then, still in Settings:

- **Skills** lists what arrived, credited to your plugin, with a switch each.
- **Agents** lists any agent it brought; **Use** makes it the one that answers.
  Its tool list is shown, and it is narrower than Luna's, never wider.
- **Workflows** lists any workflow it brought, with **Run**. It runs in the chat,
  under the same approvals, the same limits and the same trace as a message.

Off the device, `scripts/logic-tests.sh` runs `ExamplesTest`, which holds the
shipped examples to the strictest reading of the rules — empty trust list,
unsigned disallowed — installs all three into real registries, checks every
document loads, and checks that tampering with any of them breaks the digest.
Point it at your own package the same way, or just run:

```bash
scripts/luna-package.sh --verify my-plugin.lunapkg.json
```

## What a plugin still cannot do

- Run code. There is nowhere to put it.
- Reach a tool the active agent does not have, or one the person has not allowed.
- Ask for `credential.export` or `plugin.manage`. No plugin is ever granted those.
- Read another plugin's secrets: `CredentialVault.scoped` namespaces every key it
  stores as `plugin:<owner>/<key>`.
- Skip an approval. The gate is in the runtime, not in the definition.
