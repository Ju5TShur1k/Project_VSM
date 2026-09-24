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

class OperationalPlanningTest {
    private static final OffsetDateTime START = OffsetDateTime.of(2028, 7, 1, 0, 0, 0, 0, ZoneOffset.ofHours(3));
    private static final UUID SCENARIO = id("e3-scenario");
    private static final UUID WORK = id("e3-work");
    private static final UUID RESERVE = id("e3-reserve");
    private static final UUID SERVICE = id("e3-service");

    @Test
    void checkedLocationTransferAndResourceOutageConstrainBothPolicies() {
        ScenarioSnapshot snapshot = operationalSnapshot(List.of(), serviceWindows(60, 150));
        PlannerResult baseline = new EarliestDueDatePlanner().plan(snapshot,
                request(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_EDD, 95));
        PlannerResult optimized = new CpSatPlanner().plan(snapshot,
                request(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT, 95));
        assertEquals(PlannerResult.SolverStatus.FEASIBLE, baseline.solverStatus());
        assertEquals(PlannerResult.SolverStatus.OPTIMAL, optimized.solverStatus());
        for (PlannerResult result : List.of(baseline, optimized)) {
            assertEquals(START.plusMinutes(100), result.blocks().getFirst().startAt());
            assertEquals(START.plusMinutes(130), result.blocks().getFirst().endAt());
        }
    }

    @Test
    void frozenPlacementStaysBeforeCutoffWhileUnpinnedWorkMustMove() {
        ScenarioSnapshot snapshot = operationalSnapshot(
                List.of(new OperationalConstraints.FrozenPlacement(SERVICE, 110)), serviceWindows(60, 150));
        PlannerResult baseline = new EarliestDueDatePlanner().plan(snapshot,
                request(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_EDD, 120));
        PlannerResult optimized = new CpSatPlanner().plan(snapshot,
                request(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT, 120));
        assertEquals(START.plusMinutes(110), baseline.blocks().getFirst().startAt());
        assertEquals(START.plusMinutes(110), optimized.blocks().getFirst().startAt());
        assertEquals(START.plusMinutes(140), optimized.blocks().getFirst().endAt());

        ScenarioSnapshot unfrozen = operationalSnapshot(List.of(), serviceWindows(60, 150));
        PlannerResult moved = new CpSatPlanner().plan(unfrozen,
                request(unfrozen, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT, 120));
        assertEquals(START.plusMinutes(120), moved.blocks().getFirst().startAt());
    }

    @Test
    void frozenPlacementConflictingWithOutageCannotBeSilentlyMoved() {
        ScenarioSnapshot snapshot = operationalSnapshot(
                List.of(new OperationalConstraints.FrozenPlacement(SERVICE, 80)), serviceWindows(60, 150));
        PlannerResult baseline = new EarliestDueDatePlanner().plan(snapshot,
                request(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_EDD, 120));
        PlannerResult optimized = new CpSatPlanner().plan(snapshot,
                request(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT, 120));
        assertEquals(PlannerResult.SolverStatus.UNKNOWN, baseline.solverStatus());
        assertEquals("EDD_FROZEN_CONFLICT", baseline.diagnostics().getFirst().code());
        assertEquals(PlannerResult.SolverStatus.INFEASIBLE, optimized.solverStatus());
        assertTrue(optimized.blocks().isEmpty());
    }

    @Test
    void noFullLocationWindowIsInfeasibleForCpSatButUnknownForGreedy() {
        ScenarioSnapshot snapshot = operationalSnapshot(List.of(), serviceWindows(0, 20));
        PlannerResult baseline = new EarliestDueDatePlanner().plan(snapshot,
                request(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_EDD, 0));
        PlannerResult optimized = new CpSatPlanner().plan(snapshot,
                request(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT, 0));
        assertEquals(PlannerResult.SolverStatus.UNKNOWN, baseline.solverStatus());
        assertEquals("EDD_NO_SLOT", baseline.diagnostics().getFirst().code());
        assertEquals(PlannerResult.SolverStatus.INFEASIBLE, optimized.solverStatus());
        assertTrue(optimized.blocks().isEmpty());
    }

