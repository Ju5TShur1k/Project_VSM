package com.vsm.okno.validation;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.planning.E3TripAssignmentLedger;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Shows the city-specific reserve consumed by an otherwise valid candidate. */
public final class E3ReserveCoverageAssessment {
    public record City(String name, int sourceTarget, int remaining, int deficit) {}
    public record Report(String snapshotHash, int mobilizedTrainCount, List<City> cities) {
        public Report { cities = List.copyOf(cities); }
    }
    private record Interval(UUID train, String city, OffsetDateTime start, OffsetDateTime end) {}

    private final ObjectMapper json = new ObjectMapper();

    public Report assess(SourceSnapshot saved, E3TripAssignmentLedger.Assignment assignment,
                         OffsetDateTime frozenUntil, int preparationMinutes) {
        var issues = new E3TripAssignmentAudit().check(saved, assignment, frozenUntil, preparationMinutes);
        if (!issues.isEmpty())
            throw new IllegalArgumentException("candidate assignment failed D2: " + issues.getFirst().code());
        JsonNode root = json.readTree(saved.canonicalPayload());
        OffsetDateTime start = OffsetDateTime.parse(root.path("scenario").path("horizon_start").asText());
        OffsetDateTime end = OffsetDateTime.parse(root.path("scenario").path("horizon_end").asText());
        Map<UUID, String> reserved = new HashMap<>();
        for (JsonNode train : rows(root, "trains")) {
            if ("RESERVE".equals(train.path("status").asText()))
                reserved.put(UUID.fromString(train.path("id").asText()), train.path("location").asText());
        }
        List<Interval> reservations = new ArrayList<>(), presence = new ArrayList<>();
        for (JsonNode row : rows(root, "trainOccupancy")) {
            if ("RESERVE".equals(row.path("kind").asText()) && confirmed(row))
                reservations.add(interval(row, null));
        }
        for (JsonNode row : rows(root, "trainPresence")) {
            if (confirmed(row)) presence.add(interval(row, row.path("location").asText()));
        }
        Map<String, Integer> target = new HashMap<>(), remaining = new HashMap<>();
        Set<UUID> dispatched = new HashSet<>();
        assignment.trips().forEach(trip -> dispatched.add(trip.effectiveTrainId()));
        for (var entry : reserved.entrySet()) {
            UUID id = entry.getKey(); String city = entry.getValue();
            if (city.isBlank() || !covered(start, end, reservations.stream()
                    .filter(row -> id.equals(row.train()))
                    .map(row -> new OffsetDateTime[]{row.start(), row.end()}).toList())
                    || !covered(start, end, presence.stream()
                    .filter(row -> id.equals(row.train()) && city.equals(row.city()))
                    .map(row -> new OffsetDateTime[]{row.start(), row.end()}).toList()))
                throw new IllegalArgumentException("reserve source lacks full-horizon evidence for " + id);
            target.merge(city, 1, Integer::sum);
            if (!dispatched.contains(id)) remaining.merge(city, 1, Integer::sum);
        }
        List<City> cities = target.entrySet().stream().map(entry -> {
            int available = remaining.getOrDefault(entry.getKey(), 0);
            return new City(entry.getKey(), entry.getValue(), available,
                    entry.getValue() - available);
        }).sorted(Comparator.comparing(City::name)).toList();
        return new Report(saved.snapshotHash(), reserved.size() - remaining.values().stream()
                .mapToInt(Integer::intValue).sum(), cities);
    }

    private static Interval interval(JsonNode row, String city) {
        return new Interval(UUID.fromString(row.path("train_id").asText()), city,
                OffsetDateTime.parse(row.path("starts_at").asText()),
                OffsetDateTime.parse(row.path("ends_at").asText()));
    }
    private static boolean confirmed(JsonNode row) {
        return Set.of("SYNTHETIC", "CONFIRMED").contains(row.path("confirmation_status").asText());
    }
    private static JsonNode rows(JsonNode root, String field) {
        JsonNode rows = root.path(field);
        if (!rows.isArray()) throw new IllegalArgumentException("missing reserve source collection " + field);
        return rows;
    }
    private static boolean covered(OffsetDateTime from, OffsetDateTime to,
                                   List<OffsetDateTime[]> intervals) {
        if (!from.isBefore(to)) return false;
        OffsetDateTime cursor = from;
        for (OffsetDateTime[] interval : intervals.stream()
                .sorted(Comparator.comparing(row -> row[0])).toList()) {
            if (interval[0].isAfter(cursor)) return false;
            if (interval[1].isAfter(cursor)) cursor = interval[1];
            if (!cursor.isBefore(to)) return true;
        }
        return false;
    }
}
