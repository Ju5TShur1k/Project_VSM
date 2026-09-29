package com.vsm.okno.validation;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.planning.E3TripAssignmentLedger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Detects which fixed cleaning slots cease to cover the fourth-to-fifth-trip
 * obligation after reassignment. It does not schedule replacement cleaning.
 */
public final class E3CleaningCoverageAssessment {
    public record Missing(UUID trainId, UUID fourthTripId, UUID nextTripId,
                          OffsetDateTime earliestStart, OffsetDateTime latestEnd) {}
    public record Pending(UUID trainId, UUID fourthTripId) {}
    public record Report(String snapshotHash, int coveredCount, List<Missing> missing,
                         List<Pending> pendingAtHorizon) {
        public Report {
            missing = List.copyOf(missing);
            pendingAtHorizon = List.copyOf(pendingAtHorizon);
        }
    }
    private record Counter(OffsetDateTime observedAt, int trips) {}
    private record Slot(UUID trainId, OffsetDateTime start, OffsetDateTime end) {}

    private final ObjectMapper json = new ObjectMapper();

    public Report assess(SourceSnapshot saved, E3TripAssignmentLedger.Assignment assignment,
                         OffsetDateTime frozenUntil, int preparationMinutes) {
        var issues = new E3TripAssignmentAudit().check(saved, assignment, frozenUntil, preparationMinutes);
        if (!issues.isEmpty())
            throw new IllegalArgumentException("candidate assignment failed D2: " + issues.getFirst().code());
        JsonNode root = json.readTree(saved.canonicalPayload());
        OffsetDateTime horizonStart = OffsetDateTime.parse(
                root.path("scenario").path("horizon_start").asText());
        Map<UUID, Counter> initial = new HashMap<>();
        for (JsonNode row : rows(root, "cleaningCounters")) {
            UUID train = UUID.fromString(row.path("train_id").asText());
            OffsetDateTime at = OffsetDateTime.parse(row.path("observed_at").asText());
            if (at.isAfter(horizonStart))
                throw new IllegalArgumentException("in-horizon cleaning counter needs execution events");
            if (!confirmed(row)) continue;
            int count = row.path("completed_trips_since_cleaning").asInt(-1);
            if (count < 0 || count > 3) throw new IllegalArgumentException("invalid cleaning counter for " + train);
            initial.compute(train, (id, old) -> old == null || at.isAfter(old.observedAt())
                    ? new Counter(at, count) : old);
        }
        List<Slot> slots = new ArrayList<>();
        for (JsonNode row : rows(root, "trainOccupancy")) {
            if (!"CLEANING".equals(row.path("kind").asText()) || !confirmed(row)) continue;
            OffsetDateTime from = OffsetDateTime.parse(row.path("starts_at").asText());
            OffsetDateTime to = OffsetDateTime.parse(row.path("ends_at").asText());
            if (!from.isBefore(to)) throw new IllegalArgumentException("invalid fixed cleaning slot");
            slots.add(new Slot(UUID.fromString(row.path("train_id").asText()), from, to));
        }
        Map<UUID, List<E3TripAssignmentLedger.AssignedTrip>> byTrain = new HashMap<>();
        for (var trip : assignment.trips())
            byTrain.computeIfAbsent(trip.effectiveTrainId(), ignored -> new ArrayList<>()).add(trip);
        int covered = 0;
        List<Missing> missing = new ArrayList<>();
        List<Pending> pending = new ArrayList<>();
        for (var entry : byTrain.entrySet()) {
            UUID train = entry.getKey();
            Counter counter = initial.get(train);
            if (counter == null) throw new IllegalArgumentException("missing accepted cleaning counter for " + train);
            var trips = entry.getValue().stream().sorted(Comparator
                    .comparing(E3TripAssignmentLedger.AssignedTrip::departureAt)
                    .thenComparing(E3TripAssignmentLedger.AssignedTrip::id)).toList();
            int since = counter.trips();
            for (int index = 0; index < trips.size(); index++) {
                if (++since != 4) continue;
                var fourth = trips.get(index);
                if (index + 1 == trips.size()) {
                    pending.add(new Pending(train, fourth.id()));
                    break;
                }
                var next = trips.get(index + 1);
                boolean fixedSlot = slots.stream().anyMatch(slot -> train.equals(slot.trainId())
                        && !slot.start().isBefore(fourth.arrivalAt())
                        && !slot.end().isAfter(next.departureAt()));
                if (fixedSlot) covered++;
                else missing.add(new Missing(train, fourth.id(), next.id(),
                        fourth.arrivalAt(), next.departureAt()));
                since = 0;
            }
        }
        missing.sort(Comparator.comparing(Missing::earliestStart).thenComparing(Missing::trainId));
        pending.sort(Comparator.comparing(Pending::trainId));
        return new Report(saved.snapshotHash(), covered, missing, pending);
    }

    private static JsonNode rows(JsonNode root, String field) {
        JsonNode rows = root.path(field);
        if (!rows.isArray()) throw new IllegalArgumentException("missing cleaning source collection " + field);
        return rows;
    }
    private static boolean confirmed(JsonNode row) {
        return Set.of("SYNTHETIC", "CONFIRMED").contains(row.path("confirmation_status").asText());
    }
}
