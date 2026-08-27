# Fixing the rest of the list

Everything from the "missing / nice to have" answer, grouped into batches. Each batch is
written, compiled against `android.jar` + Flutter stubs locally, then pushed.

## Batch 1 — the things that were unsafe
- Cloud keys move out of plain preferences into the keystore. Old keys are migrated on read
  and wiped from the preferences file.
- Per-tool rules: ask / always / never, honoured by the loop, not just the UI.
- A repeat-call guard: the same tool with the same arguments cannot run twice in one job.
- A budget per job: tool calls, wall clock, and how many times a cloud model may be called.
- An in-run ledger, so a retried step is answered from the record instead of run again.
- A persisted error log, so a failure survives the app being killed.

## Batch 2 — what the agent could not do
- A headless browser: `open_page` and `read_page`, using an off-screen system WebView.
- The GitHub token is finally read: `github_file` fetches a file from a repo with it.
- `ask_user` really asks, and blocks the job until you answer.
- Cloud replies stream, token by token.
- A provider's model list is fetched from the provider.

## Batch 3 — downloads that behave
- Pause and resume from the UI, resuming on the byte.
- A foreground service and a notification, so a download survives the app being killed.
- Wi-Fi only, and a pause when the battery is low.
- Import your own `.gguf`, copied into the models folder and marked "not verified".
- The checksum result is shown, not just pass or fail.

## Batch 4 — the folder and the app around it
- A revoked permission is told apart from an empty folder.
- More than one folder, switchable.
- Share a file to Luna from another app.
- Keep the model warm between messages, with an idle timer.

## Batch 5 — comfort
- Dark mode, larger text, screen-reader labels.
- More than one chat, with search.
- Export a job: the trace, the answer, what changed.
- Back up and restore settings.
- A first-run walkthrough.
- A confirm before a cloud provider is deleted.

## Not done, and why
Scheduled jobs and a biometric lock are listed but not built: both need a scheduler or a
prompt that cannot be tested here, and neither is load-bearing for the agent.
