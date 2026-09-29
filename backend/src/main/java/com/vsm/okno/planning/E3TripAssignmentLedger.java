package com.vsm.okno.planning;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.validation.PlanFingerprint;
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

/**
 * Candidate trip-assignment ledger for E3. The saved D1 trips remain immutable;
 * the map records proposed effective trains. It validates physical continuity
 * and recomputes mileage, but does not claim maintenance or D2 feasibility.
 */
public final class E3TripAssignmentLedger {
    public record AssignedTrip(UUID id, UUID plannedTrainId, UUID effectiveTrainId, String label,
                               String origin, String destination, OffsetDateTime departureAt,
                               OffsetDateTime arrivalAt, long distanceKm) {}
    public record MileageAtTrip(UUID tripId, long beforeKm, long afterKm) {}
    public record TrainTrace(UUID trainId, String externalId, String initialLocation,
                             String finalLocation, long initialKm, long finalKm,
                             List<MileageAtTrip> trips) {
        public TrainTrace { trips = List.copyOf(trips); }
    }
    public record Assignment(UUID scenarioId, UUID sourceSnapshotId, String snapshotHash,
                             OffsetDateTime frozenUntil, int preparationMinutes,
                             List<AssignedTrip> trips, Map<UUID, TrainTrace> trains,
                             int changedTripCount) {
        public Assignment {
            trips = List.copyOf(trips);
            trains = Map.copyOf(trains);
        }
    }
    private record TrainSource(UUID id, String name, String location, String status, long initialKm) {}
    private record Busy(UUID trainId, OffsetDateTime from, OffsetDateTime to, String kind) {}

    private final ObjectMapper json = new ObjectMapper();

