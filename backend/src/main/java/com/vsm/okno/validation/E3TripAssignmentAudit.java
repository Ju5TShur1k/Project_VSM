package com.vsm.okno.validation;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.dto.Dto;
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

/** Independent D2 slice for proposed train assignments and their mileage. No F2 ledger calls. */
public final class E3TripAssignmentAudit {
    private record Train(String name, String city, String status, long initialKm) {}
    private record Trip(UUID id, UUID plannedTrain, String label, String origin, String destination,
                        OffsetDateTime departure, OffsetDateTime arrival, long distance) {}
    private record Busy(UUID train, OffsetDateTime start, OffsetDateTime end, String kind) {}

    private final ObjectMapper json = new ObjectMapper();

    public List<Dto.Validation> check(SourceSnapshot saved, E3TripAssignmentLedger.Assignment proposed,
                                      OffsetDateTime expectedFrozenUntil, int expectedPreparationMinutes) {
        List<Dto.Validation> issues = new ArrayList<>();
        try {
            require(saved != null && proposed != null, "D2_E3_ASSIGNMENT_MISSING", "Нет источника или назначений");
            require(saved.scenarioId().equals(proposed.scenarioId())
                            && saved.id().equals(proposed.sourceSnapshotId())
                            && saved.snapshotHash().equals(proposed.snapshotHash()),
                    "D2_E3_ASSIGNMENT_SOURCE", "Назначения относятся к другому snapshot");
            require(saved.snapshotHash().equals(PlanFingerprint.sha256(saved.canonicalPayload()))
                            && "d1-source-1.0".equals(saved.schemaVersion())
                            && "pg-jsonb-text-v1".equals(saved.canonicalization()),
                    "D2_SOURCE_HASH", "Исходный snapshot повреждён или не поддерживается");
            require(expectedFrozenUntil != null && expectedFrozenUntil.isEqual(proposed.frozenUntil())
                            && expectedPreparationMinutes >= 0
                            && expectedPreparationMinutes == proposed.preparationMinutes(),
                    "D2_E3_ASSIGNMENT_POLICY", "Заморозка или подготовка не совпадают с проверяемой политикой");
            JsonNode root = json.readTree(saved.canonicalPayload());
            require(saved.scenarioId().equals(uuid(root, "scenarioId"))
                            && saved.schemaVersion().equals(text(root, "schemaVersion"))
                            && saved.canonicalization().equals(text(root, "canonicalization")),
                    "D2_SOURCE_MISMATCH", "Метаданные payload не совпадают");
            OffsetDateTime horizonStart = time(root.path("scenario"), "horizon_start");
            OffsetDateTime horizonEnd = time(root.path("scenario"), "horizon_end");
            require(saved.scenarioId().equals(uuid(root.path("scenario"), "id")),
                    "D2_SOURCE_MISMATCH", "Строка scenario не совпадает");
            require(!expectedFrozenUntil.isBefore(horizonStart) && !expectedFrozenUntil.isAfter(horizonEnd),
                    "D2_E3_ASSIGNMENT_POLICY", "Заморозка вне горизонта");

            Map<UUID, Long> odometers = new HashMap<>();
            Map<UUID, OffsetDateTime> latest = new HashMap<>();
            for (JsonNode row : rows(root, "odometerReadings")) {
                UUID train = uuid(row, "train_id");
                OffsetDateTime at = time(row, "observed_at");
                require(!at.isAfter(horizonStart), "D2_IN_HORIZON_ODOMETER_UNSUPPORTED",
                        "Замер внутри горизонта требует новой проверки");
                if (!latest.containsKey(train) || at.isAfter(latest.get(train))) {
                    latest.put(train, at); odometers.put(train, number(row, "odometer_km"));
                }
            }
            Map<UUID, Train> trains = new HashMap<>();
            for (JsonNode row : rows(root, "trains")) {
                UUID id = uuid(row, "id");
                Long km = odometers.get(id);
                require(km != null && km >= 0 && trains.putIfAbsent(id,
                        new Train(text(row, "external_id"), text(row, "location"),
                                text(row, "status"), km)) == null,
                        "D2_E3_TRAIN_SOURCE", "Состав или начальный пробег некорректен");
            }
            Map<UUID, Trip> expectedTrips = new HashMap<>();
            for (JsonNode row : rows(root, "fixedTrips")) {
                UUID id = uuid(row, "id");
                Trip trip = new Trip(id, uuid(row, "train_id"), text(row, "label"),
                        text(row, "origin"), text(row, "destination"),
                        time(row, "departure_at"), time(row, "arrival_at"), number(row, "distance_km"));
                require(expectedTrips.putIfAbsent(id, trip) == null, "D2_E3_TRIP_SOURCE", "Повтор ID рейса в источнике");
            }
            List<Busy> commitments = new ArrayList<>();
            for (JsonNode row : rows(root, "trainOccupancy")) {
                if ("RESERVE".equals(text(row, "kind"))) continue;
                commitments.add(new Busy(uuid(row, "train_id"), time(row, "starts_at"),
                        time(row, "ends_at"), text(row, "kind")));
            }
            for (JsonNode row : rows(root, "frozenWork")) commitments.add(new Busy(
                    uuid(row, "train_id"), time(row, "starts_at"), time(row, "ends_at"), "FROZEN"));

            Map<UUID, E3TripAssignmentLedger.AssignedTrip> actualTrips = new HashMap<>();
            Map<UUID, List<E3TripAssignmentLedger.AssignedTrip>> byTrain = new HashMap<>();
            int changes = 0;
            for (var actual : proposed.trips()) {
                Trip raw = expectedTrips.get(actual.id());
                if (raw == null || actualTrips.putIfAbsent(actual.id(), actual) != null) {
                    add(issues, "D2_E3_TRIP_COVERAGE", "Лишний или повторный рейс", actual.id());
                    continue;
                }
                if (!raw.plannedTrain().equals(actual.plannedTrainId())
                        || !raw.label().equals(actual.label())
                        || !raw.origin().equals(actual.origin())
                        || !raw.destination().equals(actual.destination())
                        || !raw.departure().isEqual(actual.departureAt())
                        || !raw.arrival().isEqual(actual.arrivalAt())
                        || raw.distance() != actual.distanceKm())
                    add(issues, "D2_E3_TRIP_CHANGED", "Исходный рейс изменён в назначениях", actual.id());
                Train train = trains.get(actual.effectiveTrainId());
                if (train == null || !Set.of("AVAILABLE", "IN_SERVICE").contains(train.status()))
                    add(issues, "D2_E3_TRAIN_NOT_RELEASED", "Назначен недопущенный состав", actual.id());
                if (!actual.effectiveTrainId().equals(raw.plannedTrain())) {
                    changes++;
                    if (actual.departureAt().isBefore(expectedFrozenUntil))
                        add(issues, "D2_E3_FROZEN_TRIP_MOVED", "Рейс до границы заморозки переназначен", actual.id());
                }
                byTrain.computeIfAbsent(actual.effectiveTrainId(), ignored -> new ArrayList<>()).add(actual);
            }
            if (!actualTrips.keySet().equals(expectedTrips.keySet()))
                add(issues, "D2_E3_TRIP_COVERAGE", "Назначения не покрывают все исходные рейсы", null);
            if (changes != proposed.changedTripCount())
                add(issues, "D2_E3_CHANGE_COUNT", "Число переназначений неверно", null);
            if (!proposed.trains().keySet().equals(trains.keySet()))
                add(issues, "D2_E3_TRACE_SET", "Не все составы имеют трассу пробега", null);

            for (var entry : trains.entrySet()) {
                UUID id = entry.getKey(); Train raw = entry.getValue();
                var trace = proposed.trains().get(id);
                if (trace == null) continue;
                String city = raw.city(); long km = raw.initialKm();
                OffsetDateTime readyAfter = horizonStart;
                List<E3TripAssignmentLedger.MileageAtTrip> mileage = new ArrayList<>();
                var assigned = new ArrayList<>(byTrain.getOrDefault(id, List.of()));
                assigned.sort(Comparator.comparing(E3TripAssignmentLedger.AssignedTrip::departureAt)
                        .thenComparing(E3TripAssignmentLedger.AssignedTrip::id));
                for (var trip : assigned) {
                    if (!city.equals(trip.origin()))
                        add(issues, "D2_E3_ROUTE", "Состав находится в другом городе", trip.id());
                    if (trip.departureAt().isBefore(readyAfter.plusMinutes(expectedPreparationMinutes)))
                        add(issues, "D2_E3_PREPARATION", "Недостаточно времени между рейсами", trip.id());
                    for (Busy occupied : commitments) {
                        if (id.equals(occupied.train())
                                && overlaps(trip.departureAt().minusMinutes(expectedPreparationMinutes),
                                trip.arrivalAt(), occupied.start(), occupied.end())) {
                            add(issues, "D2_E3_OCCUPANCY_CONFLICT", "Рейс пересекается с " + occupied.kind(), trip.id());
                            break;
                        }
                    }
                    long before = km;
                    km = Math.addExact(km, trip.distanceKm());
                    mileage.add(new E3TripAssignmentLedger.MileageAtTrip(trip.id(), before, km));
                    city = trip.destination(); readyAfter = trip.arrivalAt();
                }
                if (!id.equals(trace.trainId()) || !raw.name().equals(trace.externalId())
                        || !raw.city().equals(trace.initialLocation())
                        || !city.equals(trace.finalLocation())
                        || raw.initialKm() != trace.initialKm() || km != trace.finalKm()
                        || !mileage.equals(trace.trips()))
                    add(issues, "D2_E3_MILEAGE_TRACE", "Пробег или маршрут состава не совпадает с рейсами", id);
            }
        } catch (SourceProblem error) {
            add(issues, error.code, error.getMessage(), null);
        } catch (RuntimeException error) {
            add(issues, "D2_E3_ASSIGNMENT_INVALID", "Нельзя полностью проверить назначения: " + error.getMessage(), null);
        }
        return List.copyOf(issues);
    }

