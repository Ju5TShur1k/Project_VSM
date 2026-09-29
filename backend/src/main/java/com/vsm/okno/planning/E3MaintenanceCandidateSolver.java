package com.vsm.okno.planning;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.validation.E3CleaningCoverageAssessment;
import com.vsm.okno.validation.E3ReserveCoverageAssessment;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Runs CP-SAT for maintenance on an audited E3 assignment, without claiming full E3/D2. */
public final class E3MaintenanceCandidateSolver {
    public record Input(OffsetDateTime frozenUntil, int preparationMinutes,
                        Map<UUID, UUID> effectiveTrainByTrip, int seed, int timeLimitSec) {}
    public record Result(UUID scenarioId, UUID sourceSnapshotId, String snapshotHash,
                         int tripCount, int changedTripCount, String maintenanceSolverStatus,
                         String fullSolverStatus, String d2Status,
                         List<PlannerResult.PlannedBlock> maintenanceBlocks,
                         List<PlannerResult.Diagnostic> diagnostics,
                         E3ReserveCoverageAssessment.Report reserve,
                         E3CleaningCoverageAssessment.Report cleaning) {
        public Result {
            maintenanceBlocks = List.copyOf(maintenanceBlocks);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private final Planner planner;

    public E3MaintenanceCandidateSolver(Planner planner) { this.planner = planner; }

    public Result solve(SourceSnapshot saved, Input input) {
        if (input == null || input.frozenUntil() == null || input.effectiveTrainByTrip() == null
                || input.timeLimitSec() < 1 || input.timeLimitSec() > 60
                || input.preparationMinutes() < 0 || input.preparationMinutes() > 1440)
            throw new IllegalArgumentException("freeze, assignments, 0..1440 minute preparation and 1..60 second limit are required");
        var assignment = new E3TripAssignmentLedger().evaluate(saved,
                input.effectiveTrainByTrip(), input.frozenUntil(), input.preparationMinutes());
        var projection = new E3CandidateProjection().project(saved, assignment,
                input.frozenUntil(), input.preparationMinutes());
        var reserve = new E3ReserveCoverageAssessment().assess(saved, assignment,
                input.frozenUntil(), input.preparationMinutes());
        var cleaning = new E3CleaningCoverageAssessment().assess(saved, assignment,
                input.frozenUntil(), input.preparationMinutes());
        var blockers = E3FeasibilityAudit.noContiguousWindows(projection.snapshot(),
                projection.obligations());
        if (!blockers.isEmpty())
            return result(saved, assignment, "NOT_RUN", List.of(), blockers, reserve, cleaning);

        int frozenMinute = Math.toIntExact(Duration.between(projection.snapshot().horizonStart(),
                input.frozenUntil()).toMinutes());
        var request = new PlannerRequest("1.1", saved.scenarioId(), saved.snapshotHash(),
                PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT, input.seed(), input.timeLimitSec(),
                frozenMinute);
        var solved = planner.plan(projection.snapshot(), request);
        return result(saved, assignment, solved.solverStatus().name(), solved.blocks(),
                solved.diagnostics(), reserve, cleaning);
    }

    private static Result result(SourceSnapshot saved, E3TripAssignmentLedger.Assignment assignment,
                                 String maintenanceStatus, List<PlannerResult.PlannedBlock> blocks,
                                 List<PlannerResult.Diagnostic> diagnostics,
                                 E3ReserveCoverageAssessment.Report reserve,
                                 E3CleaningCoverageAssessment.Report cleaning) {
        return new Result(saved.scenarioId(), saved.id(), saved.snapshotHash(),
                assignment.trips().size(), assignment.changedTripCount(), maintenanceStatus,
                "NOT_RUN", "NOT_PERFORMED", blocks, diagnostics, reserve, cleaning);
    }
}
