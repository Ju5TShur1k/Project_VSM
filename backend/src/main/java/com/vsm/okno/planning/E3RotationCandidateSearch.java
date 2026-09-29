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
    private record Scored(Map<UUID, UUID> changes, int blocked, int workMinutes) {}
    private record Proposal(UUID blockedId, int first, int last, Reserve reserve,
                            boolean freesWholeWork, int tripCount) {}

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
        Map<String, String> resourceCity = new HashMap<>();
        for (JsonNode row : root.path("resources"))
            resourceCity.put(row.path("id").asText(), row.path("location").asText());
        Map<UUID, List<E3TripAssignmentLedger.AssignedTrip>> byTrain = new HashMap<>();
        for (var trip : original.trips())
            byTrain.computeIfAbsent(trip.effectiveTrainId(), ignored -> new ArrayList<>()).add(trip);
        byTrain.values().forEach(trips -> trips.sort(Comparator
                .comparing(E3TripAssignmentLedger.AssignedTrip::departureAt)
                .thenComparing(E3TripAssignmentLedger.AssignedTrip::id)));
        Map<UUID, ScenarioSnapshot.ServiceBlock> blockById = new HashMap<>();
        for (var block : initial.blocks()) blockById.put(block.id(), block);

        // A rotation only helps if the freed train waits in a city that has a path
        // allowed for its work. Among those, try first the rotations whose freed
        // time (previous arrival .. next departure, minus preparation) holds the
        // whole work, and the shortest of them: a long rotation ties up the reserve
        // for days and adds reserve mileage, which creates new obligations.
        List<List<Proposal>> proposals = new ArrayList<>();
        for (UUID blockedId : blockedIds) {
            var work = blockById.get(blockedId);
            var cities = work.allowedResourceIds().stream().map(resourceCity::get).collect(java.util.stream.Collectors.toSet());
            var trips = byTrain.getOrDefault(work.trainId(), List.of());
            List<Proposal> forWork = new ArrayList<>();
            for (int first = 0; first < trips.size(); first++) {
                var leading = trips.get(first);
                int start = minute(initial, leading.departureAt());
                if (leading.departureAt().isBefore(frozenUntil) || !cities.contains(leading.origin())
                        || start > work.latestEndMinute() - work.durationMinutes()) continue;
                int freeFrom = first == 0 ? 0 : minute(initial, trips.get(first - 1).arrivalAt()) + preparationMinutes;
                // The full rotation must return to its first city, where the freed train waits.
                for (int last = first + 1; last < Math.min(trips.size(), first + 24); last++) {
                    var trailing = trips.get(last);
                    int end = minute(initial, trailing.arrivalAt());
                    if (!leading.origin().equals(trailing.destination())
                            || end < work.earliestStartMinute() + work.durationMinutes()) continue;
                    int freeTo = last + 1 < trips.size()
                            ? minute(initial, trips.get(last + 1).departureAt()) - preparationMinutes
                            : initial.horizonMinutes();
                    boolean whole = longestFree(initial, work.trainId(),
                            Math.max(freeFrom, work.earliestStartMinute()),
                            Math.min(freeTo, work.latestEndMinute())) >= work.durationMinutes();
                    int from = start - preparationMinutes;
                    int to = end + preparationMinutes;
                    for (Reserve reserve : reserves)
                        if (locationAt(initial, byTrain.getOrDefault(reserve.id(), List.of()), reserve.city(), from)
                                .equals(leading.origin())
                                && isIdle(initial, byTrain.getOrDefault(reserve.id(), List.of()), from, to))
                            forWork.add(new Proposal(blockedId, first, last, reserve, whole, last - first + 1));
                }
            }
            forWork.sort(Comparator.comparing(Proposal::freesWholeWork).reversed()
                    .thenComparingInt(Proposal::tripCount)
                    .thenComparing(Proposal::first)
                    .thenComparing(p -> p.reserve().id()));
            if (!forWork.isEmpty()) proposals.add(forWork);
        }
        // Longest works first: they need the most reserve time, so they must not
        // lose the few reserves of their city to short works taken earlier.
        proposals.sort(Comparator.comparingInt((List<Proposal> ps) ->
                blockById.get(ps.get(0).blockedId()).durationMinutes()).reversed());
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
                    int minutes = work.durationMinutes();
                    if (remaining < blockedIds.size() && (best == null || remaining < best.blocked()
                            || (remaining == best.blocked() && (minutes > best.workMinutes()
                            || (minutes == best.workMinutes() && changes.size() < best.changes().size())))))
                        best = new Scored(Map.copyOf(changes), remaining, minutes);
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

    // Longest stretch of [from, to) not covered by the train's own fixed commitments.
    // Source cleanings are skipped: once the train stops running those trips the
    // projection releases the ones that no longer cover a fourth-to-fifth-trip gap.
    private static int longestFree(ScenarioSnapshot snapshot, UUID train, int from, int to) {
        var busy = snapshot.operations().fixedOccupancies().stream()
                .filter(o -> train.equals(o.trainId()) && o.endMinute() > from && o.startMinute() < to
                        && !o.source().endsWith("; CLEANING"))
                .sorted(Comparator.comparingInt(OperationalConstraints.FixedOccupancy::startMinute)).toList();
        int best = 0, cursor = from;
        for (var o : busy) {
            best = Math.max(best, o.startMinute() - cursor);
            cursor = Math.max(cursor, o.endMinute());
        }
        return Math.max(best, to - cursor);
    }

    // Where a reserve actually is at a minute, after the trips it already took over.
    private static String locationAt(ScenarioSnapshot snapshot, List<E3TripAssignmentLedger.AssignedTrip> trips,
                                     String home, int at) {
        String city = home;
        for (var trip : trips) if (minute(snapshot, trip.arrivalAt()) <= at) city = trip.destination();
        return city;
    }

    private static boolean isIdle(ScenarioSnapshot snapshot, List<E3TripAssignmentLedger.AssignedTrip> trips,
                                  int from, int to) {
        return trips.stream().noneMatch(t -> minute(snapshot, t.departureAt()) < to && minute(snapshot, t.arrivalAt()) > from);
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
