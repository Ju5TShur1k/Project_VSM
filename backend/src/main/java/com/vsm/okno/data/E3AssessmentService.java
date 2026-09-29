package com.vsm.okno.data;

import com.vsm.okno.planning.E3FeasibilityAudit;
import com.vsm.okno.planning.E3TripAssignmentLedger;
import com.vsm.okno.planning.E3MileageObligationRecalculator;
import com.vsm.okno.planning.E3RotationCandidateSearch;
import com.vsm.okno.planning.E3MaintenanceCandidateSolver;
import com.vsm.okno.planning.CpSatPlanner;
import com.vsm.okno.planning.E3JointCandidatePlanner;
import com.vsm.okno.planning.MileageObligationGenerator;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.service.PlanningService;
import com.vsm.okno.validation.E3CleaningCoverageAssessment;
import com.vsm.okno.validation.E3MileageObligationAudit;
import com.vsm.okno.validation.E3ReserveCoverageAssessment;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Read-only assessment of the full-fleet source with its current fixed trip assignments. */
@Service
@Profile("database")
public final class E3AssessmentService {
    public record Assessment(UUID scenarioId, UUID sourceSnapshotId, String snapshotHash,
                             String provenance, int trainCount, int tripCount, int requiredWorkCount,
                             int eligibleTrainCount, int peakConcurrentTrips, int unassignedEligibleTrainCount,
                             String fixedAssignmentStatus, String d2Status,
                             List<PlannerResult.Diagnostic> blockers) {}
    public record CandidateInput(OffsetDateTime frozenUntil, int preparationMinutes,
                                 Map<UUID, UUID> effectiveTrainByTrip) {}
    public record RotationSearchInput(OffsetDateTime frozenUntil, int preparationMinutes,
                                      int maxEvaluations, Map<UUID, UUID> effectiveTrainByTrip) {}
    public record CandidateAssessment(UUID scenarioId, UUID sourceSnapshotId, String snapshotHash,
                                      int tripCount, int changedTripCount, int mileageObligationCount,
                                      String mileageStatus, String solverStatus, String d2Status,
                                      E3ReserveCoverageAssessment.Report reserve,
                                      E3CleaningCoverageAssessment.Report cleaning,
                                      List<PlannerResult.Diagnostic> blockers) {}
    private record Edge(Instant at, int change) {}

    private final SourceSnapshotRepository snapshots;

    public E3AssessmentService(SourceSnapshotRepository snapshots) { this.snapshots = snapshots; }

    public Assessment assess(UUID snapshotId) {
        var saved = snapshots.findById(snapshotId)
                .orElseThrow(() -> new PlanningService.NotFoundException("snapshot not found: " + snapshotId));
        MileageObligationGenerator.Projection projected;
        try {
            projected = new SourceSnapshotE3Adapter().project(saved);
        } catch (IllegalArgumentException error) {
            throw new PlanningService.InvalidRequestException("sourceSnapshotId",
                    "is not a supported E3 source: " + error.getMessage());
        }
        var source = projected.snapshot();
        var blockers = E3FeasibilityAudit.noContiguousWindows(source, projected.obligations());
        JsonNode raw = new ObjectMapper().readTree(saved.canonicalPayload());
        Set<UUID> eligible = new HashSet<>(), assigned = new HashSet<>();
        for (JsonNode train : raw.path("trains")) {
            if (Set.of("AVAILABLE", "IN_SERVICE").contains(train.path("status").asText()))
                eligible.add(UUID.fromString(train.path("id").asText()));
        }
        List<Edge> edges = new ArrayList<>();
        for (JsonNode trip : raw.path("fixedTrips")) {
            assigned.add(UUID.fromString(trip.path("train_id").asText()));
            edges.add(new Edge(OffsetDateTime.parse(trip.path("departure_at").asText()).toInstant(), 1));
            edges.add(new Edge(OffsetDateTime.parse(trip.path("arrival_at").asText()).toInstant(), -1));
        }
        edges.sort(Comparator.comparing(Edge::at).thenComparingInt(Edge::change));
        int simultaneous = 0, peak = 0;
        for (Edge edge : edges) {
            simultaneous += edge.change();
            peak = Math.max(peak, simultaneous);
        }
        int eligibleCount = eligible.size();
        eligible.removeAll(assigned);
        return new Assessment(source.scenarioId(), saved.id(), saved.snapshotHash(), source.provenance(),
                source.trains().size(), source.fixedTrips().size(), source.blocks().size(),
                eligibleCount, peak, eligible.size(),
                blockers.isEmpty() ? "NECESSARY_WINDOWS_PRESENT" : "BLOCKED_BY_FIXED_ASSIGNMENTS",
                "NOT_PERFORMED", blockers);
    }

