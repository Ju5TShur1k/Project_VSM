package com.vsm.okno.validation;

import com.vsm.okno.dto.Dto;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.ScenarioSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** D2's result is independent of solver status and bound to a source, event content and plan version. */
public record ValidationReport(String schemaVersion, String status, String scope,
                               UUID scenarioId, UUID sourceSnapshotId, String snapshotHash,
                               String resultHash, String policy, String solverStatus, int planVersion, Instant checkedAt,
                               String ruleVersion, String ruleConfirmation,
                               Integer requiredServiceCount, List<PendingMilestone> pendingMilestones,
                               List<Dto.Validation> findings) {
    public ValidationReport {
        pendingMilestones = List.copyOf(pendingMilestones);
        findings = List.copyOf(findings);
    }

    public record PendingMilestone(UUID trainId, String cycleCode, long nominalKm, long remainingKm) {}

    public static ValidationReport of(ScenarioSnapshot source, PlannerResult result, UUID sourceId,
                                      String scope, String ruleVersion, String confirmation,
                                      Integer count, List<PendingMilestone> pending,
                                      List<Dto.Validation> findings) {
        String status = findings.stream().anyMatch(f -> "VALIDATION_NOT_PERFORMED".equals(f.code()))
                ? "NOT_PERFORMED"
                : findings.stream().anyMatch(f -> "CRITICAL".equals(f.severity())) ? "FAILED" : "PASS";
        return new ValidationReport("d2-validation-1.0", status, scope, source.scenarioId(), sourceId,
                source.snapshotHash(), PlanFingerprint.result(result), result.policy().name(), result.solverStatus().name(), 0, Instant.now(), ruleVersion,
                confirmation, count, pending, findings);
    }

    public ValidationReport withFindings(List<Dto.Validation> combined) {
        String updatedStatus = combined.stream().anyMatch(f -> "VALIDATION_NOT_PERFORMED".equals(f.code()))
                ? "NOT_PERFORMED" : combined.stream().anyMatch(f -> "CRITICAL".equals(f.severity())) ? "FAILED" : "PASS";
        return new ValidationReport(schemaVersion, updatedStatus, scope, scenarioId, sourceSnapshotId, snapshotHash,
                resultHash, policy, solverStatus, planVersion, checkedAt, ruleVersion, ruleConfirmation,
                requiredServiceCount, pendingMilestones, combined);
    }
}
