---
name: bump
description: "Gated upstream release bump (bump-pilot, Route B): the choreography for driving an upstream rebase through Tools/bump-gate.sh and the bumper agent — plan → dispatch bumper with cheat-sheet → start → resolve → continue → verify → hand to R5/registry flow. Load this before any upstream release bump, before dispatching the bumper agent, or when a bump is stuck (abort/recovery)."
compatibility: opencode
---

# Gated Upstream Release Bump (bump-pilot)

> **Pilot status:** Route B — project-owned `Tools/bump-gate.sh` + the
> `bumper` overlay agent. Validated by the next real bump (12.10.6). Promotion
> to Route A (harness core) after 1-2 clean bumps. Owner:
> `.vh-agent-harness/overlays/bump-pilot/`.

## When to use

A new upstream DrKLO/Telegram release ("update to X.Y.Z (build)" commit on
`upstream/master`) is being adopted onto this fork's local commit series
(`docs/planning/local-changes.md` is the patch map). The bump is a REBASE of
the local series onto the new release commit — per maintenance rule R3.

## Roles

| Role | Who | Does |
|---|---|---|
| Executing dispatcher | `build` ONLY | fetches upstream, builds the cheat-sheet, **executes every `Tools/bump-gate.sh` verb on the bumper's direction** (`vh-agent-harness exec Tools/bump-gate.sh <verb> <ref>` — the ONLY invocation form that passes shell-guard today), runs R5 + registry update after |
| Resolution authority | `bumper` (this pack) | directs the verb sequence, resolves conflicts with Edit per registry + cheat-sheet, verifies no markers remain, reports gate-JSON |
| Routing | `coordination` / `project-coordinator` | may dispatch the bumper for resolution work and make decisions — but being read-only primaries, they do NOT execute gate verbs themselves; route the execution leg through `build` |
| Review/landing | coordinator via committer + R5 gate | verifies build, updates `docs/planning/local-changes.md`, operator pushes |

**Invocation split (harness v-current constraint, 2026-10-13):** the bumper
agent cannot hold the gate-invocation grant — a direct `Tools/bump-gate.sh *`
allow is a dead grant (shell-guard hard-denies non-git/non-harness commands
before per-agent tables), and a routed `vh-agent-harness exec …` allow loses
to bumper's mandated trailing `vh-agent-harness *: deny` under findLast
(the after-wildcard channel is core-tables-only). Either would fail doctor.
So the DISPATCHER executes the verbs; the bumper directs/resolves/verifies.
Closing this split (pack-expressible exec carve-out, or an engine-recognized
bump-gate family, or Route A) moves verb execution into the bumper.

Nobody fetches through the bumper, NOBODY pushes, NOBODY commits during the
bump: the dispatcher owns the network step (`git fetch upstream` — no agent's
bash table admits fetch, but dispatcher build may route it through
`vh-agent-harness exec`), the operator owns the push (`git push origin HEAD
--force-with-lease`, host terminal — rebase rewrites SHAs).

## Choreography

```
1. DISPATCHER: fetch + target
   git fetch upstream
   git log upstream/master --grep="update to" --oneline -5   # find new release hash
   # upstream/master now points at the release; that tracking ref IS the bump target

2. DISPATCHER: build the cheat-sheet
   - re-read docs/planning/local-changes.md rows whose files appear in
     git diff --name-only $(git merge-base HEAD upstream/master)..upstream/master
   - for each expected-conflict file: the resolution intent (keep ours / take
     theirs / fuse) per the registry's "Conflict-prone regions" column
   - known recurring: gradle.properties (keep APP_PACKAGE, take APP_VERSION_*),
     ChatActivity fixLayout preDraw (verify the standalone fixLayoutInternal()
     + return true shape survives), signing/build.gradle tails (re-apply
     mechanically)

3. DISPATCHER: dry prediction (optional, read-only)
   vh-agent-harness exec Tools/bump-gate.sh plan upstream/master
   → predicted conflict set; sanity-check against the cheat-sheet

4. DISPATCHER → BUMPER: dispatch with the cheat-sheet + registry rows in play;
   from here the bumper DIRECTS and the dispatcher EXECUTES each verb:
   vh-agent-harness exec Tools/bump-gate.sh <verb> <ref>

5. BUMPER directs: start upstream/master
   - exit 3 (conflicts): bumper resolves per cheat-sheet + registry with the
     Edit tool, verifies no markers remain, then directs continue — repeat
     per local commit
   - exit 0 (completed_verified): bumper reports + stop
   - exit 4 (completed_verify_failed): bumper reports failures JSON, STOP —
     recovery is operator-side (git reset --hard pre-bump-backup from a host
     terminal) or a coordinator-decided fix-forward
   - stuck/ambiguous: bumper directs abort (returns to pre-rebase state,
     keeps backup ref) and reports

6. POST-BUMP (dispatcher/coordinator — NEVER the bumper):
   a. git submodule update --init --recursive --depth=1   # mandatory post-bump
   b. R5 gate: ./gradlew :TMessagesProj:compileDebugJavaWithJavac
               ./gradlew :TMessagesProj_App:assembleAfatDebug
   c. Registry update (R4): new base hash, conflict-resolution notes — commit
      via the committer (gated-commit protocol)
   d. Operator pushes: git push origin HEAD --force-with-lease
```

