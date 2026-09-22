package com.vsm.okno.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    public record JobStatus(UUID jobId, String status, String solverStatus, UUID planId) {}

    public record PlanEvent(
            UUID id, UUID trainId, String kind, String startAt, String endAt, List<String> resourceIds
    ) {}

    public record Validation(String code, String severity, String message) {}

    public record Plan(
            UUID id, UUID scenarioId, int version, String status,
            List<PlanEvent> events, List<Validation> validations
    ) {}

    public record ApproveRequest(int expectedVersion, String actorId, String comment) {}

    public record ScenarioEvent(String kind, String description) {}

    public record ErrorDetail(String field, String reason) {}

    public record ApiError(String code, String message, String traceId, List<ErrorDetail> details) {}
}
