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

---

## Second device run — 28 August, 00:51 ("hi")

Eighteen findings, in the order they hurt. All eighteen are addressed by the
"self-healing pipeline" change described under each one.

1. **A held tool showed no approval card.** The row said "Opening a page —
   waiting on you" and nothing asked anything. The job could only end in Stop.
   The whole approval chain was read end to end and every link was correct, so
   the event was lost in transit. Fixed by redundancy rather than repair: the
   question is now announced on three channels — the `approval` event, a copy
   carried on the held step row, and a `pendingPrompt` key in the snapshot —
   plus a re-emit every two seconds while it is still unanswered. Any one of
   the three draws the card.
2. **The header, the row and the working line disagreed.** There is now one
   status line: the trace header. The working line is deleted.
3. **The clock counted the time you spent deciding.** Waiting is banked
   separately and subtracted; `run_done` carries `workMs`.
4. **"Thought for 58.9s" was false and over-precise.** It uses `workMs` and
   whole seconds.
5. **"Opened a page" in the past tense beside a cross.** Refused steps now read
   "Did not open a page".
6. **"not allowed" was ambiguous.** Three different sentences: "you skipped
   this one", "your rules say never for this", "over the limit for one job".
7. **"Stopped." was one word above the trace.** It reads "I stopped there." and
   the trace is rendered above the answer, where the record belongs.
8. **"hi" loaded the model and called `open_page` with no folder.** The prompt
   now opens with the rule that most messages need no tool, and file tools are
   omitted entirely when no folder is granted — and refused before approval if
   one is somehow called.
9. **"Loaded the model" was a step.** Hidden once it succeeds; still shown when
   it fails.
10. **Two Stop buttons.** One, in the trace header.
11. **"Thinking" twice.** Once.
12. **The header was fainter than the rows.** Same weight, mark leading.
13. **The rail overshot the last row.** It measures the rows.
14. **The detail column was stranded at the right edge.** It is a second line
    under the label.
15. **The thread was top-aligned in a void.** Bottom-anchored.
16. **No Carry on after a stop.** Pressing Stop is remembered on the Dart side
    as well as in the engine's note, so the way back in cannot be lost.
17. **The paperclip.** It picks a real file from the granted folder.
18. **Two Luna marks.** The screen header no longer carries one; the trace
    header does.

### Smoothing done at the same time

- Every tool call runs under a 90-second watchdog on its own thread. A timeout
  is a step that says "took too long and was dropped" plus an instruction to
  try a smaller piece, not a hung job.
- Every wait polls in 500 ms hops, so Stop is instant.
- A reply that is malformed JSON earns exactly one correction, then is answered
  plainly. It is never printed at you as though it were the answer.
- Token events are coalesced into one repaint every 60 ms.
