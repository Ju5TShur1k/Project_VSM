package com.vsm.okno.planning;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HotReservePlanningTest {
    private static final OffsetDateTime START = OffsetDateTime.of(2028, 7, 1, 0, 0, 0, 0,
            ZoneOffset.ofHours(3));
    private static final UUID SCENARIO = id("reserve-scenario");
    private static final UUID T1 = id("reserve-t1");
    private static final UUID T2 = id("reserve-t2");
    private static final UUID T3 = id("reserve-t3");
    private static final UUID T4 = id("reserve-t4");
    private static final UUID T5 = id("reserve-t5");
    private static final UUID CLEANING = id("reserve-cleaning");

    @Test
    void fourthReserveTrainChangesAtTripBoundary() {
        ScenarioSnapshot snapshot = snapshot(List.of(T1, T2, T3, T4, T5), 0, 60,
                List.of(trip(T1, 20, 40)));
        for (PlannerResult result : List.of(plan(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_EDD),
                plan(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT))) {
            assertTrue(result.solverStatus() == PlannerResult.SolverStatus.FEASIBLE
                    || result.solverStatus() == PlannerResult.SolverStatus.OPTIMAL);
            assertEquals(START, result.blocks().getFirst().startAt());
            assertEquals(START.plusMinutes(20), result.blocks().getFirst().endAt());
            HotReserveCoverage.Assessment coverage = HotReserveCoverage.assess(snapshot,
                    List.of(new HotReserveCoverage.Assignment(T2, 0, 20)));
            assertTrue(coverage.satisfiesRequirement());
            assertEquals(4, coverage.minimumReady());
            assertEquals(Set.of(T1, T3, T4, T5),
                    Set.copyOf(coverage.periods().getFirst().selectedReserveTrainIds()));
            assertEquals(Set.of(T2, T3, T4, T5),
                    Set.copyOf(coverage.periods().get(1).selectedReserveTrainIds()));
        }
    }

    @Test
    void serviceOverlappingTripLeavesOnlyThreeReadyTrains() {
        ScenarioSnapshot snapshot = snapshot(List.of(T1, T2, T3, T4, T5), 20, 40,
                List.of(trip(T1, 20, 40)));
        PlannerResult baseline = plan(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_EDD);
        PlannerResult optimized = plan(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT);
        assertEquals(PlannerResult.SolverStatus.UNKNOWN, baseline.solverStatus());
        assertEquals("EDD_NO_SLOT", baseline.diagnostics().getFirst().code());
        assertEquals(PlannerResult.SolverStatus.INFEASIBLE, optimized.solverStatus());
        HotReserveCoverage.Assessment violation = HotReserveCoverage.assess(snapshot,
                List.of(new HotReserveCoverage.Assignment(T2, 20, 40)));
        assertEquals(3, violation.minimumReady());
        assertEquals(20, violation.firstShortfall().startMinute());
    }

    @Test
    void fixedTripAloneCanBreakFourTrainReserve() {
        ScenarioSnapshot snapshot = snapshot(List.of(T1, T2, T3, T4), -1, -1,
                List.of(trip(T1, 20, 40)));
        PlannerResult baseline = plan(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_EDD);
        PlannerResult optimized = plan(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT);
        assertEquals(PlannerResult.SolverStatus.UNKNOWN, baseline.solverStatus());
        assertEquals("EDD_FIXED_RESERVE_SHORTFALL", baseline.diagnostics().getFirst().code());
        assertEquals(PlannerResult.SolverStatus.INFEASIBLE, optimized.solverStatus());
    }

    @Test
    void transferOccupancyCannotBeMistakenForReadyReserve() {
        OperationalConstraints.FixedOccupancy transfer = new OperationalConstraints.FixedOccupancy(
                id("reserve-transfer"), T2, null, 20, 25,
                OperationalConstraints.Kind.TRANSFER, "synthetic transfer");
        ScenarioSnapshot snapshot = snapshot(List.of(T1, T2, T3, T4, T5), -1, -1,
                List.of(trip(T1, 20, 40)), List.of(transfer));
        PlannerResult baseline = plan(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_EDD);
        PlannerResult optimized = plan(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT);
        assertEquals("EDD_FIXED_RESERVE_SHORTFALL", baseline.diagnostics().getFirst().code());
        assertEquals(PlannerResult.SolverStatus.INFEASIBLE, optimized.solverStatus());
    }

    @Test
    void cleaningProjectionKeepsTheHotReserveContract() {
        List<ScenarioSnapshot.FixedTrip> trips = List.of(
                namedTrip("r1", 0, 5), namedTrip("r2", 10, 15), namedTrip("r3", 20, 25),
                namedTrip("r4", 30, 35), namedTrip("r5", 50, 55));
        OperationalConstraints operations = new OperationalConstraints(Set.of(), List.of(),
                List.of(new OperationalConstraints.ServiceWindow(T1, "CLEANER", 35, 50,
                        "synthetic depot window")), List.of(), List.of(),
                new OperationalConstraints.HotReserve(Set.of(T1, T2, T3, T4, T5), "synthetic eligibility"));
        ScenarioSnapshot base = new ScenarioSnapshot("1.4", SCENARIO, "synthetic-cleaning-reserve",
                "synthetic", START, START.plusMinutes(60), trains(), resources(), List.of(), trips,
                operations);
        CleaningObligationGenerator.Projection projection = new CleaningObligationGenerator().generate(base,
                Map.of(T1, 0), new CleaningObligationGenerator.Rule(10, "CLEANER", "case-inspired fixture"));
        assertEquals("1.4", projection.snapshot().schemaVersion());
        assertEquals(ScenarioSnapshot.ServiceBlock.Kind.CLEANING,
                projection.snapshot().blocks().getFirst().kind());
        assertEquals(PlannerResult.SolverStatus.OPTIMAL,
                plan(projection.snapshot(), PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT).solverStatus());
    }

    @Test
    void ordinaryMaintenanceDoesNotInventAnAcceptanceDuration() {
        ScenarioSnapshot base = snapshot(List.of(T1, T2, T3, T4, T5), 0, 60, List.of());
        ScenarioSnapshot.ServiceBlock maintenance = new ScenarioSnapshot.ServiceBlock(CLEANING, T2,
                "CLEANER", 20, 0, 60, List.of(), ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE);
        ScenarioSnapshot snapshot = new ScenarioSnapshot("1.4", SCENARIO, base.snapshotHash(),
                base.provenance(), START, START.plusMinutes(60), base.trains(), base.resources(),
                List.of(maintenance), List.of(), base.operations());
        assertEquals(PlannerResult.SolverStatus.OPTIMAL,
                plan(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT).solverStatus());
    }

    @Test
    void reserveEligibilityMustBeExplicitAndReferToKnownTrains() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(List.of(T1, T2, T3, id("unknown")),
                -1, -1, List.of()));
        OperationalConstraints withoutReserve = new OperationalConstraints(Set.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> new ScenarioSnapshot("1.4", SCENARIO,
                "missing-policy", "fixture", START, START.plusMinutes(60), trains(), resources(),
                List.of(), List.of(), withoutReserve));
    }

    private static PlannerResult plan(ScenarioSnapshot snapshot, PlannerRequest.Policy policy) {
        PlannerRequest request = new PlannerRequest("1.1", SCENARIO, snapshot.snapshotHash(), policy, 42, 5, 0);
        return policy == PlannerRequest.Policy.WHOLE_CYCLE_EDD
                ? new EarliestDueDatePlanner().plan(snapshot, request)
                : new CpSatPlanner().plan(snapshot, request);
    }

    private static ScenarioSnapshot snapshot(List<UUID> eligible, int cleaningStart, int cleaningEnd,
                                             List<ScenarioSnapshot.FixedTrip> trips) {
        return snapshot(eligible, cleaningStart, cleaningEnd, trips, List.of());
    }

    private static ScenarioSnapshot snapshot(List<UUID> eligible, int cleaningStart, int cleaningEnd,
                                             List<ScenarioSnapshot.FixedTrip> trips,
                                             List<OperationalConstraints.FixedOccupancy> occupancies) {
        List<ScenarioSnapshot.ServiceBlock> blocks = cleaningStart < 0 ? List.of()
                : List.of(new ScenarioSnapshot.ServiceBlock(CLEANING, T2, "CLEANER", 20,
                cleaningStart, cleaningEnd, List.of(), ScenarioSnapshot.ServiceBlock.Kind.CLEANING));
        List<OperationalConstraints.ServiceWindow> windows = cleaningStart < 0 ? List.of()
                : List.of(new OperationalConstraints.ServiceWindow(T2, "CLEANER",
                cleaningStart, cleaningEnd, "synthetic checked window"));
        OperationalConstraints operations = new OperationalConstraints(Set.of(), occupancies, windows,
                List.of(), List.of(), new OperationalConstraints.HotReserve(Set.copyOf(eligible),
                "synthetic eligibility"));
        return new ScenarioSnapshot("1.4", SCENARIO, "synthetic-hot-reserve", "synthetic", START,
                START.plusMinutes(60), trains(), resources(), blocks, trips, operations);
    }

    private static ScenarioSnapshot.FixedTrip trip(UUID trainId, int start, int end) {
        return new ScenarioSnapshot.FixedTrip(id("reserve-trip"), trainId, "trip", start, end,
                670, "synthetic");
    }

    private static ScenarioSnapshot.FixedTrip namedTrip(String label, int start, int end) {
        return new ScenarioSnapshot.FixedTrip(id(label), T1, label, start, end, 670, "synthetic");
    }

    private static List<ScenarioSnapshot.Train> trains() {
        return List.of(new ScenarioSnapshot.Train(T1, "T1"), new ScenarioSnapshot.Train(T2, "T2"),
                new ScenarioSnapshot.Train(T3, "T3"), new ScenarioSnapshot.Train(T4, "T4"),
                new ScenarioSnapshot.Train(T5, "T5"));
    }

    private static List<ScenarioSnapshot.Resource> resources() {
        return List.of(new ScenarioSnapshot.Resource("CLEANER"));
    }

    private static UUID id(String label) {
        return UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8));
    }
}
