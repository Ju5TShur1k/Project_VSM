package com.vsm.okno.planning;

import java.util.Objects;
import java.util.UUID;

/** Internal worker request. The HTTP job DTO is adapted by F1 after contract review. */
public record PlannerRequest(
        String schemaVersion,
        UUID scenarioId,
        String snapshotHash,
        Policy policy,
        int seed,
        int timeLimitSec
) {
    public PlannerRequest {
        if (!"1.0".equals(schemaVersion)) throw new IllegalArgumentException("unsupported request schemaVersion");
        Objects.requireNonNull(scenarioId, "scenarioId");
        if (snapshotHash == null || snapshotHash.isBlank()) throw new IllegalArgumentException("snapshotHash is required");
        Objects.requireNonNull(policy, "policy");
        if (timeLimitSec <= 0) throw new IllegalArgumentException("timeLimitSec must be positive");
    }

    public enum Policy {
        BLOCKS_CP_SAT,
        WHOLE_CYCLE_CP_SAT,
        WHOLE_CYCLE_EDD
    }
}
