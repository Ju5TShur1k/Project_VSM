# F2 E1: planning handoff contract (draft for F1, D1 and D2)

This is the internal Java boundary for the first independently testable F2 block. It is not yet an HTTP endpoint or an approved production service rule. The current F1 mock endpoint remains separate until the team signs off the snapshot and validation shapes.

## Input and output

- `ScenarioSnapshot` is immutable, version `1.0`, tied to `scenarioId` and `snapshotHash`. All lists are copied. It contains a horizon with an explicit UTC offset, trains, exclusive resources and required indivisible blocks. For E1, each block has one train, one resource, duration in whole minutes, `[earliestStartMinute, latestEndMinute]` bounds and predecessor IDs. A block must finish by `latestEndMinute`.
- The toy `snapshotHash` is an opaque fixture identifier. D1 must replace it with a canonical SHA-256 of the complete source snapshot before real job idempotency or persistence relies on it.
- `PlannerRequest` names the same scenario and hash, policy `BLOCKS_CP_SAT`, integer seed and positive time limit. A mismatch is an invalid request, never a valid empty plan.
- `PlannerResult` carries the separate CP-SAT status, intervals in ISO-8601-compatible offset timestamps, diagnostics, seed, elapsed time and objective value. `INFEASIBLE` means the E1 model proved its constraints incompatible. `UNKNOWN` does not mean infeasible. This output has **not** passed D2's independent `PlanValidator` and cannot by itself authorize approval.
- All intervals are half-open `[start,end)`. E1 minimizes makespan only after all blocks are required and all stated windows, train conflicts, path conflicts and precedences are hard constraints. This objective is a toy-model check, not a business objective or a claim about fleet readiness.

## E1 limits and provenance

The toy scenario has three synthetic trains, one exclusive path and four synthetic blocks. It does not contain real EVS360 trips, mileage, maintenance cycles, reserve, cleaning, depot calendars or release rules. No production conclusion or 89% KPI can be drawn from it. The current `PlanningService` mock must not be relabeled as a CP-SAT result.

| Block / train | Duration | Earliest start | Latest finish | Predecessor |
|---|---:|---:|---:|---|
| A1 / TOY-A | 60 min | 00:00 | 02:00 | — |
| A2 / TOY-A | 60 min | 00:00 | 02:00 | A1 |
| B / TOY-B | 90 min | 02:00 | 03:30 | — |
| C / TOY-C | 60 min | 03:30 | 05:00 | — |

All times are on 2028-07-01 at `+03:00`. A hand-checked feasible placement is A1 00:00–01:00, A2 01:00–02:00, B 02:00–03:30, C 03:30–04:30. The blocks use 270 minutes of a single path with no overlap. The makespan optimum is therefore 270 minutes: all 270 minutes of required work must occupy the path, and this placement attains that lower bound.

The impossible fixture changes only C's window to 02:30–03:30. B must occupy 02:00–03:30, so C has no available path time in its sole window. `INFEASIBLE` is expected for this E1 model, with no plan intervals; the diagnostic does not claim a minimum unsatisfiable core.

## Verification

`mvn verify` with tests running on Java 21 passes the original five API/auth tests and all three E1 planner tests. The feasible and impossible cases print their solver status and intervals. On this host, Maven itself uses Java 24 to compile with `--release 21`; setting Surefire's `jvm` property to a Java 21 runtime runs the native solver tests successfully. The vendor issue [google/or-tools#4690](https://github.com/google/or-tools/issues/4690) matches the Windows native crash seen with the installed Java 24. The portable Java 21 runtime used for verification is in ignored `backend/target` and is not part of the commit.

The Linux native smoke passed on 2026-09-24: `docker build --progress=plain -f backend/Dockerfile.planning-smoke backend` ran all three E1 tests on Linux/Temurin 21, including native `solve()`, with zero failures. The smoke Dockerfile pins the Maven/Temurin base image digest used in that run. The full local `mvn verify` also passed: eight tests and a repackaged JAR.

## Handoff decisions before E2 integration

1. **F1:** adapt the existing job request to the internal planner request, run the solver outside the HTTP thread, persist both technical job state and solver status, and reject approval until D2 validation succeeds. The current empty `FEASIBLE` mock is not a solver output.
2. **D1:** provide immutable domain snapshots with canonical SHA-256, versioned service rules, train/turn data and resource calendars. The E1 `ScenarioSnapshot` is a prepared planning projection, not the database schema.
3. **D2:** define `ValidationResult` independently from `PlannerResult`. Validate final intervals and source snapshot; do not call solver internals. E1 feasible and impossible fixtures are ready for this handoff.
4. **A1:** supply confirmed service blocks and release checks. Until then, all E1 work is synthetic.

Before changing shared OpenAPI/DTO names, the team should record the agreed mapping and version. Frozen tasks, alternative resources, reserve, location, trips and mileage obligations are deliberate E2/E3 additions, with no silent defaults in E1.
