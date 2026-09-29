package com.vsm.okno.planning;

import com.vsm.okno.data.SourceSnapshotE3Adapter;
import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.validation.E3CleaningCoverageAssessment;
import com.vsm.okno.validation.E3ReserveCoverageAssessment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Bounded search for a whole return rotation that frees a maintenance window. */
public final class E3RotationCandidateSearch {
    public record Candidate(UUID scenarioId, UUID sourceSnapshotId, String snapshotHash,
                            String searchStatus, String solverStatus, String d2Status,
                            int evaluatedAssignments, int initialBlockedWorks, int remainingBlockedWorks,
                            Map<UUID, UUID> effectiveTrainByTrip,
                            E3ReserveCoverageAssessment.Report reserve,
                            E3CleaningCoverageAssessment.Report cleaning) {
        public Candidate { effectiveTrainByTrip = Map.copyOf(effectiveTrainByTrip); }
    }
    private record Reserve(UUID id, String city) {}
    private record Scored(Map<UUID, UUID> changes, int blocked) {}
    private record Proposal(UUID blockedId, int first, int last, Reserve reserve,
                            int overlapMinutes, int tripCount) {}

    private final ObjectMapper json = new ObjectMapper();

    /**
     * Finds one improvement only. It does not schedule cleaning or establish a full
     * solution; subsequent moves and the CP-SAT/D2 stages remain separate.
     */
    public Candidate search(SourceSnapshot saved, OffsetDateTime frozenUntil,
                            int preparationMinutes, int maxEvaluations) {
        return search(saved, Map.of(), frozenUntil, preparationMinutes, maxEvaluations);
    }