    /** Read-only candidate preview; it never creates a plan or grants D2 PASS. */
    public CandidateAssessment assessCandidate(UUID snapshotId, CandidateInput input) {
        if (input == null || input.frozenUntil() == null || input.effectiveTrainByTrip() == null)
            throw new PlanningService.InvalidRequestException("candidate", "freeze, preparation and assignments are required");
        var saved = snapshots.findById(snapshotId)
                .orElseThrow(() -> new PlanningService.NotFoundException("snapshot not found: " + snapshotId));
        try {
            new SourceSnapshotE3Adapter().project(saved);
            var assignment = new E3TripAssignmentLedger().evaluate(saved, input.effectiveTrainByTrip(),
                    input.frozenUntil(), input.preparationMinutes());
            var reserve = new E3ReserveCoverageAssessment().assess(saved, assignment,
                    input.frozenUntil(), input.preparationMinutes());
            var cleaning = new E3CleaningCoverageAssessment().assess(saved, assignment,
                    input.frozenUntil(), input.preparationMinutes());
            List<PlannerResult.Diagnostic> blockers = new ArrayList<>();
            int obligations = 0;
            String mileageStatus = "CHECKED";
            try {
                var recalculated = new E3MileageObligationRecalculator().recalculate(saved, assignment,
                        input.frozenUntil(), input.preparationMinutes());
                obligations = recalculated.obligations().size();
                for (var issue : new E3MileageObligationAudit().check(saved, assignment, recalculated,
                        input.frozenUntil(), input.preparationMinutes()))
                    blockers.add(new PlannerResult.Diagnostic(issue.code(), issue.message()));
                if (!blockers.isEmpty()) mileageStatus = "FAILED";
            } catch (IllegalArgumentException error) {
                mileageStatus = "BLOCKED";
                blockers.add(new PlannerResult.Diagnostic("E3_MILEAGE_PROJECTION_BLOCKED", error.getMessage()));
            }
            return new CandidateAssessment(saved.scenarioId(), saved.id(), saved.snapshotHash(),
                    assignment.trips().size(), assignment.changedTripCount(), obligations, mileageStatus,
                    "NOT_RUN", "NOT_PERFORMED", reserve, cleaning, List.copyOf(blockers));
        } catch (IllegalArgumentException error) {
            throw new PlanningService.InvalidRequestException("candidate", error.getMessage());
        }
    }

    /** Finds one source-backed rotation candidate; it does not create a plan. */
    public E3RotationCandidateSearch.Candidate searchRotation(UUID snapshotId,
                                                               RotationSearchInput input) {
        if (input == null || input.frozenUntil() == null)
            throw new PlanningService.InvalidRequestException("rotationSearch", "frozenUntil is required");
        var saved = snapshots.findById(snapshotId)
                .orElseThrow(() -> new PlanningService.NotFoundException("snapshot not found: " + snapshotId));
        try {
            return new E3RotationCandidateSearch().search(saved,
                    input.effectiveTrainByTrip() == null ? Map.of() : input.effectiveTrainByTrip(),
                    input.frozenUntil(),
                    input.preparationMinutes(), input.maxEvaluations());
        } catch (IllegalArgumentException error) {
            throw new PlanningService.InvalidRequestException("rotationSearch", error.getMessage());
        }
    }

    /** A maintenance-only CP-SAT solve. Cleaning and full D2 are still pending. */
    public E3MaintenanceCandidateSolver.Result solveMaintenanceCandidate(UUID snapshotId,
            E3MaintenanceCandidateSolver.Input input) {
        var saved = snapshots.findById(snapshotId)
                .orElseThrow(() -> new PlanningService.NotFoundException("snapshot not found: " + snapshotId));
        try {
            return new E3MaintenanceCandidateSolver(new CpSatPlanner()).solve(saved, input);
        } catch (IllegalArgumentException error) {
            throw new PlanningService.InvalidRequestException("maintenanceCandidate", error.getMessage());
        }
    }

    /** One bounded E3 candidate run; no durable plan or D2 approval is produced. */
    public E3JointCandidatePlanner.Result planJointCandidate(UUID snapshotId,
                                                              E3JointCandidatePlanner.Input input) {
        var saved = snapshots.findById(snapshotId)
                .orElseThrow(() -> new PlanningService.NotFoundException("snapshot not found: " + snapshotId));
        try {
            return new E3JointCandidatePlanner(new CpSatPlanner()).plan(saved, input);
        } catch (IllegalArgumentException error) {
            throw new PlanningService.InvalidRequestException("jointCandidate", error.getMessage());
        }
    }
}
