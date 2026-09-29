package com.vsm.okno.planning;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Bounded full-horizon candidate: rotations, mileage work and missing cleanings. */
public final class E3JointFullPlanner {
    public record Input(OffsetDateTime frozenUntil, int preparationMinutes, int maxMoves,
                        int maxEvaluationsPerMove, int seed, int timeLimitSec) {}
    public record Result(UUID scenarioId, UUID sourceSnapshotId, String snapshotHash,
                         String searchStatus, int moves, Map<UUID, UUID> effectiveTrainByTrip,
                         E3FullCandidateSolver.Result plan) {
        public Result { effectiveTrainByTrip = Map.copyOf(effectiveTrainByTrip); }
    }

    private final Planner solver;

    public E3JointFullPlanner(Planner solver) { this.solver = solver; }

    public Result plan(SourceSnapshot saved, Input input) {
        if (input == null || input.frozenUntil() == null || input.maxMoves() < 0
                || input.maxMoves() > 20 || input.maxEvaluationsPerMove() < 1
                || input.maxEvaluationsPerMove() > 32 || input.timeLimitSec() < 1
                || input.timeLimitSec() > 60 || input.preparationMinutes() < 0
                || input.preparationMinutes() > 1440)
            throw new IllegalArgumentException("E3 requires 0..20 moves, 1..32 evaluations, "
                    + "0..1440 minute preparation and 1..60 second solver limit");
        Map<UUID, UUID> candidate = Map.of();
        var full = new E3FullCandidateSolver(solver);
        var rotationSearch = new E3RotationCandidateSearch();
        for (int moves = 0; moves <= input.maxMoves(); moves++) {
            var proposed = full.solve(saved, new E3FullCandidateSolver.Input(input.frozenUntil(),
                    input.preparationMinutes(), candidate, input.seed(), input.timeLimitSec()));
            if (("FEASIBLE".equals(proposed.modelSolverStatus())
                    || "OPTIMAL".equals(proposed.modelSolverStatus()))
                    && "PASS".equals(proposed.structuralStatus()))
                return result(saved, "MODEL_CANDIDATE_FOUND", moves, candidate, proposed);
            if (!"NOT_RUN".equals(proposed.modelSolverStatus()))
                return result(saved, "MODEL_SOLVER_" + proposed.modelSolverStatus(), moves,
                        candidate, proposed);
            if (proposed.diagnostics().stream().anyMatch(d -> d.code().startsWith("E3_CLEANING_")))
                return result(saved, "CLEANING_SOURCE_OR_WINDOW_BLOCKED", moves, candidate, proposed);
            if (moves == input.maxMoves())
                return result(saved, "MOVE_LIMIT_REACHED", moves, candidate, proposed);
            var rotation = rotationSearch.search(saved, candidate, input.frozenUntil(),
                    input.preparationMinutes(), input.maxEvaluationsPerMove());
            if (!"IMPROVING_ROTATION_FOUND".equals(rotation.searchStatus()))
                return result(saved, rotation.searchStatus(), moves, candidate, proposed);
            candidate = rotation.effectiveTrainByTrip();
        }
        throw new IllegalStateException("unreachable E3 full search state");
    }

    private static Result result(SourceSnapshot saved, String status, int moves,
                                 Map<UUID, UUID> candidate, E3FullCandidateSolver.Result plan) {
        return new Result(saved.scenarioId(), saved.id(), saved.snapshotHash(),
                status, moves, candidate, plan);
    }
}
