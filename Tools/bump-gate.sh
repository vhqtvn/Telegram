#!/usr/bin/env bash
# bump-gate.sh — Gated upstream-rebase wrapper for the Telegram fork (Route B pilot)
#
# Purpose
#   A gate-style wrapper around upstream release rebases, mirroring the idioms of
#   .opencode/scripts/commit-gate.sh (mkdir-based lock, JSON status output lines,
#   set -euo pipefail hygiene, logged state transitions). The bump pilot routes
#   ALL history mutation during an upstream bump through this script's fixed
#   verbs; the bumper agent (overlay pack bump-pilot) is its sole agent caller.
#
# Safety contract
#   - argv validation FIRST: only the fixed verbs plan|start|continue|abort|
#     status|verify with fixed argument shapes are accepted; anything else is a
#     usage error (exit 2). Caller-supplied tokens are NEVER forwarded to git —
#     upstream refs are validated against a strict charset and RECONSTRUCTED as
#     full refs/remotes/upstream/<name> before use. (Anti-mutation-oracle
#     invariant: shell-guard inspects command strings, never script bodies, so
#     this script must be its own strict gate.)
#   - start/continue/abort are the ONLY history-mutating verbs, and they run
#     exactly `git rebase <validated-ref>` / `git rebase --continue` /
#     `git rebase --abort` (+ `git update-ref` for the backup ref, and `git add`
#     restricted to the conflicted-path list). NEVER `git add -A` — the 12.10.3
#     bump swept a stale tlottie submodule pin into a commit via broad staging.
#   - SKIP_COMMIT_GATE is refused: this gate has no bypass mode, for anyone
#     (guarded uniformly in start, continue, and abort).
#   - start refuses a dirty tracked tree, an in-progress rebase, a held bump
#     lock, an existing backup ref, and a detached HEAD — every prerequisite
#     is rechecked UNDER the lock (the mkdir is the single admission point),
#     and the backup ref is created by expected-absent compare-and-swap
#     update-ref: an existing backup is PRESERVED, never overwritten
#     (round-7 b-F1).
#   - abort only touches a rebase this gate OWNS (bump lock present); a
#     rebase in progress without a lock is a FOREIGN rebase (e.g. the
#     operator's own) and is refused — never destroy work you don't own.
#     continue and abort BOTH verify rebase identity via a shared validator:
#     the on-disk rebase's orig-head AND onto must positively match the
#     lock meta's head_at_start + onto_commit (binds the rebase INSTANCE,
#     not just the starting commit); unestablishable identity refuses too.
#   - Instance-token binding (round-6): when the gate's own rebase stops,
#     start/continue write the lock's random instance token (meta schema
#     bump-gate-lock-v3) into <git-dir>/rebase-merge/bump-gate-owner, and
#     the shared validator ALSO requires that file + token equality. A
#     host-side `git rebase --abort` deletes rebase-merge wholesale, so a
#     replacement rebase — even with identical orig-head + onto — cannot
#     present the token and is refused (same-input replacement window
#     closed on BOTH continue and abort paths). Token WRITE failure (e.g.
#     read-only gitdir) fails the verb closed with reason token_write_failed.
#   - A backup ref refs/heads/pre-bump-backup is created at start and KEPT on
#     every exit path (operator-prunable; it is the recovery anchor after a
#     completed-but-unverified bump).
#   - Lock: <git-dir>/bump-gate.lock/ (mkdir-atomic, meta JSON inside; schema
#     bump-gate-lock-v3 carries the per-bump instance_token). The
#     lock PERSISTS across the conflict lifecycle (start-exit-on-conflict keeps
#     it so continue/abort can read the upstream ref); a flag-guarded EXIT trap
#     releases it exactly on terminal paths (verified completion, final
#     completion, abort). status is lock-free and harmless.
#   - plan and status perform NO ref/worktree writes. plan's `git merge-tree
#     --write-tree` writes disposable tree objects to the object DB only.
#   - CONCURRENCY: never run two bump-gate invocations concurrently. The
#     dispatcher (build) serializes all gate calls; the operator hosts none.
#     The B1 lock-lifecycle race is accepted pilot debt under this contract
#     (card bump-gate-b1-lock-lifecycle) and is a Route-A promotion blocker.
#     B1, accepted and NOT fixed here (operator adjudication 2026-10-14,
#     "(b) Land now, document B1 as a pilot-known limitation, serialize by
#     contract; B1 fix becomes a Route-A promotion blocker on the existing
#     card"): abort's stale-lock cleanup branch can race a live mid-admission
#     start (lock dir created, prerequisites not yet rechecked), and the EXIT
#     trap's `rm -rf "$LOCK_DIR"` is identity-less (it does not verify the
#     meta instance_token before removing). Both windows REQUIRE a second
#     concurrent invocation; under the serialized single-dispatcher protocol
#     they are unreachable. The fix (operation mutex + pid-liveness
#     distinction + identity-checked release) is scoped on the card above and
#     blocks Route-A promotion.
#
# Exit codes
#   0 success · 1 runtime error · 2 usage/argv error · 3 rebase stopped on
#   conflicts (resolution expected, then `continue`) · 4 verify failed.
#
# Pilot status
#   Route B (project-owned script + overlay agent), pilot since 2026-10-13.
#   First real validation = the pending 12.10.6 bump run through the bumper
#   agent after the required opencode restart. Promotion to Route A (harness
#   core) is decided after 1-2 clean bumps.
#
# Owner
#   .vh-agent-harness/overlays/bump-pilot/ (agents/bumper.md is the agent
#   contract; skills/bump/SKILL.md is the coordinator choreography).
set -euo pipefail

