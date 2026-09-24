# F2 E2: fixed turns, mileage obligations, B0/B1 and calendar handoff

This is a **synthetic, independently testable F2 slice**, not a production schedule. It adds `ScenarioSnapshot` schema `1.1` with fixed trips and keeps `1.0` for E1 fixtures; trips are rejected in a `1.0` snapshot. F1's HTTP mock remains separate; its current empty plan is not an output of either planner.

## Calculation contract

1. D1 supplies an immutable scenario ID/hash, initial odometer for each train, fixed already-checked turns with integer kilometre distances and times, and the **last credited absolute-grid nominal milestone for every rule**. Missing credit history is rejected instead of guessed. D1 must replace the fixture hash with a canonical SHA-256 of the full snapshot.
2. `MileageObligationGenerator` credits trip distance **at arrival**, never during idle time. A service may begin only after the train reaches the lower odometer bound; if the next fixed trip would finish above an upper bound, service must finish **before that trip departs**. If the bound is not crossed within the visible horizon, the current E2 projection caps the placement at horizon end. This cap is a planning boundary, not proof that there is no residual obligation after it.
3. E2 explicitly uses an **absolute nominal kilometre grid**, with conservative integral rounding (release rounded up, upper bound rounded down). At coincident nominal milestones the highest-ranked cycle becomes one indivisible service, covers all coincident junior cycles, takes the latest release and earliest upper bound of the group. The covered cycles remain visible in the `Obligation` metadata. Previous milestone work on a train precedes later milestone work.
4. B0 `WHOLE_CYCLE_EDD` selects the ready block with earliest deadline and places it at the first free minute, checking fixed trips, train and exclusive resource occupancy. It does not backtrack or add artificial delays. A complete result is `FEASIBLE`, never `OPTIMAL`; inability to place a block is `UNKNOWN` with `EDD_NO_SLOT`, never a proof of infeasibility.
5. B1 `WHOLE_CYCLE_CP_SAT` uses the same prepared snapshot and hard constraints as B0, with fixed trips occupying their train and makespan as the current transparent **technical** objective. A solver `OPTIMAL` claim applies only to this reduced model. B2 `BLOCKS_CP_SAT` remains the E1 toy path until A1 confirms the split catalog and release rules.
6. Result intervals are `[start,end)` with an explicit UTC offset. Neither planner output is an approval: D2 must independently validate the final intervals against the source snapshot and business rules. `UNKNOWN` or `INFEASIBLE` has no plan intervals.

## Hand-checked fixtures

- Single synthetic train starts at 24,000 km. The last accepted IS100 nominal milestone is 12,500 km; IS200 is 0 km. Three fixed 670 km trips occupy minutes 20–50, 90–120 and 160–190 of a 240-minute horizon. The 25,000 km nominal milestone is reached on the second arrival. One synthetic 30-minute IS200 cycle covers IS100 and IS200 at 25,000 km. The stricter junior bounds give release 22,500 km and upper bound 27,500 km. B0 places it at minutes 50–80; B1 returns a feasible optimum for its makespan model. The 30-minute duration and trip times are **synthetic**, not the case's 4-hour IS200 duration.
- Starting instead at 27,000 km, the first 670 km trip would exceed the IS100 upper bound. A synthetic 10-minute senior cycle must finish before departure at minute 20; a 30-minute cycle has no legal pre-trip window and input preparation reports that conflict.
- Two synthetic trains share one path. Greedy EDD places an earlier-due job first and then cannot fit the other train before its fixed trip and deadline: `UNKNOWN / EDD_NO_SLOT`. CP-SAT reorders them and finds a complete plan. This guards against misreporting a heuristic failure as model infeasibility.

## Calendar preview

`frontend/src/calendar/PlanningCalendar.tsx` is a reusable view of train and resource lanes. Clicking an interval shows the rule source, covered cycles, mileage bounds and a fact-based reason for the window. `/?calendarDemo=1` displays the first **hand-checked synthetic** fixture after login, separately from F1's current API plan. It says independent validation is pending and exposes no approval action. F1 can later adapt a persisted, D2-validated plan to `CalendarData`; until then this preview must not be presented as live solver output.

## Verification and next consumers

Run `mvn verify` under Java 21; on this Windows host Surefire uses the portable Java 21 runtime in ignored `backend/target`. Run `npm ci` then `npm run build` in `frontend`. The Java suite checks the 25,000 km collapse, deadline before a trip, fixed-trip exclusion and the greedy failure counterexample, in addition to E1 and F1 smoke tests. On 2026-09-24 the full local suite passed 12/12 tests and the frontend production build passed. The Linux/Temurin 21 Docker smoke ran all seven E1/E2 planner tests, including native `solve()`, with zero failures.

- **D1:** map actual accepted cycle history, rule version/source, trips, train IDs and canonical snapshot hash to `Input`; do not infer last credit from current odometer. Confirm absolute-grid policy before using imported data.
- **A1:** confirm IS100/IS200 tolerance interpretation, how an IS200 credits IS100, release after arrival, actual durations/resources and acceptance moment. Provide a technology-approved IS510–540 block catalog before B2 is used for production claims.
- **D2:** independently validate both B0 and B1 intervals, especially mileage deadlines before departure, resource/train overlap and residual obligations beyond horizon; do not reuse planner's slot search.
- **F1:** bind the approved snapshot projection and asynchronous worker to internal `Planner`, keep job lifecycle separate from planner outcome, store metadata needed by `CalendarData`, and gate approval on D2 validation. The shared OpenAPI/DTO mapping needs team agreement before changes.

E3 remains: hot reserve, cleaning, location/movement, release checks, frozen work and rolling horizon. Readiness 89% and production benefits are not computed in E2.