    public Assignment evaluate(SourceSnapshot saved, Map<UUID, UUID> effectiveTrainByTrip,
                               OffsetDateTime frozenUntil, int preparationMinutes) {
        if (preparationMinutes < 0) throw new IllegalArgumentException("negative preparationMinutes");
        if (!"d1-source-1.0".equals(saved.schemaVersion())
                || !"pg-jsonb-text-v1".equals(saved.canonicalization())
                || !PlanFingerprint.sha256(saved.canonicalPayload()).equals(saved.snapshotHash()))
            throw new IllegalArgumentException("unsupported or corrupted source snapshot");
        JsonNode root = json.readTree(saved.canonicalPayload());
        if (!saved.scenarioId().equals(uuid(root, "scenarioId"))
                || !saved.schemaVersion().equals(text(root, "schemaVersion"))
                || !saved.canonicalization().equals(text(root, "canonicalization")))
            throw new IllegalArgumentException("source scenario mismatch");
        JsonNode scenario = root.path("scenario");
        if (!saved.scenarioId().equals(uuid(scenario, "id")))
            throw new IllegalArgumentException("scenario row mismatch");
        OffsetDateTime start = time(scenario, "horizon_start"), end = time(scenario, "horizon_end");
        if (frozenUntil == null || frozenUntil.isBefore(start) || frozenUntil.isAfter(end))
            throw new IllegalArgumentException("frozenUntil must lie inside the source horizon");

        Map<UUID, Long> initialKm = new HashMap<>();
        Map<UUID, OffsetDateTime> latestReading = new HashMap<>();
        for (JsonNode row : rows(root, "odometerReadings")) {
            UUID train = uuid(row, "train_id");
            OffsetDateTime observed = time(row, "observed_at");
            if (observed.isAfter(start))
                throw new IllegalArgumentException("in-horizon odometer updates need a newer ledger");
            if (!latestReading.containsKey(train) || observed.isAfter(latestReading.get(train))) {
                latestReading.put(train, observed);
                initialKm.put(train, number(row, "odometer_km"));
            }
        }
        Map<UUID, TrainSource> trains = new HashMap<>();
        for (JsonNode row : rows(root, "trains")) {
            UUID id = uuid(row, "id");
            Long mileage = initialKm.get(id);
            if (mileage == null || mileage < 0
                    || trains.putIfAbsent(id, new TrainSource(id, text(row, "external_id"),
                    text(row, "location"), text(row, "status"), mileage)) != null)
                throw new IllegalArgumentException("invalid train or odometer " + id);
        }

        List<Busy> busy = new ArrayList<>();
        for (JsonNode row : rows(root, "trainOccupancy")) {
            if ("RESERVE".equals(text(row, "kind"))) continue;
            busy.add(new Busy(uuid(row, "train_id"), time(row, "starts_at"),
                    time(row, "ends_at"), text(row, "kind")));
        }
        for (JsonNode row : rows(root, "frozenWork")) busy.add(new Busy(uuid(row, "train_id"),
                time(row, "starts_at"), time(row, "ends_at"), "FROZEN"));

        Map<UUID, UUID> requested = Map.copyOf(effectiveTrainByTrip);
        Set<UUID> sourceTripIds = new HashSet<>();
        List<AssignedTrip> trips = new ArrayList<>();
        int changed = 0;
        for (JsonNode row : rows(root, "fixedTrips")) {
            UUID id = uuid(row, "id"), planned = uuid(row, "train_id");
            if (!sourceTripIds.add(id) || !trains.containsKey(planned))
                throw new IllegalArgumentException("duplicate trip or unknown planned train " + id);
            UUID effective = requested.getOrDefault(id, planned);
            TrainSource train = trains.get(effective);
            if (train == null || !Set.of("AVAILABLE", "IN_SERVICE").contains(train.status()))
                throw new IllegalArgumentException("effective train has no release/availability evidence for trip " + id);
            OffsetDateTime departure = time(row, "departure_at"), arrival = time(row, "arrival_at");
            if (departure.isBefore(start) || arrival.isAfter(end) || !departure.isBefore(arrival))
                throw new IllegalArgumentException("trip outside source horizon " + id);
            if (!effective.equals(planned)) {
                if (departure.isBefore(frozenUntil))
                    throw new IllegalArgumentException("trip assignment lies before frozenUntil: " + id);
                changed++;
            }
            long distance = number(row, "distance_km");
            if (distance <= 0) throw new IllegalArgumentException("trip distance must be positive: " + id);
            trips.add(new AssignedTrip(id, planned, effective, text(row, "label"),
                    text(row, "origin"), text(row, "destination"), departure, arrival, distance));
        }
        if (!sourceTripIds.containsAll(requested.keySet()))
            throw new IllegalArgumentException("assignment contains unknown trip ID");
        trips.sort(Comparator.comparing(AssignedTrip::departureAt).thenComparing(AssignedTrip::id));

        Map<UUID, List<AssignedTrip>> byTrain = new HashMap<>();
        for (AssignedTrip trip : trips) byTrain.computeIfAbsent(trip.effectiveTrainId(), ignored -> new ArrayList<>()).add(trip);
        Map<UUID, TrainTrace> traces = new HashMap<>();
        for (TrainSource train : trains.values()) {
            String location = train.location();
            long km = train.initialKm();
            OffsetDateTime readyAfter = start;
            List<MileageAtTrip> mileage = new ArrayList<>();
            for (AssignedTrip trip : byTrain.getOrDefault(train.id(), List.of())) {
                if (!location.equals(trip.origin()))
                    throw new IllegalArgumentException("train " + train.name() + " is in " + location
                            + " before trip " + trip.id() + ", expected " + trip.origin());
                if (trip.departureAt().isBefore(readyAfter.plusMinutes(preparationMinutes)))
                    throw new IllegalArgumentException("insufficient train preparation before trip " + trip.id());
                for (Busy commitment : busy) {
                    if (train.id().equals(commitment.trainId())
                            && overlaps(trip.departureAt().minusMinutes(preparationMinutes), trip.arrivalAt(),
                            commitment.from(), commitment.to()))
                        throw new IllegalArgumentException("trip " + trip.id() + " overlaps " + commitment.kind()
                                + " of train " + train.name());
                }
                long before = km;
                km = Math.addExact(km, trip.distanceKm());
                mileage.add(new MileageAtTrip(trip.id(), before, km));
                location = trip.destination();
                readyAfter = trip.arrivalAt();
            }
            traces.put(train.id(), new TrainTrace(train.id(), train.name(), train.location(),
                    location, train.initialKm(), km, mileage));
        }
        return new Assignment(saved.scenarioId(), saved.id(), saved.snapshotHash(), frozenUntil,
                preparationMinutes, trips, traces, changed);
    }

    private static boolean overlaps(OffsetDateTime a, OffsetDateTime b, OffsetDateTime c, OffsetDateTime d) {
        return a.isBefore(d) && c.isBefore(b);
    }
    private static JsonNode rows(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isArray()) throw new IllegalArgumentException("missing source collection " + field);
        return value;
    }
    private static String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (!value.isTextual() || value.asText().isBlank())
            throw new IllegalArgumentException("missing source field " + field);
        return value.asText();
    }
    private static long number(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong())
            throw new IllegalArgumentException("invalid source number " + field);
        return value.asLong();
    }
    private static UUID uuid(JsonNode row, String field) { return UUID.fromString(text(row, field)); }
    private static OffsetDateTime time(JsonNode row, String field) { return OffsetDateTime.parse(text(row, field)); }
}
