package com.vsm.okno.data;

import com.vsm.okno.planning.E3FeasibilityAudit;
import com.vsm.okno.planning.MileageObligationGenerator;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.service.PlanningService;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Read-only assessment of the full-fleet source with its current fixed trip assignments. */
@Service
@Profile("database")
public final class E3AssessmentService {
    public record Assessment(UUID scenarioId, UUID sourceSnapshotId, String snapshotHash,
                             String provenance, int trainCount, int tripCount, int requiredWorkCount,
                             int eligibleTrainCount, int peakConcurrentTrips, int unassignedEligibleTrainCount,
                             String fixedAssignmentStatus, String d2Status,
                             List<PlannerResult.Diagnostic> blockers) {}
    private record Edge(Instant at, int change) {}

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
        JsonNode raw = new ObjectMapper().readTree(saved.canonicalPayload());
        Set<UUID> eligible = new HashSet<>(), assigned = new HashSet<>();
        for (JsonNode train : raw.path("trains")) {
            if (Set.of("AVAILABLE", "IN_SERVICE").contains(train.path("status").asText()))
                eligible.add(UUID.fromString(train.path("id").asText()));
        }
        List<Edge> edges = new ArrayList<>();
        for (JsonNode trip : raw.path("fixedTrips")) {
            assigned.add(UUID.fromString(trip.path("train_id").asText()));
            edges.add(new Edge(OffsetDateTime.parse(trip.path("departure_at").asText()).toInstant(), 1));
            edges.add(new Edge(OffsetDateTime.parse(trip.path("arrival_at").asText()).toInstant(), -1));
        }
        edges.sort(Comparator.comparing(Edge::at).thenComparingInt(Edge::change));
        int simultaneous = 0, peak = 0;
        for (Edge edge : edges) {
            simultaneous += edge.change();
            peak = Math.max(peak, simultaneous);
        }
        int eligibleCount = eligible.size();
        eligible.removeAll(assigned);
        return new Assessment(source.scenarioId(), saved.id(), saved.snapshotHash(), source.provenance(),
                source.trains().size(), source.fixedTrips().size(), source.blocks().size(),
                eligibleCount, peak, eligible.size(),
                blockers.isEmpty() ? "NECESSARY_WINDOWS_PRESENT" : "BLOCKED_BY_FIXED_ASSIGNMENTS",
                "NOT_PERFORMED", blockers);
    }
}
