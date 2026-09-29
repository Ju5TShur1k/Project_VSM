package com.vsm.okno.data;

import com.vsm.okno.planning.CpSatPlanner;
import com.vsm.okno.planning.E3DraftPlanFactory;
import com.vsm.okno.planning.E3JointFullPlanner;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.service.PlanningService;
import com.vsm.okno.store.DatabasePlanningRepository;
import com.vsm.okno.validation.E3CleaningCoverageAssessment;
import com.vsm.okno.validation.E3ReserveCoverageAssessment;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Profile("database")
public final class E3FullDraftService {
    public record DraftResult(UUID planId, UUID sourceSnapshotId, String snapshotHash,
                              String searchStatus, int moves, int tripCount,
                              int changedTripCount, int requiredCleaningCount,
                              int placedBlockCount, String modelSolverStatus,
                              String structuralStatus, String d2Status,
                              Map<UUID, UUID> effectiveTrainByTrip,
                              List<PlannerResult.Diagnostic> diagnostics,
                              E3ReserveCoverageAssessment.Report reserve,
                              E3CleaningCoverageAssessment.Report cleaning) {
        public DraftResult {
            effectiveTrainByTrip = Map.copyOf(effectiveTrainByTrip);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private final SourceSnapshotRepository snapshots;
    private final DatabasePlanningRepository plans;

    public E3FullDraftService(SourceSnapshotRepository snapshots, DatabasePlanningRepository plans) {
        this.snapshots = snapshots;
        this.plans = plans;
    }

    public DraftResult calculate(UUID snapshotId, E3JointFullPlanner.Input input, String actor) {
        var saved = snapshots.findById(snapshotId)
                .orElseThrow(() -> new PlanningService.NotFoundException("snapshot not found: " + snapshotId));
        E3JointFullPlanner.Result found;
        try {
            found = new E3JointFullPlanner(new CpSatPlanner()).plan(saved, input);
        } catch (IllegalArgumentException error) {
            throw new PlanningService.InvalidRequestException("e3FullDraft", error.getMessage());
        }
        UUID planId = null;
        if ("MODEL_CANDIDATE_FOUND".equals(found.searchStatus())) {
            var draft = new E3DraftPlanFactory().create(found);
            planId = plans.saveE3Draft(draft, snapshotId, actor).id;
        }
        var model = found.plan();
        return new DraftResult(planId, snapshotId, saved.snapshotHash(),
                found.searchStatus(), found.moves(), model.tripCount(), model.changedTripCount(),
                model.requiredCleaningCount(), model.blocks().size(), model.modelSolverStatus(),
                model.structuralStatus(), model.d2Status(), found.effectiveTrainByTrip(),
                model.diagnostics(), model.reserve(), model.cleaning());
    }
}
