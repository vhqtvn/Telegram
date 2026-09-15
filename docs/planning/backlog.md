# Telegram — Backlog

Source of truth for task status — the harness coordination model reads this file.

**Agents edit this file freely.** Two disciplines keep a backlog edit from
blocking a code commit (enforced at the commit/workflow layer, not by blocking
edits):

- **Commit backlog SEPARATELY from code.** A code commit carries code (+ tests +
  the docs that justify it); a backlog commit carries backlog rows. One backlog
  commit per work cycle is the target. Never bundle a backlog-status change into
  a code commit.
- **On `could_not_land` (a content-tangle — another session's backlog edit
  landed first), re-read + re-apply + retry — do NOT revert this file.**
  Reverting discards other agents' promoted state. Re-read from the new HEAD,
  re-apply only your rows (by stable ID), and retry.

**DEFER / p2 follow-up items NEVER become rows here directly.** Capture them in
`.local/coordinator/tasks/` as conditional candidates (transport, not
truth) with Notes provenance (`source:review-defer`/`source:p2-followup`,
`trigger:...`, `studied:YYYY-MM-DD`). They reach this file only after a trigger
fires AND the promotion Definition of Ready is met. See
`docs/coordination/PROMOTER_RUNBOOK.md`.

Load the `backlog` skill before substantial backlog work. After a batch edit,
run `/backlog-cleanup` (or `vh-agent-harness exec node
.opencode/scripts/normalize-backlog.js`) to keep the active sections tidy and
archive `done`/`cancelled` rows under `docs/planning/archive/`.

- **IDs:** stable `<phase>-<AREA>-<NNN>`, e.g. `P1-CORE-001`, `P2-API-003`.
- **Statuses:** `todo`, `in_progress`, `blocked` (active) · `done`, `cancelled` (history).
- **Sections:** `Now` (active focus) · `Next` (queued) · `Later` (deferred). Active
  sections hold active statuses only; the normalizer enforces and archives the rest.
- **Columns:** `ID | Status | Area | Task | Owner | Notes | Links` (Notes may carry a `YYYY-MM-DD`).

## Archive Index

Older `done` and `cancelled` history lives under [docs/planning/archive/index.md](archive/index.md) and is meant for on-demand reading instead of auto-loading into the active backlog context.

- No archive files yet.

## Now

| ID | Status | Area | Task | Owner | Notes | Links |
| --- | --- | --- | --- | --- | --- | --- |
| FOLD-001 | in_progress | ui/foldables | Foldable iteration 1: preserve chat state + fix stale round-video sizing across runtime window size-class flips (fold/unfold, split-screen). | foldables-session | 2026-09-15: implemented in 3 files (AndroidUtilities.java, LaunchActivity.java, ChatActivity.java) +54/-20 — extract computeRoundMessageSizes() with recompute in resetTabletFlag(); pause live-view fragments + capture chat scroll in invalidateTabletMode() flip branch (persists eligible drafts via ChatActivity.onPause→saveDraft, restores scroll via existing recreate mechanism); wasTablet debug-toggle alignment one-liner. VERIFIED: `:TMessagesProj:compileDebugJavaWithJavac` and `:TMessagesProj_App:assembleAfatDebug` both green. Ship-review APPROVE. commit `24b04a16e` landed 2026-09-15 (code commit separate from this docs commit per split-commit rule); runtime validation still pending (no fold device/AVD) — behavioral closure = not-demonstrable. NOT claimed: attachment-picker/PhotoViewer continuity, all-drafts guarantee. |  |

## Next

| ID | Status | Area | Task | Owner | Notes | Links |
| --- | --- | --- | --- | --- | --- | --- |

## Later

| ID | Status | Area | Task | Owner | Notes | Links |
| --- | --- | --- | --- | --- | --- | --- |
| FOLD-002 | todo | ui/foldables | Foldable iteration 2 candidates: portrait medium-width UX policy (inner-display portrait currently tabletFullSize single-pane with ~568dp content clamp — decide two-pane vs centered max-width column), ExternalActionActivity recreation on fold (deferred, low), runtime fold matrix execution when hardware/AVD available. |  | derive decisions from the Phase-2 audit behavior table; any pane-policy change must not regress small tablets (isSmallTablet ≤690dp boundary). |  |

## Done

| ID | Status | Area | Task | Owner | Notes | Links |
| --- | --- | --- | --- | --- | --- | --- |

## Cancelled

| ID | Status | Area | Task | Owner | Notes | Links |
| --- | --- | --- | --- | --- | --- | --- |