    @Test
    void protectedReserveRejectsServiceAndFixedTrip() {
        OperationalConstraints ops = new OperationalConstraints(Set.of(RESERVE), List.of(),
                List.of(new OperationalConstraints.ServiceWindow(RESERVE, "PATH", 0, 150, "synthetic")), List.of());
        assertThrows(IllegalArgumentException.class, () -> new ScenarioSnapshot("1.2", SCENARIO,
                "synthetic-reserve", "synthetic", START, START.plusMinutes(150), trains(), resources(),
                List.of(new ScenarioSnapshot.ServiceBlock(SERVICE, RESERVE, "PATH", 30, 0, 150, List.of())),
                List.of(), ops));
        assertThrows(IllegalArgumentException.class, () -> new ScenarioSnapshot("1.2", SCENARIO,
                "synthetic-reserve", "synthetic", START, START.plusMinutes(150), trains(), resources(),
                List.of(), List.of(new ScenarioSnapshot.FixedTrip(id("reserve-trip"), RESERVE,
                        "reserve trip", 0, 30, 670, "synthetic")), ops));
        OperationalConstraints transferOnProtected = new OperationalConstraints(Set.of(RESERVE),
                List.of(new OperationalConstraints.FixedOccupancy(id("reserve-transfer"), RESERVE,
                        null, 0, 30, OperationalConstraints.Kind.TRANSFER, "synthetic")),
                List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> new ScenarioSnapshot("1.2", SCENARIO,
                "synthetic-reserve", "synthetic", START, START.plusMinutes(150), trains(), resources(),
                List.of(), List.of(), transferOnProtected));
    }

    @Test
    void cleaningIsRequiredBetweenFourthAndFifthTripAndVisibleAtHorizon() {
        ScenarioSnapshot base = cleaningBase(true, 200);
        CleaningObligationGenerator generator = new CleaningObligationGenerator();
        CleaningObligationGenerator.Rule rule = new CleaningObligationGenerator.Rule(30, "CLEANER", "synthetic");
        CleaningObligationGenerator.Projection projection = generator.generate(base, Map.of(WORK, 0), rule);
        assertEquals(1, projection.obligations().size());
        assertTrue(projection.pendingAtHorizon().isEmpty());
        ScenarioSnapshot.ServiceBlock cleaning = projection.snapshot().blocks().getFirst();
        assertEquals(110, cleaning.earliestStartMinute());
        assertEquals(200, cleaning.latestEndMinute());
        assertEquals(projection.obligations().getFirst().blockId(), cleaning.id());
        PlannerResult result = new CpSatPlanner().plan(projection.snapshot(),
                request(projection.snapshot(), PlannerRequest.Policy.BLOCKS_CP_SAT, 0));
        assertEquals(PlannerResult.SolverStatus.OPTIMAL, result.solverStatus());
        assertEquals(START.plusMinutes(110), result.blocks().getFirst().startAt());
        assertEquals(START.plusMinutes(140), result.blocks().getFirst().endAt());

        CleaningObligationGenerator.Projection boundary = generator.generate(cleaningBase(false, 200),
                Map.of(WORK, 0), rule);
        assertTrue(boundary.obligations().isEmpty());
        assertEquals(1, boundary.pendingAtHorizon().size());
    }

    @Test
    void shortCleaningWindowIsReportedBeforeOptimization() {
        ScenarioSnapshot base = cleaningBase(true, 120);
        assertThrows(IllegalArgumentException.class, () -> new CleaningObligationGenerator().generate(base,
                Map.of(WORK, 0), new CleaningObligationGenerator.Rule(30, "CLEANER", "synthetic")));
    }