    public Candidate search(SourceSnapshot saved, Map<UUID, UUID> currentAssignments,
                            OffsetDateTime frozenUntil, int preparationMinutes, int maxEvaluations) {
        if (maxEvaluations < 1 || maxEvaluations > 32)
            throw new IllegalArgumentException("maxEvaluations must be between 1 and 32");
        if (preparationMinutes < 0 || preparationMinutes > 1440)
            throw new IllegalArgumentException("preparationMinutes must be between 0 and 1440");
        if (currentAssignments == null) throw new IllegalArgumentException("assignments are required");
        var ledger = new E3TripAssignmentLedger();
        var projector = new E3CandidateProjection();
        var original = ledger.evaluate(saved, currentAssignments, frozenUntil, preparationMinutes);
        var source = new SourceSnapshotE3Adapter().project(saved).snapshot();
        var initial = projector.project(saved, source, original, frozenUntil,
                preparationMinutes).snapshot();
        List<UUID> blockedIds = E3FeasibilityAudit.blockedBlockIds(initial);
        if (blockedIds.isEmpty()) return result(saved, "NO_FIXED_WINDOW_BLOCKER", 0, 0,
                currentAssignments, original, frozenUntil, preparationMinutes);

        JsonNode root = json.readTree(saved.canonicalPayload());
        List<Reserve> reserves = new ArrayList<>();
        for (JsonNode row : root.path("trains")) {
            if ("RESERVE".equals(row.path("status").asText()))
                reserves.add(new Reserve(UUID.fromString(row.path("id").asText()),
                        row.path("location").asText()));
        }
        reserves.sort(Comparator.comparing(Reserve::id));
        Map<UUID, List<E3TripAssignmentLedger.AssignedTrip>> byTrain = new HashMap<>();
        for (var trip : original.trips())
            byTrain.computeIfAbsent(trip.effectiveTrainId(), ignored -> new ArrayList<>()).add(trip);
        byTrain.values().forEach(trips -> trips.sort(Comparator
                .comparing(E3TripAssignmentLedger.AssignedTrip::departureAt)
                .thenComparing(E3TripAssignmentLedger.AssignedTrip::id)));
        Map<UUID, ScenarioSnapshot.ServiceBlock> blockById = new HashMap<>();
        for (var block : initial.blocks()) blockById.put(block.id(), block);

        // Work near the end of the horizon used to be starved by the first 32
        // chronological rotations. Rank complete return rotations by the time
        // they free inside the blocked work's own window, then share the bounded
        // evaluation budget among distinct blocked trains.
        List<List<Proposal>> proposals = new ArrayList<>();
        for (UUID blockedId : blockedIds) {
            var work = blockById.get(blockedId);
            var trips = byTrain.getOrDefault(work.trainId(), List.of());
            List<Proposal> forWork = new ArrayList<>();
            for (int first = 0; first < trips.size(); first++) {
                var leading = trips.get(first);
                int start = minute(initial, leading.departureAt());
                if (leading.departureAt().isBefore(frozenUntil)
                        || start > work.latestEndMinute() - work.durationMinutes()) continue;
                // A long IS530/IS540 can need more than two days of transferred
                // trips. The full rotation must still return to its first city.
                for (int last = first + 1; last < Math.min(trips.size(), first + 24); last++) {
                    var trailing = trips.get(last);
                    int end = minute(initial, trailing.arrivalAt());
                    if (!leading.origin().equals(trailing.destination())
                            || end < work.earliestStartMinute() + work.durationMinutes()) continue;
                    int overlap = Math.min(end, work.latestEndMinute())
                            - Math.max(start, work.earliestStartMinute());
                    // Existing free time directly before or after this rotation
                    // can complete the maintenance interval, so overlap is a
                    // ranking hint and not a validity condition.
                    for (Reserve reserve : reserves) if (reserve.city().equals(leading.origin()))
                        forWork.add(new Proposal(blockedId, first, last, reserve,
                                overlap, last - first + 1));
                }
            }
            forWork.sort(Comparator.comparingInt(Proposal::overlapMinutes).reversed()
                    .thenComparingInt(Proposal::tripCount)
                    .thenComparing(Proposal::first)
                    .thenComparing(p -> p.reserve().id()));
            if (!forWork.isEmpty()) proposals.add(forWork);
        }
        Scored best = null;
        int evaluated = 0;
        for (int rank = 0; evaluated < maxEvaluations; rank++) {
            boolean anyAtRank = false;
            for (List<Proposal> forWork : proposals) {
                if (rank >= forWork.size()) continue;
                anyAtRank = true;
                var proposal = forWork.get(rank);
                var work = blockById.get(proposal.blockedId());
                var trips = byTrain.get(work.trainId());
                Map<UUID, UUID> changes = new HashMap<>(currentAssignments);
                for (int index = proposal.first(); index <= proposal.last(); index++)
                    changes.put(trips.get(index).id(), proposal.reserve().id());
                if (changes.equals(currentAssignments)) continue;
                evaluated++;
                try {
                    var assignment = ledger.evaluate(saved, changes, frozenUntil, preparationMinutes);
                    var projected = projector.project(saved, source, assignment,
                            frozenUntil, preparationMinutes).snapshot();
                    int remaining = E3FeasibilityAudit.blockedBlockIds(projected).size();
                    if (remaining < blockedIds.size() && (best == null || remaining < best.blocked()
                            || (remaining == best.blocked()
                            && changes.size() < best.changes().size())))
                        best = new Scored(Map.copyOf(changes), remaining);
                } catch (IllegalArgumentException rejected) {
                    // Ledger, mileage audit or source evidence rejected this rotation.
                }
                if (evaluated >= maxEvaluations) break;
            }
            if (!anyAtRank) break;
        }
        if (best == null) return result(saved, "NO_IMPROVING_ROTATION", evaluated,
                blockedIds.size(), currentAssignments, original, frozenUntil, preparationMinutes);
        var assignment = ledger.evaluate(saved, best.changes(), frozenUntil, preparationMinutes);
        return result(saved, "IMPROVING_ROTATION_FOUND", evaluated, best.blocked(),
                best.changes(), assignment, frozenUntil, preparationMinutes, blockedIds.size());
    }

    private static int minute(ScenarioSnapshot snapshot, OffsetDateTime at) {
        return Math.toIntExact(java.time.Duration.between(snapshot.horizonStart(), at).toMinutes());
    }

    private static Candidate result(SourceSnapshot saved, String status, int evaluated,
                                    int blocked, Map<UUID, UUID> changes,
                                    E3TripAssignmentLedger.Assignment assignment,
                                    OffsetDateTime frozenUntil, int preparationMinutes) {
        return result(saved, status, evaluated, blocked, changes, assignment,
                frozenUntil, preparationMinutes, blocked);
    }

    private static Candidate result(SourceSnapshot saved, String status, int evaluated,
                                    int blocked, Map<UUID, UUID> changes,
                                    E3TripAssignmentLedger.Assignment assignment,
                                    OffsetDateTime frozenUntil, int preparationMinutes, int initialBlocked) {
        return new Candidate(saved.scenarioId(), saved.id(), saved.snapshotHash(), status,
                "NOT_RUN", "NOT_PERFORMED", evaluated, initialBlocked, blocked, changes,
                new E3ReserveCoverageAssessment().assess(saved, assignment,
                        frozenUntil, preparationMinutes),
                new E3CleaningCoverageAssessment().assess(saved, assignment,
                        frozenUntil, preparationMinutes));
    }
}