# ---------------------------------------------------------------------------
# Constants (git-dir resolved per invocation, worktree-safe — commit-gate idiom)
# ---------------------------------------------------------------------------
_GIT_DIR="$(git rev-parse --git-dir 2>/dev/null || echo ".git")"
LOCK_DIR="${_GIT_DIR}/bump-gate.lock"
LOCK_META="${LOCK_DIR}/meta"
LOG_FILE="${_GIT_DIR}/bump-gate.log"
BACKUP_REF="refs/heads/pre-bump-backup"
REBASE_OWNER_NAME="bump-gate-owner"   # instance-token file inside rebase-merge/
RELEASE_LOCK=0
_PLAN_TMP=""   # plan scratch path; reclaimed on abnormal exits by the trap

trap '_on_exit' EXIT
_on_exit() {
  local rc=$?
  [[ -n "$_PLAN_TMP" && -f "$_PLAN_TMP" ]] && rm -f "$_PLAN_TMP"
  if [[ "$RELEASE_LOCK" == 1 && -d "$LOCK_DIR" ]]; then
    rm -rf "$LOCK_DIR"
  fi
  exit "$rc"
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
json_out() { printf '%s\n' "$1"; }

_json_escape() {
  # JSON string escaping. Control characters (newline/CR/tab) MUST be escaped:
  # verify failure details are multiline by nature (gitlink diffs, conflict-
  # marker output), and literal newlines inside a JSON string would corrupt
  # the one-JSON-line-per-event contract exactly on the paths a dispatcher
  # must parse (reviewer F2).
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//$'\n'/\\n}"
  s="${s//$'\r'/\\r}"
  s="${s//$'\t'/\\t}"
  printf '%s' "$s"
}

log_event() {
  # Append one JSONL state-transition record under the git dir (never committed).
  # $1=verb $2=status; rest are key=value pairs appended verbatim — callers
  # must pass pre-quoted JSON fragments.
  local verb="$1" status="$2"; shift 2
  local ts
  ts="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  printf '{"ts":"%s","verb":"%s","status":"%s"%s}\n' \
    "$ts" "$verb" "$status" "${*:+,$*}" >> "$LOG_FILE" 2>/dev/null || true
}

usage() {
  cat >&2 <<'USAGE'
Usage: Tools/bump-gate.sh <verb> [args]

Verbs:
  plan     <upstream-ref>   Predict the rebase's conflict set (read-only; no
                            ref/worktree writes). <upstream-ref> must resolve
                            under refs/remotes/upstream/* — short form
                            "upstream/master" or full "refs/remotes/upstream/master".
  start    <upstream-ref>   Refuse dirty tree / in-progress rebase / held lock /
                            existing backup ref / detached HEAD; create backup
                            ref refs/heads/pre-bump-backup; run git rebase.
  continue                  Stage EXACTLY the conflicted paths (never -A), then
                            git rebase --continue; verify + release on completion.
  abort                     git rebase --abort (returns to pre-rebase state);
                            release lock; KEEP the backup ref.
  status                    Lock-free state report (rebase in progress? lock?
                            backup ref? HEAD).
  verify   <upstream-ref>   Post-invariants: (a) no leftover conflict markers in
                            the local series' diff, (b) submodule gitlink parity
                            with upstream, (c) clean working tree.

Exit codes: 0 ok · 1 runtime error · 2 usage error · 3 conflicts (resolve, then
continue) · 4 verify failed.
USAGE
}

die_usage() {
  json_out "{\"verb\":\"$1\",\"status\":\"error\",\"reason\":\"usage_error\",\"detail\":\"$(_json_escape "$2")\"}"
  exit 2
}

die() {
  local verb="$1" reason="$2" detail="${3:-}"
  log_event "$verb" "error" "\"reason\":\"$(_json_escape "$reason")\""
  json_out "{\"verb\":\"$verb\",\"status\":\"error\",\"reason\":\"$(_json_escape "$reason")\"${detail:+,\"detail\":\"$(_json_escape "$detail")\"}}"
  exit 1
}

# Resolve a caller-supplied upstream ref token into a FULL validated ref.
# Accepts "upstream/<name>" or "refs/remotes/upstream/<name>". The raw token is
# NEVER forwarded to git — only the reconstructed, charset-validated full ref.
# Echoes the full ref on success; returns 1 on any rejection.
resolve_upstream_ref() {
  local raw="$1" name full
  if [[ "$raw" == refs/remotes/upstream/* ]]; then
    name="${raw#refs/remotes/upstream/}"
  elif [[ "$raw" == upstream/* ]]; then
    name="${raw#upstream/}"
  else
    return 1
  fi
  # Strict charset + shape guards (refname components; blocks traversal-ish and
  # option-looking tokens before they ever reach a git command line).
  [[ -n "$name" ]] || return 1
  [[ "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._/-]*$ ]] || return 1
  [[ "$name" == *..* ]] && return 1
  [[ "$name" == *//* ]] && return 1
  [[ "$name" == /* || "$name" == */ || "$name" == *.lock ]] && return 1
  full="refs/remotes/upstream/${name}"
  git rev-parse --verify --quiet "${full}^{commit}" >/dev/null 2>&1 || return 1
  printf '%s' "$full"
}

rebase_in_progress() {
  local gd
  gd="$(git rev-parse --git-dir 2>/dev/null || echo ".git")"
  [[ -d "${gd}/rebase-merge" || -d "${gd}/rebase-apply" ]]
}

current_branch() {
  git symbolic-ref -q --short HEAD 2>/dev/null || printf ''
}

dirty_tracked() {
  # Submodule workdir dirt is ignored: it does not block a rebase checkout and
  # is refreshed by the mandatory post-bump `git submodule update --init --recursive`.
  [[ -n "$(git status --porcelain --untracked-files=no --ignore-submodules=all 2>/dev/null)" ]]
}

