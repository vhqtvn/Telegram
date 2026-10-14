# bump-pilot

A project-local overlay pack (the first in this repo) that routes upstream
release bumps through a gated wrapper instead of raw rebase commands.

## What this is

- **`Tools/bump-gate.sh`** (repo `Tools/`, project-owned, executable): the
  gate — fixed verbs `plan|start|continue|abort|status|verify`, argv-validated
  so caller tokens are never forwarded to git; mkdir-based lock under
  `.git/bump-gate.lock/`; JSON status output lines; `pre-bump-backup` ref kept
  on every path; staging restricted to `--diff-filter=U` paths (never `-A`);
  post-verify of conflict-marker cleanliness + submodule gitlink parity +
  clean tree; SKIP_COMMIT_GATE refused.
- **`agents/bumper.md`** → renders to `.opencode/agents/bumper.md`: the
  rebase-resolution authority. Never fetches/commits/pushes; resolves
  conflicts with the Edit tool per the local-changes registry + dispatcher
  cheat-sheet; reports gate-JSON; escalates on unexpected conflict shapes,
  verify failures, submodule mismatches.
- **`skills/bump/SKILL.md`** → renders to `.opencode/skills/bump/`: the
  coordinator choreography (plan → dispatch → start → resolve → continue →
  verify → R5/registry flow) and recovery quick-reference.
- **`permission-pack.jsonc`**: canonical permission input for the emitter —
  bumper gets readonly + git_readonly + repo-wide edit, and NOTHING else.

**Invocation split (harness v-current, 2026-10-13):** the bumper cannot hold
the gate-invocation grant. A direct `Tools/bump-gate.sh *` allow is a DEAD
GRANT (shell-guard hard-denies non-git/non-harness command families before
per-agent tables — dead_grant_lint "TrueAI defect class"), and a routed
`vh-agent-harness exec Tools/bump-gate.sh *` allow loses to the trailing
`vh-agent-harness *: deny` under opencode's findLast (the after-wildcard
channel, ReadOnlyExtraAllows, is core-tables-only). Either dead grant makes
`doctor` FAIL. Interim: the executing dispatcher — `build` only (read-only
coordinator primaries route but do not execute) — runs every verb as
`vh-agent-harness exec Tools/bump-gate.sh <verb> <ref>`; the
bumper directs/resolves/verifies. Closing options: pack-expressible exec
carve-out, engine-recognized bump-gate family, or Route A promotion. Full
finding in `.vh-agent-harness/config-transform.mjs`.

## Instance-token ownership (round-6 hardening, 2026-10-13)

When the gate's own rebase stops, `start`/`continue` write the lock's random
128-bit instance token (meta schema `bump-gate-lock-v3`) into
`.git/rebase-merge/bump-gate-owner` (gitdir resolved per invocation); the
shared `_gate_owns_rebase` validator requires that file AND token equality
before `continue`/`abort` touch anything. A host-side `git rebase --abort`
deletes `rebase-merge/` wholesale, so a replacement rebase — even with
identical orig-head AND onto — cannot present the token and is refused
(`not_gate_owned`; different token → `rebase_token_mismatch`). Token WRITE
failure (e.g. read-only gitdir) fails the verb closed
(`token_write_failed`, lock retained). `status` surfaces
`rebase_owner_token: present|absent|n/a` for refusal diagnosis.

### Regression evidence

Machine-exercised (throwaway fixture repos under `tmp/` only — the project
repo is never mutated by the tests; harness was a transient
`tmp/test-bump-gate.sh`, receipts retained in the landing session's
closeout, 50/50 assertions green):

- (c) happy path: gate rebase stops (exit 3, token file written, meta v3,
  disk token == meta token), resolve, `continue` → `completed_verified`
  exit 0; rebase dir cleaned, lock released, backup ref kept.
- (a) host aborts the gate rebase, starts a replacement rebase with
  IDENTICAL inputs (same orig-head, same onto) → gate `continue` refused
  `not_gate_owned` (exit 1) — the same-input replacement window is closed.
- (b) same fixture → gate `abort` refused `not_gate_owned` (exit 1); the
  foreign replacement rebase is left untouched.
- (f) forged token in the owner file → `rebase_token_mismatch` (exit 1).
- (e) token write failure at a second stop (chmod'd owner file) →
  `continue` dies `token_write_failed` (fail-closed), lock retained;
  host recovery (fix perms, host abort) then gate `abort` releases the
  stale lock (exit 0).
- (d) happy abort: gate-owned stopped rebase → `abort` exit 0, rebase
  cleaned, backup ref kept.
- usage matrix (8 malformed argv forms → exit 2), `status` smoke, read-only
  `plan` smoke, and the `_json_escape` unit (quote/backslash/newline/tab).

Deferred to the 12.10.6 live-fire (crux-deferred): the mutating lifecycle on
REAL upstream history — real submodule gitlink parity during conflicts,
plan-vs-rebase prediction divergence at release scale, and the bumper-agent
choreography end-to-end after the required opencode restart.

## Known limitation: B1 lock-lifecycle race (accepted pilot debt)

**B1 (operator-adjudicated 2026-10-14, accepted and NOT fixed):** abort's
stale-lock cleanup branch (lock present, no rebase in progress) releases the
lock without distinguishing abandoned state from a live mid-admission `start`
(lock dir created, prerequisites still being rechecked), and the EXIT trap's
lock removal (`rm -rf` of the lock dir) is identity-less — it does not verify
the meta `instance_token` before deleting. Under concurrency this could let
one invocation destroy another's in-flight lock. **Both windows require a
second concurrent invocation — unreachable under the serialized
single-dispatcher protocol** ("CONCURRENCY: never run two bump-gate
invocations concurrently. The dispatcher (build) serializes all gate calls;
the operator hosts none"), which is stated as a hard rule in the skill, the
bumper agent contract, and the gate script header. Operator disposition
verbatim: "(b) Land now, document B1 as a pilot-known limitation, serialize
by contract; B1 fix becomes a Route-A promotion blocker on the existing
card." The fix scope (operation mutex + pid-liveness stale-lock distinction
+ identity-checked trap release + interleaving regressions) lives on card
`bump-gate-b1-lock-lifecycle` (`.local/coordinator/tasks/`) and is a **Route-A
promotion blocker** — the pilot does not promote to harness core while B1 is
open.

## Selection

Already selected in `.vh-agent-harness/vh-harness-profile.yml` under
`overlays:`. After editing this pack, run `vh-agent-harness update` and
restart opencode (agent registry is cached per-process).

## Pilot lifecycle

Route B now (project-owned script + overlay agent). Promotion to Route A
(harness core) is decided after 1-2 clean bumps. First real validation: the
pending 12.10.6 bump.

## Known collision note

`Tools/` is an upstream directory (release helper scripts). `bump-gate.sh` is
fork infra living there deliberately (operator-facing tooling location), and
it is registered in `docs/planning/local-changes.md` as fork infra with an
upstream-collision note by the same landing (the registry row commit lands
immediately after this pack's commit) — if upstream ever ships a
`Tools/bump-gate.sh`, keep ours.
