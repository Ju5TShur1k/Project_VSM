package com.vsm.okno.planning;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CpSatPlannerToyTest {
    private static final OffsetDateTime START = OffsetDateTime.of(2028, 7, 1, 0, 0, 0, 0, ZoneOffset.ofHours(3));
    private static final UUID SCENARIO = id("scenario");
    private static final UUID TRAIN_A = id("train-a");
    private static final UUID TRAIN_B = id("train-b");
    private static final UUID TRAIN_C = id("train-c");
    private static final UUID A1 = id("a1");
    private static final UUID A2 = id("a2");
    private static final UUID B = id("b");
    private static final UUID C = id("c");

    @Test
    void fourBlocksForThreeTrainsFitOnOneExclusivePath() {
        ScenarioSnapshot snapshot = toy(false);
        PlannerResult result = new CpSatPlanner().plan(snapshot, request(snapshot));

        assertEquals(PlannerResult.SolverStatus.OPTIMAL, result.solverStatus());
        assertEquals(4, result.blocks().size());
        assertEquals(270.0, result.objectiveMinutes());
        assertBlock(result, A1, 0, 60);
        assertBlock(result, A2, 60, 120);
        assertBlock(result, B, 120, 210);
        assertBlock(result, C, 210, 270);
        for (int i = 1; i < result.blocks().size(); i++) {
            assertTrue(!result.blocks().get(i).startAt().isBefore(result.blocks().get(i - 1).endAt()),
                    "the single path must not be double-booked");
        }
        System.out.println("F2 E1 feasible: " + result.solverStatus() + " " + result.blocks());
    }

    @Test
    void impossibleLastWindowIsProvenInfeasibleWithoutPlan() {
        ScenarioSnapshot snapshot = toy(true);
        PlannerResult result = new CpSatPlanner().plan(snapshot, request(snapshot));

        assertEquals(PlannerResult.SolverStatus.INFEASIBLE, result.solverStatus());
        assertTrue(result.blocks().isEmpty());
        assertEquals("SOLVER_INFEASIBLE", result.diagnostics().getFirst().code());
        System.out.println("F2 E1 impossible: " + result.solverStatus() + " " + result.diagnostics());
    }

    @Test
    void requestForAnotherSnapshotIsRejectedBeforeSolving() {
        ScenarioSnapshot snapshot = toy(false);
        PlannerRequest wrong = new PlannerRequest("1.0", SCENARIO, "different-hash",
                PlannerRequest.Policy.BLOCKS_CP_SAT, 42, 5);
        assertThrows(IllegalArgumentException.class, () -> new CpSatPlanner().plan(snapshot, wrong));
    }

    private static ScenarioSnapshot toy(boolean impossible) {
        // All values are synthetic. Times are minute offsets from 2028-07-01T00:00+03:00.
        return new ScenarioSnapshot("1.0", SCENARIO, impossible ? "toy-impossible-v1" : "toy-feasible-v1",
                "synthetic", START, START.plusMinutes(300),
                List.of(new ScenarioSnapshot.Train(TRAIN_A, "TOY-A"),
                        new ScenarioSnapshot.Train(TRAIN_B, "TOY-B"),
                        new ScenarioSnapshot.Train(TRAIN_C, "TOY-C")),
                List.of(new ScenarioSnapshot.Resource("TRACK-1")),
                List.of(new ScenarioSnapshot.ServiceBlock(A1, TRAIN_A, "TRACK-1", 60, 0, 120, List.of()),
                        new ScenarioSnapshot.ServiceBlock(A2, TRAIN_A, "TRACK-1", 60, 0, 120, List.of(A1)),
                        new ScenarioSnapshot.ServiceBlock(B, TRAIN_B, "TRACK-1", 90, 120, 210, List.of()),
                        new ScenarioSnapshot.ServiceBlock(C, TRAIN_C, "TRACK-1", 60,
                                impossible ? 150 : 210, impossible ? 210 : 300, List.of())));
    }

    private static PlannerRequest request(ScenarioSnapshot snapshot) {
        return new PlannerRequest("1.0", snapshot.scenarioId(), snapshot.snapshotHash(),
                PlannerRequest.Policy.BLOCKS_CP_SAT, 42, 5);
    }

    private static void assertBlock(PlannerResult result, UUID id, int startMinute, int endMinute) {
        PlannerResult.PlannedBlock block = result.blocks().stream().filter(value -> value.blockId().equals(id))
                .findFirst().orElseThrow();
        assertEquals(START.plusMinutes(startMinute), block.startAt());
        assertEquals(START.plusMinutes(endMinute), block.endAt());
    }

    private static UUID id(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8));
    }
}
