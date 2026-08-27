# Bugs from the 23:37 build — noted, not yet fixed

From your two screenshots on 27 August. Nothing here has been touched; say which
ones to take and in what order.

## Chat

1. **The raw tool call is printed as the answer.** `{"tool":"write_file","args":…}`
   and a stray `{"changed": …}` both landed in the bubble. The streamed tokens of
   a tool call should never reach the thread — only the step row should. The
   parser strips the call after the fact, but the tokens have already been shown.
2. **"Overwrite notes.md with 1 lines?"** — the line count is not pluralised.
3. **Skip looks disabled.** On the white approval card the Skip button is light
   grey on near-white. It reads as ghosted next to a solid black Allow.
4. **A stray "…" chip** sits above the buttons — that is the content preview with
   nothing in it. It should not render when there is nothing to preview.
5. **The trace says "Working · 1 step"** while the job is parked on your
   approval. Waiting on you is not working, and the only step it lists is
   "Loaded the model", which is not a step the user cares about once it is done.
6. **The step list shows model loading but not the tool that triggered the
   approval.** `write_file` should be a row in "held" state, not absent.

## Model Zoo

7. **The imported model and the downloaded model are the same file** and both are
   listed — the imported copy of `qwen2.5-coder-0.5b…` duplicates the catalogue
   entry that is already installed. Dedupe by content, or say "already in the
   zoo" instead of importing a second copy.
8. **Titles truncate mid-word** — `qwen2.5-coder-0.5b-…` and `not ver…`. The row
   needs two lines for its own files, or a middle ellipsis.
9. **"Running now" claims the model is loaded** while the phone is idle. It is
   accurate only when the runtime holds it; otherwise it should read "Ready".

## Both

10. **Speed reads 11.1 t/s from the last run** with nothing to say it is
    historical. Either label it "last run" or clear it.

## Status after the 28 August build

Fixed: 1 (the engine holds a reply back until it knows it is prose, and the app
drops it again if it looks machine-shaped), 2 ("1 line"), 3 (Skip is #E4E4E8 and
sits second, after Allow), 4 (an empty preview no longer renders), 5 (the trace
says "Waiting on you" and the shimmer stops), 6 (the held tool is a row in
"waiting on you" state), 9 ("Ready to use" unless a job is running), 10 (speed is
labelled "Last run", cleared when a new job starts, and given in words a second).

Still open: 7 (imported model duplicating the catalogue entry), 8 (titles
truncating mid-word in Your own files).