    private static boolean overlaps(OffsetDateTime a, OffsetDateTime b, OffsetDateTime c, OffsetDateTime d) {
        return a.isBefore(d) && c.isBefore(b);
    }
    private static JsonNode rows(JsonNode root, String field) {
        JsonNode value = root.path(field);
        require(value.isArray(), "D2_SOURCE_INCOMPLETE", "Нет массива " + field);
        return value;
    }
    private static String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        require(value.isTextual() && !value.asText().isBlank(), "D2_SOURCE_INCOMPLETE", "Нет поля " + field);
        return value.asText();
    }
    private static long number(JsonNode row, String field) {
        JsonNode value = row.path(field);
        require(value.isIntegralNumber() && value.canConvertToLong(), "D2_SOURCE_INVALID", "Некорректное число " + field);
        return value.asLong();
    }
    private static UUID uuid(JsonNode row, String field) { return UUID.fromString(text(row, field)); }
    private static OffsetDateTime time(JsonNode row, String field) { return OffsetDateTime.parse(text(row, field)); }
    private static void add(List<Dto.Validation> issues, String code, String message, UUID object) {
        issues.add(new Dto.Validation(code, "CRITICAL", message,
                object == null ? null : object.toString(), null, null));
    }
    private static void require(boolean condition, String code, String message) {
        if (!condition) throw new SourceProblem(code, message);
    }
    private static final class SourceProblem extends RuntimeException {
        final String code;
        SourceProblem(String code, String message) { super(message); this.code = code; }
    }
}
