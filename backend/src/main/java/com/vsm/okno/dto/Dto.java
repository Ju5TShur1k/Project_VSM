package com.vsm.okno.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.vsm.okno.validation.ValidationReport;
import com.vsm.okno.validation.PlanMetrics;

// Hand-written to match contracts/openapi.yaml — that file is the source of
// truth, keep both in sync manually when the contract changes.
public final class Dto {
    private Dto() {}

    public record Train(
            UUID id,
            String externalId,
            String status,
            double mileageKm,
            String nextObligation
    ) {}

    public record ImportRequest(List<Train> trains) {}

    public record ImportResponse(UUID scenarioId, List<String> warnings, String provenance) {}

    public record DemoSource(UUID scenarioId, UUID snapshotId, String snapshotHash, String provenance) {}
    public record CaseDataset(DemoSource source, String dataset, int trainCount, int tripCount,
                              boolean planningSupported, List<String> warnings) {}
    public record TripTimeChange(int arrivalMinute) {}

    public record Scenario(UUID id, Instant createdAt, String provenance, int trainCount) {}

    public record JobRequest(
            UUID scenarioId,
            String policy,
            long seed,
            int timeLimitSec,
            Instant frozenUntil,
            String idempotencyKey
    ) {}

    // status is the job's lifecycle (QUEUED/RUNNING/SUCCEEDED/...), solverStatus
    // is the CP-SAT outcome (FEASIBLE/INFEASIBLE/...) — SUCCEEDED never implies
    // a usable plan, the two are independent per the spec.
    // error is set only when status is FAILED (planner crash / invalid input).
    public record JobStatus(UUID jobId, String status, String solverStatus, UUID planId, String error) {}

    public record PlanEvent(
            UUID id, UUID trainId, String kind, String startAt, String endAt, List<String> resourceIds
    ) {}

    public record Validation(String code, String severity, String message,
                             String objectId, String startAt, String endAt) {
        public Validation(String code, String severity, String message) {
            this(code, severity, message, null, null, null);
        }
    }

    public record Plan(
            UUID id, UUID scenarioId, int version, String status,
            List<PlanEvent> events, List<Validation> validations, String approvedBy,
            String snapshotHash, String validationStatus, ValidationReport validationReport, PlanMetrics metrics
    ) {}

    public record CalendarEvent(UUID id, String kind, UUID trainId, String resourceId,
                                String label, String startAt, String endAt, String source,
                                String reason, String cycleCode, List<String> covers,
                                Long releaseOdometerKm, Long dueOdometerKm, String dueAt) {}

    public record PlanCalendar(UUID scenarioId, String snapshotHash, String provenance,
                               String policy, String solverStatus, String validationStatus,
                               boolean independentlyValidated, String horizonStart,
                               String horizonEnd, List<CalendarLane> trains,
                               List<CalendarLane> resources, List<CalendarEvent> events) {}

    public record CalendarLane(String id, String label) {}

    // actorId is ignored server-side: the approver is the authenticated user
    // (Plan.approvedBy). Kept only so existing clients don't break.
    public record ApproveRequest(int expectedVersion, String actorId, String comment) {}

    public record ScenarioEvent(String kind, String description) {}

    // Dispatcher's report of a change the plan has to account for (trip moved,
    // urgent maintenance, equipment down). Logged for the planner, not fed to the solver.
    public record Incident(UUID id, String train, String kind, String description,
                           String reportedBy, Instant reportedAt) {}
    public record IncidentRequest(String train, String kind, String description) {}
    public record CurrentPlan(UUID planId) {}
    public record Me(String username, String role) {}

    public record ErrorDetail(String field, String reason) {}

    public record ApiError(String code, String message, String traceId, List<ErrorDetail> details) {}
}
