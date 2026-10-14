<!-- bump-pilot/callable-graph-snippet.md — optional pack routing doc -->

## bump-pilot specialists

- **bumper** (gated upstream rebases): resolution authority for upstream
  release bumps — directs the `Tools/bump-gate.sh` verb sequence
  (dispatcher-executed; see the invocation-split note in skills/bump),
  resolves conflicts with the Edit tool guided by the local-changes registry +
  dispatcher cheat-sheet; never fetches, commits, or pushes.

### bump-pilot routing

- `build`, `coordination`, and `project-coordinator` may delegate to `bumper`
  (task allow, auto-injected from this pack's permission-pack.jsonc
  delegateFrom). `build` is the EXECUTING dispatcher (runs the gate verbs via
  `vh-agent-harness exec Tools/bump-gate.sh …`); `coordination` /
  `project-coordinator` route and decide but do not execute gate verbs.
- `bumper` has NO outbound task edges: it reports back to its dispatcher in
  gate-JSON style; review routing (R5 gate, registry update, landing) stays
  with the coordinator.
- Commands surfaced by this pack: none (choreography lives in the `bump`
  skill; invocation contract for coordinators/build).