    @Test
    void carriedTripCounterMakesTheFirstTripTheFourth() {
        ScenarioSnapshot base = cleaningBase(true, 200);
        CleaningObligationGenerator.Projection projection = new CleaningObligationGenerator().generate(base,
                Map.of(WORK, 3), new CleaningObligationGenerator.Rule(5, "CLEANER", "synthetic"));
        assertEquals(1, projection.obligations().size());
        assertEquals(id("r1"), projection.obligations().getFirst().fourthTripId());
        assertEquals(id("r2"), projection.obligations().getFirst().nextTripId());
        assertEquals(20, projection.snapshot().blocks().getFirst().earliestStartMinute());
        assertEquals(30, projection.snapshot().blocks().getFirst().latestEndMinute());
    }

    private static ScenarioSnapshot operationalSnapshot(
            List<OperationalConstraints.FrozenPlacement> frozen,
            List<OperationalConstraints.ServiceWindow> windows) {
        OperationalConstraints ops = new OperationalConstraints(Set.of(RESERVE),
                List.of(new OperationalConstraints.FixedOccupancy(id("path-outage"), null, "PATH", 60, 90,
                                OperationalConstraints.Kind.RESOURCE_OUTAGE, "synthetic"),
                        new OperationalConstraints.FixedOccupancy(id("transfer"), WORK, null, 90, 100,
                                OperationalConstraints.Kind.TRANSFER, "synthetic")),
                windows, frozen);
        return new ScenarioSnapshot("1.2", SCENARIO, "synthetic-operations", "synthetic",
                START, START.plusMinutes(150), trains(), resources(),
                List.of(new ScenarioSnapshot.ServiceBlock(SERVICE, WORK, "PATH", 30, 0, 150, List.of())),
                List.of(new ScenarioSnapshot.FixedTrip(id("fixed-trip"), WORK, "fixed trip", 0, 40,
                        670, "synthetic")), ops);
    }

    private static ScenarioSnapshot cleaningBase(boolean fifthTrip, int fifthStart) {
        List<ScenarioSnapshot.FixedTrip> trips = new java.util.ArrayList<>(List.of(
                trip("r1", 0, 20), trip("r2", 30, 50), trip("r3", 60, 80), trip("r4", 90, 110)));
        if (fifthTrip) trips.add(trip("r5", fifthStart, fifthStart + 20));
        OperationalConstraints ops = new OperationalConstraints(Set.of(), List.of(),
                List.of(new OperationalConstraints.ServiceWindow(WORK, "CLEANER", 20, 30, "synthetic"),
                        new OperationalConstraints.ServiceWindow(WORK, "CLEANER", 110, 220, "synthetic")), List.of());
        return new ScenarioSnapshot("1.2", SCENARIO, "synthetic-cleaning", "synthetic",
                START, START.plusMinutes(240), List.of(new ScenarioSnapshot.Train(WORK, "WORK")),
                List.of(new ScenarioSnapshot.Resource("CLEANER")), List.of(), trips, ops);
    }

    private static ScenarioSnapshot.FixedTrip trip(String label, int start, int end) {
        return new ScenarioSnapshot.FixedTrip(id(label), WORK, label, start, end, 670, "synthetic");
    }

    private static List<OperationalConstraints.ServiceWindow> serviceWindows(int start, int end) {
        return List.of(new OperationalConstraints.ServiceWindow(WORK, "PATH", start, end, "synthetic checked depot"));
    }

    private static List<ScenarioSnapshot.Train> trains() {
        return List.of(new ScenarioSnapshot.Train(WORK, "WORK"), new ScenarioSnapshot.Train(RESERVE, "RESERVE"));
    }

    private static List<ScenarioSnapshot.Resource> resources() {
        return List.of(new ScenarioSnapshot.Resource("PATH"));
    }

    private static PlannerRequest request(ScenarioSnapshot snapshot, PlannerRequest.Policy policy, int frozenUntil) {
        return new PlannerRequest("1.1", snapshot.scenarioId(), snapshot.snapshotHash(), policy, 42, 5, frozenUntil);
    }

    private static UUID id(String label) {
        return UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8));
    }
}
