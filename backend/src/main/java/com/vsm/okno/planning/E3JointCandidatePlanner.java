package com.vsm.okno.planning;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

/** Bounded decomposition: verified return rotations followed by maintenance CP-SAT. */
public final class E3JointCandidatePlanner {
    public record Input(OffsetDateTime frozenUntil, int preparationMinutes, int maxMoves,
                        int maxEvaluationsPerMove, int seed, int timeLimitSec) {}
    public record Result(UUID scenarioId, UUID sourceSnapshotId, String snapshotHash,
                         String searchStatus, String lastRotationStatus, int moves,
                         Map<UUID, UUID> effectiveTrainByTrip,
                         E3MaintenanceCandidateSolver.Result maintenance) {
        public Result { effectiveTrainByTrip = Map.copyOf(effectiveTrainByTrip); }
    }

    private final Planner maintenancePlanner;

    public E3JointCandidatePlanner(Planner maintenancePlanner) {
        this.maintenancePlanner = maintenancePlanner;
    }

    public Result plan(SourceSnapshot saved, Input input) {
        if (input == null || input.frozenUntil() == null || input.maxMoves() < 0
                || input.maxMoves() > 4 || input.maxEvaluationsPerMove() < 1
                || input.maxEvaluationsPerMove() > 32 || input.timeLimitSec() < 1
                || input.timeLimitSec() > 60 || input.preparationMinutes() < 0
                || input.preparationMinutes() > 1440)
            throw new IllegalArgumentException("E3 search requires 0..4 moves, 1..32 evaluations, "
                    + "0..1440 minute preparation and 1..60 second solve limit");
        Map<UUID, UUID> candidate = Map.of();
        String lastRotation = "NOT_RUN";
        var solver = new E3MaintenanceCandidateSolver(maintenancePlanner);
        var search = new E3RotationCandidateSearch();
        for (int moves = 0; moves <= input.maxMoves(); moves++) {
            var maintenance = solver.solve(saved, new E3MaintenanceCandidateSolver.Input(
                    input.frozenUntil(), input.preparationMinutes(), candidate,
                    input.seed(), input.timeLimitSec()));
            if ("FEASIBLE".equals(maintenance.maintenanceSolverStatus())
                    || "OPTIMAL".equals(maintenance.maintenanceSolverStatus()))
                return result(saved, "MAINTENANCE_CANDIDATE_FOUND", lastRotation,
                        moves, candidate, maintenance);
            if (!"NOT_RUN".equals(maintenance.maintenanceSolverStatus()))
                return result(saved, "MAINTENANCE_SOLVER_" + maintenance.maintenanceSolverStatus(),
                        lastRotation, moves, candidate, maintenance);
            if (moves == input.maxMoves())
                return result(saved, "MOVE_LIMIT_REACHED", lastRotation, moves,
                        candidate, maintenance);
            var rotation = search.search(saved, candidate, input.frozenUntil(),
                    input.preparationMinutes(), input.maxEvaluationsPerMove());
            lastRotation = rotation.searchStatus();
            if (!"IMPROVING_ROTATION_FOUND".equals(lastRotation))
                return result(saved, lastRotation, lastRotation, moves, candidate, maintenance);
            candidate = rotation.effectiveTrainByTrip();
        }
        throw new IllegalStateException("unreachable E3 search state");
    }

    private static Result result(SourceSnapshot saved, String status, String lastRotation,
                                 int moves, Map<UUID, UUID> candidate,
                                 E3MaintenanceCandidateSolver.Result maintenance) {
        return new Result(saved.scenarioId(), saved.id(), saved.snapshotHash(), status,
                lastRotation, moves, candidate, maintenance);
    }
}
