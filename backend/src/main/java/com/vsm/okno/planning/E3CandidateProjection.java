package com.vsm.okno.planning;

import com.vsm.okno.data.SourceSnapshotE3Adapter;
import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.validation.E3MileageObligationAudit;
import com.vsm.okno.validation.E3TripAssignmentAudit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Rebuilds train presence and mileage work for a verified E3 trip candidate. */
public final class E3CandidateProjection {
    public record Projection(ScenarioSnapshot snapshot,
                             List<MileageObligationGenerator.Obligation> obligations) {
        public Projection { obligations = List.copyOf(obligations); }
    }

    private record ResourceWindow(String id, String city, int start, int end) {}
    private record Presence(String city, int start, int end) {}
    private final ObjectMapper json = new ObjectMapper();

    public Projection project(SourceSnapshot saved, E3TripAssignmentLedger.Assignment assignment,
                              OffsetDateTime frozenUntil, int preparationMinutes) {
        var source = new SourceSnapshotE3Adapter().project(saved).snapshot();
        return project(saved, source, assignment, frozenUntil, preparationMinutes);
    }

    Projection project(SourceSnapshot saved, ScenarioSnapshot source,
                       E3TripAssignmentLedger.Assignment assignment,
                       OffsetDateTime frozenUntil, int preparationMinutes) {
        if (!source.scenarioId().equals(saved.scenarioId())
                || !source.snapshotHash().equals(saved.snapshotHash()))
            throw new IllegalArgumentException("E3 projection uses another source snapshot");
        var tripIssues = new E3TripAssignmentAudit().check(saved, assignment, frozenUntil,
                preparationMinutes);
        if (!tripIssues.isEmpty())
            throw new IllegalArgumentException("invalid E3 assignment: " + tripIssues.getFirst().code());
        var recalculated = new E3MileageObligationRecalculator().recalculate(saved, assignment,
                frozenUntil, preparationMinutes);
        var mileageIssues = new E3MileageObligationAudit().check(saved, assignment, recalculated,
                frozenUntil, preparationMinutes);
        if (!mileageIssues.isEmpty())
            throw new IllegalArgumentException("invalid E3 mileage: " + mileageIssues.getFirst().code());

        JsonNode root = json.readTree(saved.canonicalPayload());
        OffsetDateTime horizonStart = source.horizonStart();
        int horizon = source.horizonMinutes();
        Map<UUID, String> status = new HashMap<>();
        for (JsonNode train : root.path("trains"))
            status.put(UUID.fromString(train.path("id").asText()), train.path("status").asText());
        List<ResourceWindow> resourceWindows = new ArrayList<>();
        Map<String, String> cityByResource = new HashMap<>();
        for (JsonNode resource : root.path("resources"))
            cityByResource.put(resource.path("id").asText(), resource.path("location").asText());
        for (JsonNode row : root.path("resourceAvailability")) {
            String id = row.path("resource_id").asText();
            String city = cityByResource.get(id);
            if (city == null) throw new IllegalArgumentException("unknown resource availability " + id);
            resourceWindows.add(new ResourceWindow(id, city,
                    minute(horizonStart, row.path("starts_at").asText()),
                    minute(horizonStart, row.path("ends_at").asText())));
        }

        Map<UUID, List<E3TripAssignmentLedger.AssignedTrip>> byTrain = new HashMap<>();
        Set<UUID> changedTrains = new HashSet<>();
        for (var trip : assignment.trips()) {
            byTrain.computeIfAbsent(trip.effectiveTrainId(), ignored -> new ArrayList<>()).add(trip);
            if (!trip.plannedTrainId().equals(trip.effectiveTrainId())) {
                changedTrains.add(trip.plannedTrainId());
                changedTrains.add(trip.effectiveTrainId());
            }
        }
        List<OperationalConstraints.ServiceWindow> windows = new ArrayList<>();
        for (var trace : assignment.trains().values()) {
            UUID train = trace.trainId();
            if (!changedTrains.contains(train)
                    || !Set.of("AVAILABLE", "IN_SERVICE", "RESERVE").contains(status.get(train))) {
                // Keep D1 evidence for unaffected trains. Depot/failed trains still
                // need an explicit release fact before their windows can change.
                source.operations().serviceWindows().stream()
                        .filter(window -> train.equals(window.trainId())).forEach(windows::add);
                continue;
            }
            String city = trace.initialLocation();
            int ready = 0;
            var trips = byTrain.getOrDefault(train, List.of()).stream()
                    .sorted(Comparator.comparing(E3TripAssignmentLedger.AssignedTrip::departureAt)
                            .thenComparing(E3TripAssignmentLedger.AssignedTrip::id)).toList();
            for (var trip : trips) {
                int departure = minute(horizonStart, trip.departureAt());
                addWindows(windows, resourceWindows, train, new Presence(city, ready,
                        departure - preparationMinutes));
                city = trip.destination();
                ready = minute(horizonStart, trip.arrivalAt()) + preparationMinutes;
            }
            addWindows(windows, resourceWindows, train, new Presence(city, ready, horizon));
        }

        var original = source.operations();
        // Reserve use is measured by the separate city assessment. This candidate
        // model must not silently apply the old four-train hard reserve constraint.
        var operations = new OperationalConstraints(Set.of(), original.fixedOccupancies(),
                windows, original.frozenPlacements(), original.releaseRequirements(), null);
        var snapshot = new ScenarioSnapshot("1.3", source.scenarioId(), saved.snapshotHash(),
                source.provenance() + "; E3 candidate with reassigned trips", horizonStart,
                source.horizonEnd(), source.trains(), source.resources(), recalculated.blocks(),
                recalculated.effectiveTrips(), operations);
        return new Projection(snapshot, recalculated.obligations());
    }

    private static void addWindows(List<OperationalConstraints.ServiceWindow> target,
                                   List<ResourceWindow> resources, UUID train, Presence presence) {
        if (presence.end() <= presence.start()) return;
        for (var resource : resources) {
            if (!resource.city().equals(presence.city())) continue;
            int start = Math.max(presence.start(), resource.start());
            int end = Math.min(presence.end(), resource.end());
            if (start < end)
                target.add(new OperationalConstraints.ServiceWindow(train, resource.id(), start, end,
                        "Derived from verified E3 trip trace and D1 resource availability"));
        }
    }

    private static int minute(OffsetDateTime origin, String value) {
        return minute(origin, OffsetDateTime.parse(value));
    }

    private static int minute(OffsetDateTime origin, OffsetDateTime value) {
        Duration elapsed = Duration.between(origin, value);
        if (elapsed.isNegative() || elapsed.getNano() != 0 || elapsed.getSeconds() % 60 != 0)
            throw new IllegalArgumentException("E3 time is outside whole-minute source horizon: " + value);
        return Math.toIntExact(elapsed.toMinutes());
    }
}
