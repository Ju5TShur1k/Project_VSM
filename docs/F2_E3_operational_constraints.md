# F2 E3-A: operational hard constraints (synthetic slice)

This block continues F2 on `backend_analitics` after E1/E2 entered `main`. It is an internal, prepared planning projection, **not** a live plan or a claim that the full E3 operational model is complete.

## Contract and model

- `ScenarioSnapshot` schema `1.2` adds immutable `OperationalConstraints`. E1 `1.0` and E2 `1.1` constructors remain valid. `PlannerRequest` schema `1.1` adds `frozenUntilMinute`; old `1.0` requests use zero.
- `protectedReserveTrainIds` names trains protected for the **whole visible horizon**. A protected train cannot have a service block or a fixed trip. This is a deliberately static subset of the 4-hot-reserve rule; dynamic reserve swaps are still pending.
- `FixedOccupancy` books a train, an exclusive resource, or both as a half-open interval. It can describe a confirmed transfer, resource outage, release wait or other commitment. CP-SAT adds it to the corresponding `NoOverlap`; EDD checks it before any new placement. Contradictory fixed facts yield `INFEASIBLE` in CP-SAT and `UNKNOWN` in EDD.
- `ServiceWindow` is an explicitly checked train/resource interval when the train is at the resource's location and access/movement is complete. A **whole** service block must fit inside one such window. The solver does not infer location from a trip label and does not silently assume depot availability. D1 must derive these windows from actual origins, destinations and manoeuvre rules, with their source.
- A `FrozenPlacement` fixes the start of a previously approved or started block. All other blocks must start at or after `frozenUntilMinute`. If a frozen slot conflicts with a new outage, CP-SAT reports model `INFEASIBLE`; EDD reports `UNKNOWN / EDD_FROZEN_CONFLICT`. Neither silently moves the frozen block. The request's cutoff and pinned list are a single immutable job snapshot.
- `CleaningObligationGenerator` uses fixed trips and the accepted trip counter (0–3). After the fourth trip it creates a mandatory indivisible cleaning block between that arrival and the fifth departure. It rejects a too-short gap as `CLEANING_WINDOW_TOO_SHORT`. If the fourth trip is the last one inside the horizon, it returns `PendingCleaning` for the next horizon instead of pretending the obligation vanished. The counter resets in the projection only because that generated block is mandatory; D2 must check actual completion and acceptance in the final plan.
- The toy objective remains makespan. It is **not** a readiness, reserve or economic KPI. Final intervals still require D2's independent validation before approval.

## Synthetic checks

The E3 fixture has one working train, one protected reserve train, one fixed trip, a depot window, a path outage and a transfer. A 30-minute whole-cycle service cannot start until minute 100; both B0 and B1 place it at 100–130. A pinned service at 110 stays there even with `frozenUntil=120`; without a pin it starts at 120. A pinned service at 80 conflicts with the path outage and produces no plan. Separate tests reject service/trips on the protected reserve and reject a location window too short for the block.

A five-trip synthetic turnover puts cleaning after trip four at minute 110 and before trip five at minute 200. A 30-minute toy cleaning block is placed at 110–140. With only four trips the pending obligation is carried beyond the visible horizon. The case's cleaning duration is 2–3 hours; **30 minutes is only a hand-checkable test fixture**. No actual depot turns or staff rosters have been imported.

Verification on 2026-09-24: local `mvn verify` with Java 21 ran 20 tests with zero failures; `docker build --progress=plain -f backend/Dockerfile.planning-smoke backend` ran all 15 E1/E2/E3 planner tests under Linux/Temurin 21, including native CP-SAT, with zero failures.

## Handoff and remaining E3 work

- **F1:** convert `frozenUntil` from an offset timestamp to a whole-minute solver offset; provide pinned placements from the prior approved plan and preserve them across jobs. Persist both planner status and D2 validation. Do not expose the synthetic calendar as a live approved plan.
- **D1:** provide versioned fixed trips, location/transfer facts, exclusive resource outages, source-tagged service windows, the accepted cleaning counter and snapshot hash covering all of these and the cleaning rule. An absent window means no legal service placement, not general depot availability.
- **A1:** confirm actual cleaning duration, required resource/acceptance, whether it may overlap other work, reserve exchange rules, manoeuvre durations and release checks. Synthetic choices cannot establish production feasibility.
- **D2:** independently check fixed occupations, full containment in service windows, protected reserve, frozen starts, trip-four cleaning before trip five and pending obligations across horizon boundaries. Never reuse the planner's slot predicate.

Still open for later E3 work: dynamic four-train reserve coverage, explicit accepted release before departure, multi-location transfers generated from real topology, rolling replan with residual mileage obligations, and B2 split-cycle comparison after A1 confirms technology. The present `AWAITING_RELEASE` fixed occupancy can represent an already known wait, but it does not itself prove that every planned maintenance block has a release check.
