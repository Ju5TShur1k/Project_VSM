package com.vsm.okno.data;

import com.vsm.okno.planning.E3FeasibilityAudit;
import com.vsm.okno.planning.MileageObligationGenerator;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.service.PlanningService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/** Read-only assessment of the full-fleet source with its current fixed trip assignments. */
@Service
@Profile("database")
public final class E3AssessmentService {
    public record Assessment(UUID scenarioId, UUID sourceSnapshotId, String snapshotHash,
                             String provenance, int trainCount, int tripCount, int requiredWorkCount,
                             String fixedAssignmentStatus, String d2Status,
                             List<PlannerResult.Diagnostic> blockers) {}

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
        return new Assessment(source.scenarioId(), saved.id(), saved.snapshotHash(), source.provenance(),
                source.trains().size(), source.fixedTrips().size(), source.blocks().size(),
                blockers.isEmpty() ? "NECESSARY_WINDOWS_PRESENT" : "BLOCKED_BY_FIXED_ASSIGNMENTS",
                "NOT_PERFORMED", blockers);
    }
}
