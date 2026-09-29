package com.vsm.okno.requests;

import tools.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Task 1 boundary: source facts and a request, never a solver-selected service slot. */
public final class RequestDto {
    private RequestDto() {}
    public record Command(UUID scenarioId, Integer expectedVersion, String idempotencyKey,
                          String reason, String source, JsonNode change) {}
    public record Version(UUID rootId, UUID scenarioId, int version, UUID parentScenarioId,
                          UUID snapshotId, String snapshotHash, String createdBy, Instant createdAt) {}
    public record Receipt(UUID id, String kind, UUID trainId, UUID tripId, String reason, String source,
                          String reportedBy, Instant reportedAt, Version version, JsonNode change,
                          String status, UUID jobId, UUID planId, String errorCode, String errorMessage) {}
    public record Update(long sequence, UUID requestId, String status, String actor, UUID jobId,
                         UUID planId, String code, String message, Instant recordedAt) {}
    public record Updates(long nextCursor, List<Update> events) {}
    public record Selection(UUID rootId, UUID latestDraftId, UUID effectivePlanId,
                            boolean effectivePlanUsesCurrentSource) {}
}