conflicted_paths() {
  # Unmerged entries only (diff-filter=U); -z is space/quote-safe.
  git diff --name-only --diff-filter=U -z 2>/dev/null | tr '\0' '\n' | sed '/^$/d'
}

lock_meta_read() {
  [[ -f "$LOCK_META" ]] && cat "$LOCK_META" 2>/dev/null || printf '{}'
}

# 128-bit random instance token (32 hex chars) from /dev/urandom. Generated
# once per gated bump at start; carried in lock meta (schema v3) and written
# into the live rebase state dir at every gate-rebase stop.
_instance_token_new() {
  od -An -N16 -tx1 /dev/urandom 2>/dev/null | tr -d ' \n'
}

# Bind the STOPPED rebase to this gate: write the lock meta's instance token
# into <git-dir>/rebase-merge/<REBASE_OWNER_NAME> (round-6). Called by
# start/continue immediately after the GATE's own rebase stops (conflicts or
# pause), BEFORE the conflict set is advertised, so any later continue/abort
# positively proves the rebase-merge dir was not replaced (host abort +
# re-rebase) — see _gate_owns_rebase stage 0. Fail-closed: if the token
# cannot be written the verb dies (never advertise an unbindable rebase);
# the lock is intentionally KEPT so `abort` can still release it after a
# host-side recovery.
_write_rebase_owner_token() {
  local verb="$1" token rmd
  rmd="${_GIT_DIR}/rebase-merge"
  token="$(lock_meta_read | sed -n 's/.*"instance_token":"\([^"]*\)".*/\1/p')"
  if [[ ! -d "$rmd" ]]; then
    log_event "$verb" token_write_failed "\"cause\":\"no_rebase_merge_dir\""
    die "$verb" token_write_failed "rebase stopped but ${rmd} is absent (apply-backend or foreign rebase?) — this gate only manages merge-backend rebases; host recovery: git rebase --abort, then Tools/bump-gate.sh abort"
  fi
  if [[ -z "$token" ]]; then
    log_event "$verb" token_write_failed "\"cause\":\"meta_missing_token\""
    die "$verb" token_write_failed "lock meta carries no instance_token — refusing to advertise an unbindable rebase; host recovery: git rebase --abort, then Tools/bump-gate.sh abort"
  fi
  if ! printf '%s\n' "$token" > "${rmd}/${REBASE_OWNER_NAME}" 2>/dev/null; then
    log_event "$verb" token_write_failed "\"cause\":\"write_error\",\"path\":\"${rmd}/${REBASE_OWNER_NAME}\""
    die "$verb" token_write_failed "cannot write ${rmd}/${REBASE_OWNER_NAME} (read-only git dir/file?) — fail closed; host recovery: fix permissions or git rebase --abort, then Tools/bump-gate.sh abort"
  fi
  log_event "$verb" owner_token_written
}

# Shared gate-ownership validator (round-4 b-F1 + round-5 b-F1/b-F2 + round-6
# instance token): the in-progress rebase is owned by this gate iff ALL hold:
#   (0) TOKEN — <git-dir>/rebase-merge exists AND carries the owner file
#       (bump-gate-owner) whose token equals the lock meta's instance_token
#       (written by start/continue when the GATE's own rebase stopped). A
#       host-side `git rebase --abort` deletes rebase-merge wholesale; any
#       replacement rebase — even with identical orig-head AND onto —
#       recreates the dir WITHOUT the token → refused. Closes the
#       same-input replacement window on BOTH continue and abort paths.
#   (1) IDENTITY — orig-head equals the lock meta's head_at_start AND onto
#       equals the lock meta's onto_commit (recorded at start as the
#       resolved upstream tip); binding only the starting commit is
#       insufficient (operator host-aborts then rebases a different series).
# Identity must be POSITIVELY established on the mutation paths
# (continue/abort): any absent or unparseable signal → refuse (fail-closed;
# normal merge-backend git always writes orig-head and onto, so
# unestablishable means anomaly). Emits refusal JSON + log and returns 1 on
# any failure. PRECONDITION: caller has confirmed a rebase is in progress
# AND the lock dir exists (this validator does not re-check either).
_gate_owns_rebase() {
  local verb="$1"
  local meta meta_head meta_onto meta_token disk_token="" orig_head="" onto_head=""
  local rmd="${_GIT_DIR}/rebase-merge"
  # Stage 0 — instance token (round-6). Checked FIRST: a replacement rebase
  # with identical inputs would pass the identity stage below but cannot
  # present this gate's token.
  if [[ ! -d "$rmd" ]]; then
    log_event "$verb" refused_not_gate_owned "\"cause\":\"no_rebase_merge_dir\""
    json_out "{\"verb\":\"$verb\",\"status\":\"refused\",\"reason\":\"not_gate_owned\",\"detail\":\"no ${rmd} directory (apply-backend or foreign rebase) — this gate only manages its own merge-backend rebases; inspect from a host terminal\"}"
    return 1
  fi
  meta="$(lock_meta_read)"
  meta_token="$(printf '%s' "$meta" | sed -n 's/.*"instance_token":"\([^"]*\)".*/\1/p')"
  if [[ -f "${rmd}/${REBASE_OWNER_NAME}" ]]; then
    disk_token="$(tr -d ' \n\r' < "${rmd}/${REBASE_OWNER_NAME}" 2>/dev/null || printf '')"
  fi
  if [[ -z "$meta_token" || -z "$disk_token" ]]; then
    local mt_state="MISSING" of_state="MISSING"
    [[ -n "$meta_token" ]] && mt_state="present"
    [[ -n "$disk_token" ]] && of_state="present"
    local tok_state="meta_token:${mt_state} owner_file:${of_state}"
    log_event "$verb" refused_not_gate_owned "\"tok_state\":\"$(_json_escape "$tok_state")\""
    json_out "{\"verb\":\"$verb\",\"status\":\"refused\",\"reason\":\"not_gate_owned\",\"detail\":\"gate owner token not established (${tok_state}) — a rebase-merge dir created outside this gate (e.g. host aborted the gate's rebase then started another, even with identical inputs) cannot present this gate's token; resolve or abort that rebase from a host terminal\"}"
    return 1
  fi
  if [[ "$disk_token" != "$meta_token" ]]; then
    log_event "$verb" refused_rebase_token_mismatch
    json_out "{\"verb\":\"$verb\",\"status\":\"refused\",\"reason\":\"rebase_token_mismatch\",\"detail\":\"the in-progress rebase carries a different owner token than this gate's lock — a foreign rebase or another gate instance; inspect from a host terminal\"}"
    return 1
  fi
  # Stage 1 — instance identity: orig-head + onto both positively match the
  # lock meta (stage 0 already guaranteed the merge-backend dir exists).
  meta_head="$(printf '%s' "$meta" | sed -n 's/.*"head_at_start":"\([^"]*\)".*/\1/p')"
  meta_onto="$(printf '%s' "$meta" | sed -n 's/.*"onto_commit":"\([^"]*\)".*/\1/p')"
  if [[ -f "${rmd}/orig-head" ]]; then
    orig_head="$(git rev-parse --verify "$(cat "${rmd}/orig-head")^{commit}" 2>/dev/null || printf '')"
    if [[ -f "${rmd}/onto" ]]; then
      onto_head="$(git rev-parse --verify "$(cat "${rmd}/onto")^{commit}" 2>/dev/null || printf '')"
    fi
  fi
  if [[ -z "$meta_head" || -z "$meta_onto" || -z "$orig_head" || -z "$onto_head" ]]; then
    log_event "$verb" refused_identity_unestablished "\"meta_head\":\"$(_json_escape "$meta_head")\",\"meta_onto\":\"$(_json_escape "$meta_onto")\",\"orig_head\":\"$(_json_escape "$orig_head")\",\"onto\":\"$(_json_escape "$onto_head")\""
    json_out "{\"verb\":\"$verb\",\"status\":\"refused\",\"reason\":\"identity_unestablished\",\"detail\":\"rebase identity could not be positively established (absent or unparseable orig-head/onto or lock meta fields) — inspect .git/rebase-merge state from a host terminal before resuming\"}"
    return 1
  fi
  if [[ "$meta_head" != "$orig_head" || "$meta_onto" != "$onto_head" ]]; then
    log_event "$verb" refused_rebase_identity_mismatch "\"meta\":\"$(_json_escape "$meta_head")\",\"orig_head\":\"$(_json_escape "$orig_head")\",\"meta_onto\":\"$(_json_escape "$meta_onto")\",\"onto\":\"$(_json_escape "$onto_head")\""
    json_out "{\"verb\":\"$verb\",\"status\":\"refused\",\"reason\":\"rebase_identity_mismatch\",\"detail\":\"the in-progress rebase is a different instance than the one this gate's lock recorded (orig-head or onto differs) — likely a foreign rebase; inspect .git/rebase-merge state from a host terminal\"}"
    return 1
  fi
  return 0
}

# ---------------------------------------------------------------------------
# Verb: plan <upstream-ref> — read-only conflict prediction
# ---------------------------------------------------------------------------
cmd_plan() {
  local ref="$1"
  local head base local_count drift
  head="$(git rev-parse --verify "HEAD^{commit}")" || die plan head_resolve_failed
  base="$(git merge-base "$head" "$ref")" || die plan merge_base_failed "no common ancestor with ${ref}"
  local_count="$(git rev-list --count "${base}..${head}")"
  drift="$(git rev-list --count "${base}..${ref}")"

  if [[ "$base" == "$(git rev-parse "$ref")" ]]; then
    json_out "{\"verb\":\"plan\",\"status\":\"up_to_date\",\"ref\":\"$ref\",\"local_commits\":${local_count},\"upstream_drift\":${drift},\"predicted_conflicts\":0,\"files\":[]}"
    log_event plan up_to_date "\"ref\":\"$ref\""
    return 0
  fi

  # Rebase-simulation: merge-tree with --merge-base anchored at the fork point,
  # ours=<upstream ref>, theirs=<local HEAD> — the same ours/theirs orientation
  # `git rebase` uses when replaying local commits onto upstream.
  # HONEST APPROXIMATION (documented divergence): merge-tree computes ONE merge
  # of the cumulative diffs; rebase REPLAYS each local commit separately against
  # the upstream tip, so a rebase can stop on conflicts merge-tree cannot
  # predict (e.g. a local commit conflicting with upstream state that a later
  # local commit would have fixed, or vice versa). Treat plan output as a floor,
  # not a guarantee. --write-tree writes disposable tree objects to the object
  # DB; no refs or worktree paths are touched. Output is parsed NUL-delimited
  # (-z) through a scratch file under the git dir — bash variables cannot hold
  # NUL bytes. Section layout (empirically probed on git 2.43):
  # <tree-oid>NUL<conflicted-file>NUL...NUL(empty separator)NUL<message
  # records...>. The loop BREAKS at the empty separator token so the
  # informational-messages section is never miscounted as conflicted paths
  # (reviewer round-1 F11/F15 + round-2 c-F1). The scratch path is tracked in
  # _PLAN_TMP and reclaimed by the EXIT trap too (abnormal-kill residue).
  _PLAN_TMP="${_GIT_DIR}/bump-gate-plan-$$.tmp"
  local mt_rc=0
  git merge-tree --write-tree --name-only -z --merge-base="$base" "$ref" "$head" > "$_PLAN_TMP" 2>/dev/null || mt_rc=$?
  if [[ $mt_rc -gt 1 ]]; then
    rm -f "$_PLAN_TMP"; _PLAN_TMP=""
    die plan merge_tree_failed "exit ${mt_rc}"
  fi
  local tree_oid="" tok files_json="[" first=1 n=0
  while IFS= read -r -d '' tok; do
    if [[ -z "$tree_oid" ]]; then
      tree_oid="$tok"   # first NUL-delimited token is the written tree OID
      continue
    fi
    if [[ -z "$tok" ]]; then
      break             # empty separator token: informational records follow
    fi
    [[ $first == 1 ]] || files_json+=","
    files_json+="\"$(_json_escape "$tok")\""
    first=0
    n=$((n+1))
  done < "$_PLAN_TMP"
  rm -f "$_PLAN_TMP"; _PLAN_TMP=""
  files_json+="]"

  if [[ $mt_rc -eq 0 ]]; then
    json_out "{\"verb\":\"plan\",\"status\":\"clean\",\"ref\":\"$ref\",\"head\":\"$head\",\"merge_base\":\"$base\",\"simulated_result_tree\":\"$tree_oid\",\"local_commits\":${local_count},\"upstream_drift\":${drift},\"predicted_conflicts\":0,\"files\":[],\"note\":\"merge-tree simulation: single cumulative merge; rebase replays per-commit and may surface richer conflict sets\"}"
  else
    json_out "{\"verb\":\"plan\",\"status\":\"conflicts_predicted\",\"ref\":\"$ref\",\"head\":\"$head\",\"merge_base\":\"$base\",\"simulated_result_tree\":\"$tree_oid\",\"local_commits\":${local_count},\"upstream_drift\":${drift},\"predicted_conflicts\":${n},\"files\":${files_json},\"note\":\"merge-tree simulation: single cumulative merge; rebase replays per-commit and may surface richer conflict sets\"}"
  fi
  log_event plan "$([[ $mt_rc -eq 0 ]] && echo clean || echo conflicts_predicted)" "\"ref\":\"$ref\",\"predicted\":${n}"
}

# ---------------------------------------------------------------------------
# Verb: start <upstream-ref>
# ---------------------------------------------------------------------------
cmd_start() {
  local ref="$1"
  if [[ -n "${SKIP_COMMIT_GATE:-}" ]]; then
    die start skip_refused "this gate has no bypass; unset SKIP_COMMIT_GATE"
  fi
  # Fast prechecks (no state mutated yet; friendly early refusals). The
  # authoritative rechecks run UNDER the lock below — the lock mkdir is the
  # single admission point, so no shared state read before it is trusted
  # (round-7 b-F1: pre-lock checks + unconditional update-ref let a paused
  # second start overwrite a completed bump's retained backup ref; the
  # under-lock recheck + expected-absent CAS close that interleave).
  rebase_in_progress && die start rebase_in_progress "resolve or abort the current rebase first (continue/abort)"
  if [[ -d "$LOCK_DIR" ]]; then
    die start lock_contended "holder meta: $(lock_meta_read)"
  fi
  dirty_tracked && die start dirty_tree "commit or stash tracked changes before a bump"
  if git show-ref --verify --quiet "$BACKUP_REF"; then
    die start backup_ref_exists "refs/heads/pre-bump-backup already exists — run abort after the current bump, or (outside any bump) operator: git branch -D pre-bump-backup"
  fi
  [[ -n "$(current_branch)" ]] || die start detached_head "bumps operate on a branch, not a detached HEAD"
  local onto_commit instance_token
  onto_commit="$(git rev-parse --verify "$ref^{commit}")" || die start onto_resolve_failed
  # instance_token: fresh 128-bit token per gated bump (round-6). Generated
  # BEFORE the lock is created so a generation failure strands no state.
  instance_token="$(_instance_token_new || true)"
  [[ "$instance_token" =~ ^[0-9a-f]{32}$ ]] || die start token_gen_failed "cannot generate a 128-bit instance token (/dev/urandom unreadable?)"

  # ── Admission: the mkdir IS the arbiter. Everything below runs under the
  # lock, and every prerequisite is RECHECKED here so state changed by a
  # concurrent invocation between precheck and acquisition refuses this
  # start (each refusal releases the lock via the EXIT trap).
  mkdir "$LOCK_DIR" || die start lock_create_failed "$LOCK_DIR"
  rebase_in_progress && { RELEASE_LOCK=1; die start rebase_in_progress "a rebase appeared while acquiring the lock — resolve or abort it first (continue/abort)"; }
  dirty_tracked && { RELEASE_LOCK=1; die start dirty_tree "tree went dirty while acquiring the lock — commit or stash tracked changes before a bump"; }
  if git show-ref --verify --quiet "$BACKUP_REF"; then
    RELEASE_LOCK=1; die start backup_ref_exists "refs/heads/pre-bump-backup appeared while acquiring the lock — existing backup preserved; run abort after the current bump, or (outside any bump) operator: git branch -D pre-bump-backup"
  fi
  local branch head
  branch="$(current_branch)"
  [[ -z "$branch" ]] && { RELEASE_LOCK=1; die start detached_head "HEAD detached while acquiring the lock — bumps operate on a branch"; }
  head="$(git rev-parse --verify "HEAD^{commit}")" || { RELEASE_LOCK=1; die start head_resolve_failed; }

  # Meta schema v3 = v2 + instance_token. onto_commit records the rebase
  # INSTANCE target (the resolved upstream tip) — together with head_at_start
  # it lets _gate_owns_rebase bind the on-disk rebase (orig-head + onto) to
  # THIS gate's rebase, not merely to the same starting commit (round-5
  # b-F1/b-F2); instance_token additionally binds the rebase-merge STATE DIR
  # itself (round-6). head/branch are captured UNDER the lock (round-7).
  printf '{"schema":"bump-gate-lock-v3","verb":"start","upstream_ref":"%s","onto_commit":"%s","branch":"%s","head_at_start":"%s","started_at":"%s","pid":%s,"instance_token":"%s"}\n' \
    "$ref" "$onto_commit" "$branch" "$head" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$$" "$instance_token" > "$LOCK_META"
  log_event start acquired "\"ref\":\"$ref\",\"head\":\"$head\",\"branch\":\"$branch\""

  # Backup ref via expected-ABSENT compare-and-swap (empty <oldvalue> = the
  # ref must not exist): a backup that appeared since the recheck is
  # PRESERVED, never overwritten — a completed bump's recovery anchor is
  # inviolable (round-7 b-F1).
  if ! git update-ref "$BACKUP_REF" "$head" ""; then
    RELEASE_LOCK=1
    die start backup_ref_exists "expected-absent update-ref refused: refs/heads/pre-bump-backup exists (appeared concurrently) — existing backup PRESERVED; run abort after the current bump, or (outside any bump) operator: git branch -D pre-bump-backup"
  fi
  log_event start backup_ref_created "\"ref\":\"$BACKUP_REF\",\"at\":\"$head\""

  # Not exec(3): the wrapper must survive the rebase to report the conflict set
  # and manage the lock on every exit path.
  local rc=0
  GIT_EDITOR=true git rebase "$ref" || rc=$?
  if [[ $rc -eq 0 ]]; then
    _finish_after_rebase start "$ref"
    return $?
  fi
  # Rebase stopped: bind the stopped rebase to this gate (instance token)
  # BEFORE advertising the conflict set — continue/abort will require it.
  _write_rebase_owner_token start
  # Either unmerged paths exist (exit 3, resolution expected) or it stopped
  # without a conflict set (unexpected — exit 1).
  local csrc=0
  _report_conflict_state start "$ref" || csrc=$?
  [[ $csrc -eq 0 ]] && csrc=1
  exit "$csrc"
}

# Report the current unmerged set (shared by start/continue conflict paths).
# Returns 3 (conflicts, resolution expected) or 1 (stopped without unmerged
# paths); never returns 0.
_report_conflict_state() {
  local verb="$1" ref="$2"
  local files_json="[" first=1 n=0 f
  while IFS= read -r f; do
    [[ -z "$f" ]] && continue
    [[ $first == 1 ]] || files_json+=","
    files_json+="\"$(_json_escape "$f")\""
    first=0
    n=$((n+1))
  done < <(conflicted_paths)
  files_json+="]"
  if [[ $n -gt 0 ]]; then
    log_event "$verb" conflicts "\"ref\":\"$ref\",\"count\":${n}"
    json_out "{\"verb\":\"$verb\",\"status\":\"conflicts\",\"ref\":\"$ref\",\"conflicts\":${n},\"files\":${files_json},\"next\":\"resolve files, then Tools/bump-gate.sh continue\"}"
    return 3
  fi
  log_event "$verb" stopped_no_unmerged "\"ref\":\"$ref\""
  json_out "{\"verb\":\"$verb\",\"status\":\"error\",\"reason\":\"stopped_without_unmerged_paths\",\"ref\":\"$ref\",\"next\":\"inspect .git/rebase-merge state; Tools/bump-gate.sh abort to recover\"}"
  return 1
}

# Shared completion path: verify invariants, release lock, keep backup ref.
# RELEASE_LOCK is armed BEFORE cmd_verify (reviewer F9): a die() inside verify
# must not leave the lock stranded — at completion time the rebase is over and
# the lock has nothing further to coordinate.
_finish_after_rebase() {
  local verb="$1" ref="$2"
  local new_head
  new_head="$(git rev-parse --verify "HEAD^{commit}")"
  RELEASE_LOCK=1
  local vrc=0
  cmd_verify "$ref" || vrc=$?
  if [[ $vrc -eq 0 ]]; then
    log_event "$verb" completed_verified "\"ref\":\"$ref\",\"head\":\"$new_head\""
    json_out "{\"verb\":\"$verb\",\"status\":\"completed_verified\",\"ref\":\"$ref\",\"head\":\"$new_head\",\"backup_ref\":\"$BACKUP_REF\"}"
    return 0
  fi
  log_event "$verb" completed_verify_failed "\"ref\":\"$ref\",\"head\":\"$new_head\""
  json_out "{\"verb\":\"$verb\",\"status\":\"completed_verify_failed\",\"ref\":\"$ref\",\"head\":\"$new_head\",\"backup_ref\":\"$BACKUP_REF\",\"next\":\"fix forward or operator: git reset --hard pre-bump-backup (host terminal)\"}"
  return 4
}

# ---------------------------------------------------------------------------
# Verb: continue
# ---------------------------------------------------------------------------
cmd_continue() {
  if [[ -n "${SKIP_COMMIT_GATE:-}" ]]; then
    die continue skip_refused "this gate has no bypass; unset SKIP_COMMIT_GATE"
  fi
  [[ -d "$LOCK_DIR" ]] || die continue no_lock "no gated bump in progress (lock absent)"
  rebase_in_progress || die continue no_rebase "lock exists but no rebase is in progress — stale lock; run abort to clean up"
  local ref refcheck
  ref="$(lock_meta_read | sed -n 's/.*"upstream_ref":"\([^"]*\)".*/\1/p')"
  # Re-validate the recovered ref through the same resolver (reviewer F19):
  # lock meta is a file we wrote, but validating costs nothing and keeps a
  # single admission path for every ref that reaches git. Pass the FULL ref —
  # the resolver accepts refs/remotes/upstream/* verbatim and echoes it back
  # (round-2 b-F1: stripping the prefix fed it a bare name the resolver
  # rejects, which deterministically killed every valid continue).
  refcheck="$(resolve_upstream_ref "$ref" || true)"
  [[ -n "$refcheck" && "$refcheck" == "$ref" ]] || die continue lock_meta_bad "upstream_ref in lock meta failed re-validation; run abort and restart the bump"
  # Ownership (round-4 b-F1): a rebase in progress under a valid lock may
  # still be FOREIGN (operator interleave after the gate's bump stopped) —
  # verify rebase identity against the lock meta BEFORE staging anything.
  _gate_owns_rebase continue || exit 1

  # Stage EXACTLY the unmerged paths — NEVER `git add -A` (12.10.3 lesson: a
  # broad stage swept a stale tlottie submodule pin into a commit).
  local staged=0 f
  while IFS= read -r -d '' f; do
    [[ -z "$f" ]] && continue
    git add -- "$f"
    staged=$((staged+1))
  done < <(git diff --name-only --diff-filter=U -z)
  log_event continue staged "\"paths\":${staged}"

  local rc=0
  GIT_EDITOR=true git rebase --continue || rc=$?
  if [[ $rc -eq 0 ]] && ! rebase_in_progress; then
    _finish_after_rebase continue "$ref"
    return $?
  fi
  if [[ $rc -ne 0 ]] && rebase_in_progress; then
    # Stopped again: re-bind the rebase to this gate (idempotent token
    # refresh) before reporting — then either another conflict set (exit 3)
    # or an unexpected stop.
    _write_rebase_owner_token continue
    local csrc=0
    _report_conflict_state continue "$ref" || csrc=$?
    [[ $csrc -eq 0 ]] && csrc=1
    exit "$csrc"
  fi
  die continue rebase_failed "git rebase --continue exited ${rc}"
}

# ---------------------------------------------------------------------------
# Verb: abort — git rebase --abort only; NO destructive reset; keep backup ref
# ---------------------------------------------------------------------------
cmd_abort() {
  if [[ -n "${SKIP_COMMIT_GATE:-}" ]]; then
    die abort skip_refused "this gate has no bypass; unset SKIP_COMMIT_GATE"
  fi
  # Ownership (reviewer F1/F3 + round-2 d-F1): a rebase in progress is only
  # abortable through this gate when THIS gate owns it — lock present (a
  # rebase without a bump lock belongs to someone else, e.g. an operator's
  # own rebase with in-flight conflict resolutions — refuse; never destroy
  # work you don't own) AND rebase identity confirmed by the shared
  # _gate_owns_rebase validator (a stale lock coexisting with a FOREIGN
  # rebase must not abort that rebase).
  local did_abort=no
  if rebase_in_progress; then
    if [[ ! -d "$LOCK_DIR" ]]; then
      log_event abort refused_foreign_rebase
      json_out "{\"verb\":\"abort\",\"status\":\"refused\",\"reason\":\"foreign_rebase\",\"detail\":\"a rebase is in progress but no bump-gate lock exists — not this gate's rebase; resolve or abort it directly from a host terminal\"}"
      return 1
    fi
    local rc=0
    if ! _gate_owns_rebase abort; then
      return 1
    fi
    git rebase --abort || rc=$?
    if [[ $rc -ne 0 ]]; then
      die abort rebase_abort_failed "git rebase --abort exited ${rc}"
    fi
    did_abort=yes
  elif [[ -d "$LOCK_DIR" ]]; then
    # Stale lock, no rebase: cleanup-only branch (cannot abort anything).
    log_event abort stale_lock_cleanup
  fi
  local head note
  head="$(git rev-parse --verify "HEAD^{commit}")"
  if [[ "$did_abort" == "yes" ]]; then
    note="git rebase --abort returned the repo to its pre-rebase state; backup ref kept (operator-prunable)"
  else
    note="no rebase was in progress; stale lock (if any) released; backup ref kept (operator-prunable)"
  fi
  RELEASE_LOCK=1
  log_event abort aborted "\"head\":\"$head\",\"aborted_rebase\":\"$did_abort\""
  json_out "{\"verb\":\"abort\",\"status\":\"aborted\",\"aborted_rebase\":\"$did_abort\",\"head\":\"$head\",\"backup_ref\":\"$BACKUP_REF\",\"note\":\"$note\"}"
}

# ---------------------------------------------------------------------------
# Verb: status — lock-free, harmless
# ---------------------------------------------------------------------------
cmd_status() {
  local branch head rebase=no lock=no backup=no
  branch="$(current_branch)"
  branch="${branch:-detached-HEAD}"
  head="$(git rev-parse --verify "HEAD^{commit}" 2>/dev/null || printf '')"
  rebase_in_progress && rebase=yes
  [[ -d "$LOCK_DIR" ]] && lock=yes
  git show-ref --verify --quiet "$BACKUP_REF" && backup=yes
  local conflicts=0
  if [[ "$rebase" == "yes" ]]; then
    conflicts="$(conflicted_paths | sed '/^$/d' | wc -l | tr -d ' ')"
  fi
  # Owner-token presence diagnostic (round-6): distinguishes a gate-owned
  # stopped rebase from a replaced/foreign one when diagnosing refusals.
  local owner_file="n/a"
  if [[ "$rebase" == "yes" && -d "${_GIT_DIR}/rebase-merge" ]]; then
    if [[ -f "${_GIT_DIR}/rebase-merge/${REBASE_OWNER_NAME}" ]]; then
      owner_file=present
    else
      owner_file=absent
    fi
  fi
  json_out "{\"verb\":\"status\",\"status\":\"ok\",\"branch\":\"$branch\",\"head\":\"$head\",\"rebase_in_progress\":\"$rebase\",\"bump_lock\":\"$lock\",\"lock_meta\":\"$(_json_escape "$(lock_meta_read)")\",\"backup_ref\":\"$backup\",\"unmerged_paths\":${conflicts:-0},\"rebase_owner_token\":\"$owner_file\"}"
  log_event status ok
}

# ---------------------------------------------------------------------------
# Verb: verify <upstream-ref> — post-invariants
# ---------------------------------------------------------------------------
cmd_verify() {
  local ref="$1"
  local head base
  head="$(git rev-parse --verify "HEAD^{commit}")" || die verify head_resolve_failed
  base="$(git merge-base "$head" "$ref")" || die verify merge_base_failed

  local failed=0
  local fail_json=""

  # (a) Leftover conflict markers in the local series' diff.
  # `git diff --check` also flags whitespace problems; ONLY conflict markers
  # fail the invariant — whitespace notices are surfaced as warnings.
  local check_out markers="" warnings=""
  check_out="$(git diff --check "$base" "$head" 2>/dev/null || true)"
  if [[ -n "$check_out" ]]; then
    markers="$(printf '%s\n' "$check_out" | grep -F 'leftover conflict marker' || true)"
    warnings="$(printf '%s\n' "$check_out" | grep -vF 'leftover conflict marker' | sed '/^$/d' || true)"
  fi
  if [[ -n "$markers" ]]; then
    failed=1
    fail_json+="{\"check\":\"conflict_markers\",\"detail\":\"$(_json_escape "$(printf '%s' "$markers" | head -c 500)")\"}"
  fi

  # (b) Submodule gitlink parity: every gitlink (mode 160000) in HEAD must equal
  # upstream's at the same path, and no gitlink may exist on only one side.
  # The local series pins NO submodules (local-changes registry), so ANY
  # divergence is a rebase artifact (the mechanized 12.10.3 second defect:
  # a stale tlottie pin swept into a local commit).
  # ls-tree -r output: "<mode> <type> <oid>\t<path>" — with -F'\t' the oid is
  # the third space-field of $1 and the path is $2.
  local ours theirs mismatches
  ours="$(git ls-tree -r "$head" | awk -F'\t' '$1 ~ /^160000/ {split($1, a, " "); print a[3] " " $2}')"
  theirs="$(git ls-tree -r "$ref" | awk -F'\t' '$1 ~ /^160000/ {split($1, a, " "); print a[3] " " $2}')"
  mismatches="$(diff <(printf '%s\n' "$ours") <(printf '%s\n' "$theirs") || true)"
  if [[ -n "$(printf '%s' "$mismatches" | sed '/^$/d')" ]]; then
    failed=1
    [[ -n "$fail_json" ]] && fail_json+=","
    fail_json+="{\"check\":\"gitlink_parity\",\"detail\":\"$(_json_escape "$(printf '%s' "$mismatches" | head -c 800)")\"}"
  fi

  # (c) Clean working tree (tracked surface; submodule dirt refreshed by the
  # mandatory post-bump `git submodule update --init --recursive --depth=1`).
  if dirty_tracked; then
    failed=1
    [[ -n "$fail_json" ]] && fail_json+=","
    fail_json+="{\"check\":\"clean_tree\",\"detail\":\"$(_json_escape "$(git status --porcelain --untracked-files=no --ignore-submodules=all | head -c 500)")\"}"
  fi

  if [[ $failed -eq 1 ]]; then
    log_event verify failed "\"ref\":\"$ref\""
    json_out "{\"verb\":\"verify\",\"status\":\"failed\",\"ref\":\"$ref\",\"head\":\"$head\",\"failures\":[${fail_json}]}"
    return 4
  fi
  log_event verify passed "\"ref\":\"$ref\",\"head\":\"$head\""
  json_out "{\"verb\":\"verify\",\"status\":\"passed\",\"ref\":\"$ref\",\"head\":\"$head\"${warnings:+,\"warnings\":\"$(_json_escape "$(printf '%s' "$warnings" | head -c 300)")\"}}"
}

# ---------------------------------------------------------------------------
# main — argv validation FIRST (fixed verbs, fixed shapes, nothing forwarded)
# ---------------------------------------------------------------------------
main() {
  local verb="${1:-}"
  case "$verb" in
    plan|start|verify)
      [[ $# -eq 2 ]] || die_usage "$verb" "expected exactly one <upstream-ref> argument"
      local ref
      ref="$(resolve_upstream_ref "$2")" || die_usage "$verb" "ref must resolve under refs/remotes/upstream/* (got: $2)"
      case "$verb" in
        plan)   cmd_plan "$ref" ;;
        start)  cmd_start "$ref" ;;
        verify) cmd_verify "$ref" ;;
      esac
      ;;
    continue)
      [[ $# -eq 1 ]] || die_usage continue "takes no arguments"
      cmd_continue
      ;;
    abort)
      [[ $# -eq 1 ]] || die_usage abort "takes no arguments"
      cmd_abort
      ;;
    status)
      [[ $# -eq 1 ]] || die_usage status "takes no arguments"
      cmd_status
      ;;
    *)
      usage
      die_usage "${verb:-<none>}" "unknown verb"
      ;;
  esac
}

main "$@"
