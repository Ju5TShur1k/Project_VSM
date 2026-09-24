package com.vsm.okno.planning;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Solver output only. Independent validation and approval are separate stages. */
public record PlannerResult(
        String schemaVersion,
        UUID scenarioId,
        String snapshotHash,
        PlannerRequest.Policy policy,
        SolverStatus solverStatus,
        List<PlannedBlock> blocks,
        List<Diagnostic> diagnostics,
        int seed,
        long elapsedMillis,
        Double objectiveMinutes
) {
    public PlannerResult {
        if (!"1.0".equals(schemaVersion)) throw new IllegalArgumentException("unsupported result schemaVersion");
        Objects.requireNonNull(scenarioId, "scenarioId");
        Objects.requireNonNull(snapshotHash, "snapshotHash");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(solverStatus, "solverStatus");
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (elapsedMillis < 0) throw new IllegalArgumentException("elapsedMillis must be nonnegative");
        if (solverStatus != SolverStatus.OPTIMAL && solverStatus != SolverStatus.FEASIBLE && !blocks.isEmpty()) {
            throw new IllegalArgumentException("a plan requires a feasible solver status");
        }
    }

    public enum SolverStatus {
        OPTIMAL, FEASIBLE, INFEASIBLE, UNKNOWN, MODEL_INVALID
    }

    public record PlannedBlock(
            UUID blockId,
            UUID trainId,
            String resourceId,
            OffsetDateTime startAt,
            OffsetDateTime endAt
    ) {
        public PlannedBlock {
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(trainId, "trainId");
            Objects.requireNonNull(resourceId, "resourceId");
            Objects.requireNonNull(startAt, "startAt");
            Objects.requireNonNull(endAt, "endAt");
            if (!startAt.isBefore(endAt)) throw new IllegalArgumentException("endAt must follow startAt");
        }
    }

    public record Diagnostic(String code, String message) {
        public Diagnostic {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(message, "message");
        }
    }
}
