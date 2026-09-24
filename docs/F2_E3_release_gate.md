# F2 E3-B: maintenance release gate (synthetic slice)

This block extends the internal planner contract to `ScenarioSnapshot` schema `1.3`. It is still a prepared, synthetic planning model; a scheduled check is **not** evidence that a real train has been accepted for service.

## Contract

- Every `1.3` service block has an explicit `Kind`. `MAINTENANCE` must have exactly one `ReleaseRequirement` referencing a separate `RELEASE_CHECK` block on the same train; every release check must be paired. The check must list its maintenance block as a predecessor. `CLEANING` and `OTHER` are distinct so an unclassified block cannot silently bypass this gate. Older snapshot versions retain their untyped `GENERAL` blocks.
- D1/A1 must supply the check duration, exclusive resource, checked location window and source. The snapshot rejects missing pairs, mismatched trains, and untyped `1.3` blocks before optimization. It cannot infer whether a source task was wrongly marked `OTHER`.
- CP-SAT places the release check after the maintenance. For each fixed trip on that train, maintenance must lie either after the trip or finish before it; in the latter case the release check must finish before departure. B0 EDD applies the same departure rule to candidate check slots and never calls a greedy failure a proof of infeasibility.
- Both blocks obey train/resource `NoOverlap`, service windows, frozen placements and the request cutoff. A release check in the future is a planned event. On execution, F1/D1 must record actual acceptance and keep the train unavailable until that acceptance is confirmed; D2 independently checks the release before approving the plan.

## Hand-checked cases

A 20-minute maintenance at minutes 0–20 and a 10-minute check at 20–30 both finish before a fixed trip at 60–80. B0 and B1 can schedule that plan. If the check is only possible at 80–100, B0 returns `UNKNOWN` and CP-SAT proves this prepared model `INFEASIBLE`; neither emits a complete plan. Work at 80–100 with a check at 100–110 is allowed after the earlier trip. A maintenance block without its release requirement is rejected at snapshot construction.

Run the full backend suite from `backend` with Java 21, or run the planner smoke in the pinned Linux image from the repository root:

```powershell
mvn verify
docker build --progress=plain -f backend/Dockerfile.planning-smoke backend
```

On this Windows workspace, Maven itself may use another Java version; Surefire must use Java 21 via its `-Djvm` option. The exact local command is in the final chat handoff.

Verification on 2026-09-24: the full local backend suite passed 24/24 tests; the pinned Linux/Temurin 21 image passed 19/19 selected planner tests, including native CP-SAT.

## Handoff and next E3 work

- **F1:** map typed blocks and release requirements into the worker input. Keep planned check, real acceptance and D2 validation as separate states. Prevent approval and live-calendar display until D2 passes.
- **D1:** supply a source-tagged complete set of maintenance and check pairs, plus actual acceptance events and location/resource windows. Include all fields in the canonical snapshot hash.
- **A1:** confirm which operations require a release check, its duration, sign-off role/resource and whether cleaning also requires a check. Confirm the production treatment of a trip when the check is still pending.
- **D2:** validate the source classification and every work/check/departure order independently from planner code; a scheduled check does not certify actual acceptance.

Still open in E3: dynamic four-train reserve coverage, multi-location topology/movement generated from real data, rolling horizon with residual obligations and B2 split-cycle rules. None of those is implied by the release-gate fixture.
