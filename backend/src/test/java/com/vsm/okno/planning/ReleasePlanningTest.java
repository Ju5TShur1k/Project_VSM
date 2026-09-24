package com.vsm.okno.planning;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReleasePlanningTest {
    private static final OffsetDateTime START = OffsetDateTime.of(2028, 7, 1, 0, 0, 0, 0,
            ZoneOffset.ofHours(3));
    private static final UUID SCENARIO = id("release-scenario");
    private static final UUID TRAIN = id("release-train");
    private static final UUID WORK = id("release-work");
    private static final UUID CHECK = id("release-check");

    @Test
    void maintenanceAndCheckBeforeDepartureAreFeasibleInBothPolicies() {
        ScenarioSnapshot snapshot = snapshot(0, 20, 20, 40, true);
        for (PlannerResult result : List.of(plan(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_EDD),
                plan(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT))) {
            assertTrue(result.solverStatus() == PlannerResult.SolverStatus.FEASIBLE
                    || result.solverStatus() == PlannerResult.SolverStatus.OPTIMAL);
            assertEquals(START, result.blocks().getFirst().startAt());
            assertEquals(START.plusMinutes(20), result.blocks().getFirst().endAt());
            assertEquals(START.plusMinutes(20), result.blocks().get(1).startAt());
            assertEquals(START.plusMinutes(30), result.blocks().get(1).endAt());
        }
    }

    @Test
    void departureBetweenMaintenanceAndCheckCannotBeApprovedAsAPlan() {
        ScenarioSnapshot snapshot = snapshot(0, 20, 80, 100, true);
        PlannerResult baseline = plan(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_EDD);
        PlannerResult optimized = plan(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT);
        assertEquals(PlannerResult.SolverStatus.UNKNOWN, baseline.solverStatus());
        assertEquals("EDD_RELEASE_CHECK_NO_SLOT", baseline.diagnostics().getFirst().code());
        assertTrue(baseline.blocks().isEmpty());
        assertEquals(PlannerResult.SolverStatus.INFEASIBLE, optimized.solverStatus());
        assertTrue(optimized.blocks().isEmpty());
    }

    @Test
    void maintenanceAfterTheEarlierTripMayBeReleasedLater() {
        ScenarioSnapshot snapshot = snapshot(80, 100, 100, 120, true);
        PlannerResult optimized = plan(snapshot, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT);
        assertEquals(PlannerResult.SolverStatus.OPTIMAL, optimized.solverStatus());
        assertEquals(START.plusMinutes(80), optimized.blocks().getFirst().startAt());
        assertEquals(START.plusMinutes(110), optimized.blocks().get(1).endAt());
    }

    @Test
    void typedMaintenanceCannotLoseItsReleaseRequirement() {
        assertThrows(IllegalArgumentException.class, () -> snapshot(0, 20, 20, 40, false));
    }

    private static PlannerResult plan(ScenarioSnapshot snapshot, PlannerRequest.Policy policy) {
        PlannerRequest request = new PlannerRequest("1.1", SCENARIO, snapshot.snapshotHash(), policy, 42, 5, 0);
        return policy == PlannerRequest.Policy.WHOLE_CYCLE_EDD
                ? new EarliestDueDatePlanner().plan(snapshot, request)
                : new CpSatPlanner().plan(snapshot, request);
    }

    private static ScenarioSnapshot snapshot(int workStart, int workEnd, int checkStart,
                                             int checkEnd, boolean includeRequirement) {
        ScenarioSnapshot.ServiceBlock work = new ScenarioSnapshot.ServiceBlock(WORK, TRAIN, "SERVICE", 20,
                workStart, workEnd, List.of(),
                ScenarioSnapshot.ServiceBlock.Kind.RELEASE_GATED_MAINTENANCE);
        ScenarioSnapshot.ServiceBlock check = new ScenarioSnapshot.ServiceBlock(CHECK, TRAIN, "INSPECT", 10,
                checkStart, checkEnd, List.of(WORK), ScenarioSnapshot.ServiceBlock.Kind.RELEASE_CHECK);
        OperationalConstraints operations = new OperationalConstraints(Set.of(), List.of(),
                List.of(new OperationalConstraints.ServiceWindow(TRAIN, "SERVICE", workStart, workEnd, "fixture"),
                        new OperationalConstraints.ServiceWindow(TRAIN, "INSPECT", checkStart, checkEnd, "fixture")),
                List.of(), includeRequirement
                ? List.of(new OperationalConstraints.ReleaseRequirement(WORK, CHECK, "fixture requirement"))
                : List.of());
        return new ScenarioSnapshot("1.3", SCENARIO, "fixture-release", "fixture", START,
                START.plusMinutes(120), List.of(new ScenarioSnapshot.Train(TRAIN, "TRAIN")),
                List.of(new ScenarioSnapshot.Resource("SERVICE"), new ScenarioSnapshot.Resource("INSPECT")),
                List.of(work, check), List.of(new ScenarioSnapshot.FixedTrip(id("release-trip"), TRAIN,
                        "trip", 60, 80, 670, "fixture")), operations);
    }

    private static UUID id(String label) {
        return UUID.nameUUIDFromBytes(label.getBytes(StandardCharsets.UTF_8));
    }
}
