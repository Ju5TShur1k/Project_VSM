package com.vsm.okno.validation;

import com.vsm.okno.planning.OperationalConstraints;
import com.vsm.okno.planning.PlannerRequest;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.ScenarioSnapshot;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndependentIntervalAuditTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2028-07-01T00:00:00+03:00");
    private static final UUID SCENARIO = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID BLOCK = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_BLOCK = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @Test
    void acceptsExactlyPlacedWorkAndHalfOpenBoundaries() {
        var source = source(5, false, false, true);
        assertTrue(IndependentIntervalAudit.check(source, result(source, List.of(placed(BLOCK, 0, 10)))).isEmpty());
    }

    @Test
    void rejectsMissingWorkAndChangedSnapshot() {
        var source = source(5, false, false, false);
        assertCode(source, result(source, List.of()), "MISSING_BLOCK");
        var altered = new PlannerResult("1.0", SCENARIO, "different", PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT,
                PlannerResult.SolverStatus.FEASIBLE, List.of(placed(BLOCK, 0, 10)), List.of(), 42, 1, 10.0);
        assertCode(source, altered, "SNAPSHOT_MISMATCH");
    }

    @Test
    void findsResourceCollisionAndMovedFrozenWork() {
        var source = source(6, true, false, false);
        assertCode(source, result(source, List.of(placed(BLOCK, 0, 10),
                new PlannerResult.PlannedBlock(SECOND_BLOCK, train(1), "PATH", START, START.plusMinutes(10)))),
                "RESOURCE_OVERLAP");
        var frozen = source(5, false, false, true);
        assertCode(frozen, result(frozen, List.of(placed(BLOCK, 10, 20))), "FROZEN_WORK_MOVED");
    }

    @Test
    void findsReserveShortageFromFixedTripEvenWithOtherwiseValidBlock() {
        var source = source(5, false, true, false);
        assertCode(source, result(source, List.of(placed(BLOCK, 0, 10))), "HOT_RESERVE_SHORTAGE");
    }

    private static ScenarioSnapshot source(int count, boolean twoBlocks, boolean trip, boolean frozen) {
        var trains = new ArrayList<ScenarioSnapshot.Train>();
        Set<UUID> eligible = new HashSet<>();
        for (int i = 0; i < count; i++) {
            trains.add(new ScenarioSnapshot.Train(train(i), "TEST-" + i));
            eligible.add(train(i));
        }
        var blocks = new ArrayList<ScenarioSnapshot.ServiceBlock>();
        blocks.add(new ScenarioSnapshot.ServiceBlock(BLOCK, train(0), "PATH", 10, 0, 30,
                List.of(), ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE));
        if (twoBlocks) blocks.add(new ScenarioSnapshot.ServiceBlock(SECOND_BLOCK, train(1), "PATH", 10, 0, 30,
                List.of(), ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE));
        var trips = trip ? List.of(new ScenarioSnapshot.FixedTrip(UUID.randomUUID(), train(1),
                "fixed", 0, 10, 100, "synthetic")) : List.<ScenarioSnapshot.FixedTrip>of();
        var windows = new ArrayList<OperationalConstraints.ServiceWindow>();
        windows.add(new OperationalConstraints.ServiceWindow(train(0), "PATH", 0, 30, "synthetic"));
        if (twoBlocks) windows.add(new OperationalConstraints.ServiceWindow(train(1), "PATH", 0, 30, "synthetic"));
        var frozenBlocks = frozen ? List.of(new OperationalConstraints.FrozenPlacement(BLOCK, 0))
                : List.<OperationalConstraints.FrozenPlacement>of();
        var operations = new OperationalConstraints(Set.of(), List.of(), windows, frozenBlocks,
                List.of(), new OperationalConstraints.HotReserve(eligible, "synthetic"));
        return new ScenarioSnapshot("1.4", SCENARIO, "audit-fixture", "synthetic", START,
                START.plusMinutes(30), trains, List.of(new ScenarioSnapshot.Resource("PATH")),
                blocks, trips, operations);
    }

    private static PlannerResult result(ScenarioSnapshot source, List<PlannerResult.PlannedBlock> blocks) {
        return new PlannerResult("1.0", source.scenarioId(), source.snapshotHash(),
                PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT, PlannerResult.SolverStatus.FEASIBLE,
                blocks, List.of(), 42, 1, 10.0);
    }

    private static PlannerResult.PlannedBlock placed(UUID id, int start, int end) {
        return new PlannerResult.PlannedBlock(id, train(0), "PATH", START.plusMinutes(start), START.plusMinutes(end));
    }

    private static UUID train(int n) {
        return new UUID(0L, n + 1L);
    }

    private static void assertCode(ScenarioSnapshot source, PlannerResult result, String code) {
        assertEquals(true, IndependentIntervalAudit.check(source, result).stream()
                .anyMatch(finding -> finding.code().equals(code)));
    }
}
