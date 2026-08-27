# What we can take from Mr-Nobody and Legally Unbullied

Read on 2026-08-27 from the two repos at `GraphicMiles/Mr-Nobody` (main) and
`GraphicMiles/Legallyunbullied` (main). Everything below was checked against the
actual files, with paths and sizes. Mr-Nobody is **MIT**, so reuse is clean.
Legally Unbullied has no licence file — treat it as design reference only, not
code to lift.

---

## 1. Mr-Nobody is the same stack as Luna

`app/pubspec.yaml`: Flutter, `sdk: ^3.5.4`, **zero third-party packages** — not
even a WebView plugin. Java under `app/android/app/src/main/java/com/mrnobody/`,
Dart under `app/lib/`. Same shape as Luna, so its Dart widgets and Java classes
port without dragging dependencies in.

628 files, ~24 MB (most of it golden-test PNGs).

---

## 2. The headless browser — real, and reusable

`agent/browser/HeadlessWebViewEngine.java` — 549 lines, 23 KB.

It is an off-screen `android.webkit.WebView`, not a bundled browser, so it costs
**nothing in APK size**. What it gives us:

- `loadAndExtract(url, timeoutMs)` — navigate, wait for `onPageFinished`, run an
  extraction script, return readable text. Best-effort: a timeout returns
  whatever it has rather than hanging the agent.
- `loadAndEvaluate(url, script, timeoutMs)` and `evaluate(script, timeoutMs)` —
  run JS against the **rendered DOM**. Their comment is the right argument:
  a selector against the live document survives markup churn, and a
  client-rendered results page is only visible this way at all.
- Interaction: `click`, `type`, `scroll`, `select`, `waitForSelector`,
  `uploadFile`, `waitFor`.
- All WebView work marshalled to the main thread internally, so tools can call
  it from any worker — exactly how Luna's `AgentEngine` runs.

`agent/browser/BrowserEngine.java` (83 lines) is the interface, so the engine is
swappable. `agent/browser/HeadlessSessions.java` (75 lines) keeps **one WebView
per task** with its own WebView profile, so two jobs cannot share cookies;
`isIsolated()` reports honestly when the device's WebView lacks multi-profile
rather than claiming isolation it did not get.

**Verdict:** this is the single biggest thing to take. It would give Luna
`open_page`, `read_page`, `click`, `type` for free, at zero size cost.

---

## 3. The tool layer worth stealing

`agent/tools/` (main source, no tests):

| File | Size | What it is |
| --- | --- | --- |
| `BrowserTool.java` | 28.6 KB | The browse tool over `BrowserEngine` |
| `SearchTool.java` | 12.1 KB | Web search with budget |
| `HttpTool.java` | 11.3 KB | Plain fetch with a text guard |
| `DownloadTool.java` | 10.5 KB | Resolve a link and download it |
| `TerminalRuntime.java` | 9.6 KB | Sandboxed command runtime |
| `MemoryTool.java` | 3.8 KB | Recall across runs |
| `WorkspacePath.java` | 1.8 KB | Path confinement |

And `agent/util/` — 29 small, self-contained classes that are the unglamorous
part nobody wants to write twice:

`ReadableText.java` (15 KB, article extraction), `DownloadLinkResolver.java`
(11.8 KB), `NetworkTargetPolicy.java` (8.2 KB — SSRF/private-range guard),
`RobotsRules.java` (8.3 KB), `SearchProviders.java` (8.6 KB),
`Hosts.java` (7.3 KB), `Sanitize.java` (6.6 KB), `DdgHtmlParser.java` (5.9 KB),
`HtmlText.java`, `PageForms.java`, `PageKind.java`, `HostRateLimit.java`,
`FetchRetry.java`, `EndpointPolicy.java`, `SiteMemory.java`.

Each has a matching unit test in `app/android/app/src/test/.../agent/util/`.

---

## 4. The harness Luna's loop is missing

`agent/core/ToolPipeline.java` (443 lines). Its header states the order every
call goes through:

```
record the call → validate parameters → prepare/replay ledger → approval
→ guards → confirmation → execute with timeout → normalise throws
→ validate output → render → record the result
```

Two properties it enforces that Luna currently does not:

- **Guards are monotonic** — a guard can only narrow permission, never widen it.
- **Confirmation fails closed** — a task woken with the screen off has nobody to
  ask, and "no answer" means "did not run".

Supporting pieces:

- `agent/policy/ApprovalPolicy.java` (167 lines) — resolves permission from
  per-tool override → approval mode → tier default. Luna has one global
  ask/auto switch; this is the "always allow downloads" escape hatch that makes
  an approval prompt bearable.
- `agent/policy/` also has `BudgetGuard`, `RepeatCallGuard` (stops the model
  looping on the same call), `RestrictedTools`, `TaskBudget`, `PerRunGuard`.
- `agent/core/ToolSpec.java` / `ParamSpec.java` / `OutputSpec.java` — declared
  parameter and output shapes, validated on both sides of execution. Luna
  currently trusts whatever JSON the model emits.
- `agent/execution/` — `SqliteExecutionLedger`, `ExecutionIdentity`,
  `LedgeredCall`: durable call identity, so a replayed call returns the
  committed result instead of running twice.