## Safety contract (one-liner)

History mutates ONLY via `Tools/bump-gate.sh`'s fixed verbs (argv-validated;
caller tokens never forwarded to git), invoked exclusively by the dispatcher
as `vh-agent-harness exec Tools/bump-gate.sh <verb> <ref>` on the bumper's
direction; the gate stages only `--diff-filter=U` paths (never `-A`),
verifies conflict-marker cleanliness + submodule gitlink parity with upstream
before declaring success, keeps a `pre-bump-backup` ref on every path, and
refuses every bypass.

## Concurrency contract (hard rule)

**CONCURRENCY: never run two bump-gate invocations concurrently. The
dispatcher (build) serializes all gate calls; the operator hosts none. The B1
lock-lifecycle race is accepted pilot debt under this contract (card
bump-gate-b1-lock-lifecycle) and is a Route-A promotion blocker.**

One gate invocation at a time, always as a single serialized
`vh-agent-harness exec Tools/bump-gate.sh <verb> <ref>` line from the
dispatcher, completed before the next begins. This is what makes B1 — abort's
stale-lock cleanup racing a live mid-admission start, and the identity-less
EXIT-trap `rm -rf` of the lock dir — unreachable: under serialization there
is never a second concurrent invocation to race. B1 is NOT fixed in the gate
script; it is accepted as documented pilot debt by operator adjudication
(2026-10-14: "(b) Land now, document B1 as a pilot-known limitation,
serialize by contract; B1 fix becomes a Route-A promotion blocker on the
existing card") and blocks Route-A promotion until the fix lands.

## Recovery quick-reference

| State | Probe | Recovery |
|---|---|---|
| Mid-bump, conflicts open | `Tools/bump-gate.sh status` | resolve + `continue`, or `abort` |
| Lock held, no rebase (crash) | `Tools/bump-gate.sh status` | `Tools/bump-gate.sh abort` (releases lock) |
| Gate refuses: `foreign_rebase` / `rebase_identity_mismatch` / `identity_unestablished` | refusal JSON | a rebase is in progress that this gate does NOT own (no lock, or orig-head/onto does not positively match the lock's recorded instance — e.g. operator interleave); the gate refuses to drive or abort it — inspect `.git/rebase-merge` and resolve or abort it from a host terminal |
| Completed but verify failed | gate JSON `completed_verify_failed` | operator: `git reset --hard pre-bump-backup` (host terminal) or fix forward + re-`verify` |
| Stale backup ref blocking start | gate JSON `backup_ref_exists` | after confirming no bump in flight, operator: delete `pre-bump-backup` |

## Honest limits

- `plan`'s merge-tree simulation computes one cumulative merge; rebase replays
  per-commit — richer conflict sets than predicted are normal.
- The bumper agent is dispatchable only after an opencode restart (agent
  registry is cached per-process).
- **Invocation split (harness v-current):** the bumper cannot itself invoke
  the gate — the dispatcher executes every verb (see Roles). Dead-grant
  finding documented in `.vh-agent-harness/config-transform.mjs`.
- **B1 lock-lifecycle race (accepted pilot debt):** the gate is safe ONLY
  under the concurrency contract above — never two concurrent invocations.
  Route-A promotion blocker (card `bump-gate-b1-lock-lifecycle`).
- First REAL validation of the whole pilot = the pending 12.10.6 bump.
