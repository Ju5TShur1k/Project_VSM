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
        int timeLimitSec,
        int frozenUntilMinute
) {
    public PlannerRequest(String schemaVersion, UUID scenarioId, String snapshotHash, Policy policy,
                          int seed, int timeLimitSec) {
        this(schemaVersion, scenarioId, snapshotHash, policy, seed, timeLimitSec, 0);
    }

    public PlannerRequest {
        if (!"1.0".equals(schemaVersion) && !"1.1".equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported request schemaVersion");
        }
        Objects.requireNonNull(scenarioId, "scenarioId");
        if (snapshotHash == null || snapshotHash.isBlank()) throw new IllegalArgumentException("snapshotHash is required");
        Objects.requireNonNull(policy, "policy");
        if (timeLimitSec <= 0) throw new IllegalArgumentException("timeLimitSec must be positive");
        if (frozenUntilMinute < 0 || ("1.0".equals(schemaVersion) && frozenUntilMinute != 0)) {
            throw new IllegalArgumentException("frozenUntilMinute requires request schemaVersion 1.1");
        }
    }

    public void validateAgainst(ScenarioSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (!scenarioId.equals(snapshot.scenarioId()) || !snapshotHash.equals(snapshot.snapshotHash())) {
            throw new IllegalArgumentException("request does not match snapshot id/hash");
        }
        if (frozenUntilMinute > snapshot.horizonMinutes()
                || (frozenUntilMinute > 0 && !"1.2".equals(snapshot.schemaVersion())
                && !"1.3".equals(snapshot.schemaVersion()) && !"1.4".equals(snapshot.schemaVersion()))) {
            throw new IllegalArgumentException("frozenUntilMinute requires an E3 snapshot and must fit the horizon");
        }
    }

    public enum Policy {
        BLOCKS_CP_SAT,
        WHOLE_CYCLE_CP_SAT,
        WHOLE_CYCLE_EDD
    }
}