- `agent/jobs/` — `AsyncJobCoordinator` + `WorkManagerAsyncJobScheduler`. This
  is the pattern that fixes **our download-dies-when-backgrounded problem**.

---

## 5. The pipeline visualisation — already exists in Flutter

This is the important find: the animation you like in Legally Unbullied is
**already implemented in Dart** in Mr-Nobody.

`app/lib/widgets/agent_response.dart` — 1,297 lines, 42 KB, imports only
`flutter/material.dart`, its own `app_theme.dart` and `brand_logo.dart`.

Inside it:

- `AgentMetrics` — one metrics table (13px body, 1.62 height, 21px line box,
  21px avatar, 10px gap) so the avatar centres on line one instead of riding
  five pixels high.
- `AgentTurn` / `UserTurn` / `AgentStamp` — the turn layout.
- `ShimmerLabel` — a highlight sweeping through the label while working, which
  **stops completely** when inactive, "because a shimmer that never stops trains
  people to ignore it".
- `PixelLoader` — 3×3 grid with a chevron wavefront; 650 ms cycle chosen so two
  fronts are always in flight.
- `AgentWorkingLine` — loader + shimmering label + live elapsed timer.
- `TraceStep` and `AgentTrace` — the expandable "Thought for 4 seconds" trace.
  Auto-expands while running, collapses when settled, stays tappable. Uses
  `AnimatedAlign(heightFactor)` + `AnimatedOpacity` with `Cubic(0.23, 1, 0.32, 1)`,
  and a left border as the vertical rail.
- `StreamedAnswer` + `_Word` — the answer revealed word by word, each word
  resolving out of blur, with a blinking `_Caret`. Parent owns the timing, so
  the same widget serves a real token stream and a timed reveal.
- `_CiteChip` / `_SourceMark` — a **drawn** lettermark instead of a favicon:
  never a network request.
- `FigureWarning`, `AgentActions` (copy/retry/vote), `AgentFollowUps`.

`app/lib/agent/task_timeline.dart` (522 lines) is the data model behind it:
`enum TimelineState { working, done, recovered, failed, denied, waiting, cancelled }`
and a projection of append-only events into rows. Its design note matters —
there is deliberately **no fixed Search → Read → Answer pipeline**; a row exists
only because an event happened. That is honest, and it is what Luna's `step`
events already look like.

### What Legally Unbullied adds

`public/components/BeUIThinkingState.js` (409 lines) and
`BeUIStreamingText.js` (447 lines), vanilla JS, no framework.

Not portable to Flutter, but three ideas are worth copying:

1. **Stage timings** `const STAGES = [800, 600, 1800, 2600, 1600]` — uneven, so
   it reads as work rather than a progress bar.
2. **Variants** — `Steps`, `Reasoning`, `Search`, `Coding`, each with its own
   active label, done label and row shape. Luna's equivalents: thinking,
   listing/reading, searching, writing.
3. **Real elapsed time, two forms** — `startedAt` for a live ticking
   "Thought for 4.2s" (500 ms refresh), `elapsedMs` for a finished message. Plus
   a `static: true` mode that renders the collapsed finished state on reload so
   history matches the live pipeline exactly. Luna needs precisely this: our
   transcript currently redraws with no trace at all.
4. `WORD_MS = 100` with `filter: blur(10px)` → `stream-in 500ms
   cubic-bezier(0.22, 0.61, 0.25, 1)` — the same word-blur reveal that
   `StreamedAnswer` does natively in Dart.

---

## 6. What I would actually adopt, in order

1. **`agent_response.dart` + `task_timeline.dart`** — port into Luna, restyled to
   our monochrome tokens (their amber warning becomes fill + weight; their
   `Icons.auto_awesome` becomes a FontAwesome glyph). This alone gives us the
   trace, the working line, the elapsed label and the streamed answer. Highest
   value, lowest risk: pure presentation, no dependencies.
2. **`HeadlessWebViewEngine` + `BrowserEngine` + `HeadlessSessions`** — gives
   Luna a browser for free. Needs a decision from you: it re-opens the web tools
   we deliberately cut. My advice is to add exactly two — `open_page` and
   `read_page` — both mutating-tier so they hit the approval card.
3. **`ToolSpec` / `ParamSpec` validation and `RepeatCallGuard`** — small, and
   they fix real defects in our loop (unvalidated args, model looping).
4. **`AsyncJobCoordinator` + `WorkManagerAsyncJobScheduler`** — the correct fix
   for downloads dying in the background.
5. **`ApprovalPolicy` with per-tool overrides** — upgrade from our single
   ask/auto switch.
6. **`NetworkTargetPolicy`, `RobotsRules`, `ReadableText`, `Sanitize`** — only if
   we take the browser.

## What I would not take

- `DeterministicEngine.java` (90 KB) and most of `agent/planner/` — that is
  Mr-Nobody's deterministic, non-LLM planner. Luna's planner is the model.
- `agent/mcp/` (Canva OAuth, MCP client) and `agent/design/` — out of scope.
- `agent/dispatcher/RemoteWorker` — Luna is local-first.
- Anything from Legally Unbullied's server, corpus or eval harness.

## Size note

The Dart port is text only. The headless engine uses the system WebView, so it
adds no `.so` and no assets — call it under 100 KB of dex. We are at 28.83 MiB
against a 50 MiB budget, so all of the above fits comfortably.
