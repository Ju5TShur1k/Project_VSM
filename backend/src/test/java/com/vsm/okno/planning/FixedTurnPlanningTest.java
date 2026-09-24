package com.vsm.okno.planning;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedTurnPlanningTest {
    private static final OffsetDateTime START = OffsetDateTime.of(2028, 7, 1, 0, 0, 0, 0, ZoneOffset.ofHours(3));
    private static final UUID SCENARIO = id("e2-scenario");
    private static final UUID TRAIN = id("e2-train");

    @Test
    void twentyFiveThousandProducesOneSeniorCycleWithJuniorCoverage() {
        MileageObligationGenerator.Projection projection = projection(24_000, 30);
        assertEquals(1, projection.snapshot().blocks().size());
        MileageObligationGenerator.Obligation obligation = projection.obligations().getFirst();
        assertEquals("IS200", obligation.cycleCode());
        assertEquals(25_000, obligation.nominalKm());
        assertEquals(22_500, obligation.releaseOdometerKm()); // junior IS100 has the tighter lower bound
        assertEquals(27_500, obligation.dueOdometerKm()); // junior IS100 has the tighter upper bound
        assertEquals(List.of("IS200", "IS100"), obligation.covers().stream()
                .map(MileageObligationGenerator.CoveredCycle::code).toList());
        assertEquals(3, projection.snapshot().fixedTrips().size());
        assertEquals(projection.snapshot().blocks().getFirst().id(), obligation.blockId());
        assertEquals(obligation.blockId(), projection(24_000, 30).obligations().getFirst().blockId());
    }

    @Test
    void baselineAndCpSatPlaceWholeCycleOutsideFixedTrips() {
        ScenarioSnapshot snapshot = projection(24_000, 30).snapshot();
        PlannerResult baseline = new EarliestDueDatePlanner().plan(snapshot,
                request(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_EDD));
        PlannerResult optimized = new CpSatPlanner().plan(snapshot,
                request(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT));
        assertEquals(PlannerResult.SolverStatus.FEASIBLE, baseline.solverStatus());
        assertEquals(PlannerResult.SolverStatus.OPTIMAL, optimized.solverStatus());
        assertEquals(1, baseline.blocks().size());
        assertEquals(1, optimized.blocks().size());
        assertEquals(START.plusMinutes(50), baseline.blocks().getFirst().startAt());
        assertEquals(START.plusMinutes(80), baseline.blocks().getFirst().endAt());
        assertEquals(80.0, optimized.objectiveMinutes());
        for (PlannerResult result : List.of(baseline, optimized)) {
            PlannerResult.PlannedBlock block = result.blocks().getFirst();
            for (ScenarioSnapshot.FixedTrip trip : snapshot.fixedTrips()) {
                OffsetDateTime tripStart = START.plusMinutes(trip.startMinute());
                OffsetDateTime tripEnd = START.plusMinutes(trip.endMinute());
                assertTrue(!block.startAt().isBefore(tripEnd) || !tripStart.isBefore(block.endAt()));
            }
        }
    }

    @Test
    void crossingMileageLimitOnNextTripRequiresCompletionBeforeDeparture() {
        MileageObligationGenerator.Projection projection = projection(27_000, 10);
        MileageObligationGenerator.Obligation obligation = projection.obligations().getFirst();
        assertEquals(START.plusMinutes(20), obligation.dueAt());
        assertTrue(obligation.completionRequiredBeforeNextTrip());
        PlannerResult result = new CpSatPlanner().plan(projection.snapshot(),
                request(projection.snapshot(), PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT));
        assertEquals(PlannerResult.SolverStatus.OPTIMAL, result.solverStatus());
        assertEquals(START.plusMinutes(10), result.blocks().getFirst().endAt());
        assertThrows(IllegalArgumentException.class, () -> projection(27_000, 30));
    }

    @Test
    void greedyNoSlotIsUnknownEvenWhenCpSatFindsAPlan() {
        UUID a = id("greedy-a");
        UUID b = id("greedy-b");
        ScenarioSnapshot snapshot = new ScenarioSnapshot("1.1", SCENARIO, "synthetic-greedy-trap",
                "synthetic", START, START.plusMinutes(130),
                List.of(new ScenarioSnapshot.Train(a, "A"), new ScenarioSnapshot.Train(b, "B")),
                List.of(new ScenarioSnapshot.Resource("PATH")),
                List.of(new ScenarioSnapshot.ServiceBlock(id("job-a"), a, "PATH", 30, 0, 100, List.of()),
                        new ScenarioSnapshot.ServiceBlock(id("job-b"), b, "PATH", 30, 0, 110, List.of())),
                List.of(new ScenarioSnapshot.FixedTrip(id("trip-b"), b, "fixed trip", 30, 100, 670, "synthetic")));
        PlannerResult baseline = new EarliestDueDatePlanner().plan(snapshot,
                request(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_EDD));
        PlannerResult optimized = new CpSatPlanner().plan(snapshot,
                request(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT));
        assertEquals(PlannerResult.SolverStatus.UNKNOWN, baseline.solverStatus());
        assertEquals("EDD_NO_SLOT", baseline.diagnostics().getFirst().code());
        assertTrue(baseline.blocks().isEmpty());
        assertEquals(PlannerResult.SolverStatus.OPTIMAL, optimized.solverStatus());
        assertEquals(2, optimized.blocks().size());
        assertEquals(id("job-b"), optimized.blocks().get(0).blockId());
        assertEquals(START.plusMinutes(0), optimized.blocks().get(0).startAt());
        assertEquals(START.plusMinutes(30), optimized.blocks().get(0).endAt());
        assertNotEquals(baseline.solverStatus(), optimized.solverStatus());
    }

    private static MileageObligationGenerator.Projection projection(long initialOdometer, int duration) {
        ScenarioSnapshot.Train train = new ScenarioSnapshot.Train(TRAIN, "EVS-SYN-1");
        return new MileageObligationGenerator().generate(new MileageObligationGenerator.Input(
                SCENARIO, "synthetic-e2-fixed-turns", "synthetic", START, START.plusMinutes(240),
                List.of(new MileageObligationGenerator.TrainState(train, initialOdometer,
                        Map.of("IS100", 12_500L, "IS200", 0L))),
                List.of(new ScenarioSnapshot.Resource("PATH")),
                List.of(new ScenarioSnapshot.FixedTrip(id("t1"), TRAIN, "R1", 20, 50, 670, "synthetic"),
                        new ScenarioSnapshot.FixedTrip(id("t2"), TRAIN, "R2", 90, 120, 670, "synthetic"),
                        new ScenarioSnapshot.FixedTrip(id("t3"), TRAIN, "R3", 160, 190, 670, "synthetic")),
                List.of(new MileageObligationGenerator.CycleRule("IS100", 12_500, 1_000, 20,
                                1, "PATH", "CASE / synthetic duration"),
                        new MileageObligationGenerator.CycleRule("IS200", 25_000, 2_000, duration,
                                2, "PATH", "CASE / synthetic duration"))));
    }

    private static PlannerRequest request(ScenarioSnapshot snapshot, PlannerRequest.Policy policy) {
        return new PlannerRequest("1.0", snapshot.scenarioId(), snapshot.snapshotHash(), policy, 42, 5);
    }

    private static UUID id(String key) {
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
