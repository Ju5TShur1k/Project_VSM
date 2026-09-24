package com.vsm.okno.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    public record Validation(String code, String severity, String message) {}

    public record Plan(
            UUID id, UUID scenarioId, int version, String status,
            List<PlanEvent> events, List<Validation> validations, String approvedBy
    ) {}

    // actorId is ignored server-side: the approver is the authenticated user
    // (Plan.approvedBy). Kept only so existing clients don't break.
    public record ApproveRequest(int expectedVersion, String actorId, String comment) {}

    public record ScenarioEvent(String kind, String description) {}

    public record ErrorDetail(String field, String reason) {}

    public record ApiError(String code, String message, String traceId, List<ErrorDetail> details) {}
}
