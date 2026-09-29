package com.vsm.okno.planning;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.dto.Dto;
import com.vsm.okno.validation.E3CleaningCoverageAssessment;
import com.vsm.okno.validation.E3ReserveCoverageAssessment;
import com.vsm.okno.validation.IndependentIntervalAudit;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Places mileage maintenance and every newly required cleaning in one CP-SAT model. */
public final class E3FullCandidateSolver {
    public record Input(OffsetDateTime frozenUntil, int preparationMinutes,
                        Map<UUID, UUID> effectiveTrainByTrip, int seed, int timeLimitSec) {}
    public record Result(UUID scenarioId, UUID sourceSnapshotId, String snapshotHash,
                         int tripCount, int changedTripCount, int requiredCleaningCount,
                         String modelSolverStatus, String structuralStatus, String d2Status,
                         List<PlannerResult.PlannedBlock> blocks,
                         List<PlannerResult.Diagnostic> diagnostics,
                         E3ReserveCoverageAssessment.Report reserve,
                         E3CleaningCoverageAssessment.Report cleaning,
                         Dto.PlanCalendar calendar) {
        public Result {
            blocks = List.copyOf(blocks);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private final Planner planner;

    public E3FullCandidateSolver(Planner planner) { this.planner = planner; }

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
        E3CleaningBlockProjector.Projection withCleaning;
        try {
            withCleaning = new E3CleaningBlockProjector().add(saved, projection.snapshot(), cleaning);
        } catch (E3CleaningBlockProjector.Blocked blocked) {
            return result(saved, assignment, cleaning, reserve, "NOT_RUN", "NOT_PERFORMED",
                    List.of(), List.of(new PlannerResult.Diagnostic(blocked.code(), blocked.getMessage())), null);
        }
        var source = withCleaning.snapshot();
        var blockers = E3FeasibilityAudit.noContiguousWindows(source, projection.obligations());
        if (!blockers.isEmpty())
            return result(saved, assignment, cleaning, reserve, "NOT_RUN", "NOT_PERFORMED",
                    List.of(), blockers, null);
        int frozenMinute = Math.toIntExact(Duration.between(source.horizonStart(),
                input.frozenUntil()).toMinutes());
        var request = new PlannerRequest("1.1", saved.scenarioId(), saved.snapshotHash(),
                PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT, input.seed(), input.timeLimitSec(), frozenMinute);
        var solved = planner.plan(source, request);
        List<PlannerResult.Diagnostic> diagnostics = new ArrayList<>(solved.diagnostics());
        var structuralIssues = IndependentIntervalAudit.check(source, solved);
        structuralIssues.forEach(issue -> diagnostics.add(new PlannerResult.Diagnostic(
                issue.code(), issue.message())));
        String structural = structuralIssues.isEmpty() ? "PASS" : "FAILED";
        Dto.PlanCalendar calendar = null;
        if (solved.solverStatus() == PlannerResult.SolverStatus.FEASIBLE
                || solved.solverStatus() == PlannerResult.SolverStatus.OPTIMAL) {
            var byId = projection.obligations().stream().collect(Collectors.toMap(
                    MileageObligationGenerator.Obligation::blockId, Function.identity()));
            calendar = PlanCalendarProjector.project(source, solved, byId, "NOT_PERFORMED");
        }
        return result(saved, assignment, cleaning, reserve, solved.solverStatus().name(), structural,
                solved.blocks(), diagnostics, calendar);
    }

    private static Result result(SourceSnapshot saved, E3TripAssignmentLedger.Assignment assignment,
                                 E3CleaningCoverageAssessment.Report cleaning,
                                 E3ReserveCoverageAssessment.Report reserve, String solverStatus,
                                 String structuralStatus, List<PlannerResult.PlannedBlock> blocks,
                                 List<PlannerResult.Diagnostic> diagnostics, Dto.PlanCalendar calendar) {
        return new Result(saved.scenarioId(), saved.id(), saved.snapshotHash(),
                assignment.trips().size(), assignment.changedTripCount(), cleaning.missing().size(),
                solverStatus, structuralStatus, "NOT_PERFORMED", blocks, diagnostics,
                reserve, cleaning, calendar);
    }
}
