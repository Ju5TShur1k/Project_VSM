# F2 E3-C1: four-train hot reserve

The case brief `6_ru.pdf` (pages 4 and 8) says that four trains remain in hot reserve at all times, receive no maintenance while in reserve, and are ready for operational entry. This block enforces that count in an internal planning snapshot. All tests use synthetic trains and times; they are not an operational timetable.

## Prepared input and constraint

- `ScenarioSnapshot` schema `1.4` requires a source-tagged `HotReserve` set of trains confirmed eligible to enter reserve **whenever idle for the entire horizon**. At least four eligible IDs must exist and all must refer to scenario trains. The earlier `protectedReserveTrainIds` still supports trains pinned in reserve throughout a horizon; those IDs must be in the eligible set and cannot carry trips, service or fixed train occupancies.
- A train is unavailable for reserve during any fixed trip, fixed train occupancy (including transfer or release wait), or scheduled service block of any kind. Resource-only outages do not remove a train from reserve. Train `NoOverlap` prevents a train from being double-counted in the CP-SAT reserve capacity.
- With `N` eligible trains, CP-SAT puts all their unavailable intervals into a cumulative constraint of capacity `N - 4`. Thus no instant can have more than `N - 4` eligible trains unavailable. EDD checks fixed commitments first, then checks every candidate placement against the same four-train coverage; its inability to place work remains `UNKNOWN`, never a proof of model infeasibility.
- `HotReserveCoverage.assess()` sweeps the event boundaries and reports ready trains, a deterministic selection of four reserve trains, and the first shortage interval. The selection is an explanation of a feasible schedule, not a business preference or a real dispatch order. A handoff at a shared boundary has no invented transfer duration: if a real handoff takes time, D1 must include that time as a train occupancy.
- The existing four-trip cleaning projection preserves schema `1.4` and creates a typed cleaning block. Plain `MAINTENANCE` does not invent a release-check operation. `RELEASE_GATED_MAINTENANCE` still requires an explicitly sourced check; the case PDF does not supply one.

## Synthetic counterexamples

Five eligible trains: train T2 receives cleaning at minutes 0–20; train T1 takes a fixed trip at 20–40. There are four eligible idle trains on both sides of minute 20, but the selected fourth train changes from T1 to T2. Both B0 and B1 place the cleaning at 0–20. If cleaning can occur only at 20–40, only three trains remain ready: EDD returns `UNKNOWN`, CP-SAT `INFEASIBLE`. A fixed trip alone violates the rule when only four trains are eligible. A source-tagged transfer overlapping a trip also counts as unavailability. The tests additionally cover cleaning projection and plain maintenance under schema `1.4`.

## Scope and handoff

Eligibility is an **input fact**, not inferred from a train being absent from the timetable. This slice assumes each listed train stays reserve-eligible while idle over the whole snapshot horizon. The brief also describes gradual fleet commissioning, so a horizon crossing a commissioning change must be split or a later version must model time-varying eligibility. Location, activation time and dispatcher preference are not specified in the brief; source occupancies and shorter horizons can conservatively represent known limitations.

The brief states an operational-readiness target of at least 89%, but does not provide a complete time series of commissioned trains and their readiness states. This block does not claim that four-train reserve coverage proves the 89% KPI. A readiness report needs a separately defined denominator and validated state history.

- **F1:** pass the versioned eligible set and its source to the worker; retain solver status separately from D2 validation and approval. The current HTTP mock is not this planner.
- **D1:** provide the active fleet and sourced reserve eligibility for each horizon, fixed trips, transfer/release occupancies and any known handoff time. Hash all those facts with the snapshot.
- **D2:** independently sweep all fixed and planned train intervals, check at least four eligible idle trains at every boundary, and verify the eligibility evidence. Do not call the F2 evaluator as the independent validator.

Rolling mileage/cleaning carryover, real topology and the IS510–IS540 block catalog remain separate work. The PDF permits splitting those cycles but supplies no operation-level durations or resources, so this block does not invent them.

Verification on 2026-09-24: local Java 21 `mvn verify` passed 31/31 backend tests. The pinned Linux/Temurin 21 planner smoke passed 26/26 tests, including native CP-SAT.
