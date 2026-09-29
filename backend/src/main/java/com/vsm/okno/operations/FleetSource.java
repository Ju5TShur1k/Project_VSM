package com.vsm.okno.operations;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.validation.PlanFingerprint;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Reads immutable source facts; does not use F2's projection or move source trips. */
public record FleetSource(UUID scenarioId, UUID snapshotId, String hash, OffsetDateTime start, OffsetDateTime end,
                          List<Train> trains, List<Trip> trips, List<Occupation> occupations,
                          List<Presence> presences, List<Rule> rules) {
    public record Train(UUID id, String name, String status, String location, long initialKm,
                        Integer cleaningCounter, String cleaningEvidence, Map<String, Long> credits) {}
    public record Trip(UUID id, UUID trainId, String label, String origin, String destination,
                       OffsetDateTime departure, OffsetDateTime arrival, long distance) {}
    public record Occupation(UUID trainId, String kind, OffsetDateTime start, OffsetDateTime end) {}
    public record Presence(UUID trainId, String location, OffsetDateTime start, OffsetDateTime end, String confirmation) {}
    public record Rule(String code, long interval, int tolerance) {}

    public static FleetSource read(SourceSnapshot saved) {
        if (!PlanFingerprint.sha256(saved.canonicalPayload()).equals(saved.snapshotHash()))
            throw new IllegalArgumentException("Повреждён hash исходного snapshot");
        JsonNode root = new ObjectMapper().readTree(saved.canonicalPayload());
        if (!saved.schemaVersion().equals("d1-source-1.0") || !saved.canonicalization().equals("pg-jsonb-text-v1")
                || !saved.scenarioId().toString().equals(root.path("scenarioId").asText())
                || !root.path("scenario").path("provenance").asText().startsWith("MODELLED FULL43"))
            throw new IllegalArgumentException("Оперативный пример поддерживает только явно модельный FULL43, не эксплуатационные данные");
        OffsetDateTime start = time(root.path("scenario"), "horizon_start");
        OffsetDateTime end = start.plusDays(1);
        if (end.isAfter(time(root.path("scenario"), "horizon_end"))) throw new IllegalArgumentException("Нужен полный день источника");
        UUID ruleSet = uuid(root.path("scenario"), "rule_set_id");
        JsonNode ruleMetadata=null;
        for (var row:root.path("ruleSets")) if (ruleSet.equals(uuid(row,"id"))) ruleMetadata=row;
        if (ruleMetadata==null) throw new IllegalArgumentException("Отсутствует активная версия правил");
        if (!ruleMetadata.path("mileage_policy").asText().equals("ABSOLUTE_GRID")
                || !ruleMetadata.path("tolerance_basis").asText().equals("NOMINAL_MILESTONE")
                || !List.of("SYNTHETIC","CONFIRMED").contains(ruleMetadata.path("confirmation_status").asText()))
            throw new IllegalArgumentException("Не поддерживается или не подтверждена политика пробеговых норм");
        Map<UUID, Long> odometers = new HashMap<>();
        Map<UUID, OffsetDateTime> observed = new HashMap<>();
        for (var row : root.path("odometerReadings")) {
            UUID id = uuid(row, "train_id"); var at = time(row, "observed_at");
            if (!at.isAfter(start) && (!observed.containsKey(id) || at.isAfter(observed.get(id)))) {
                observed.put(id, at); odometers.put(id, row.path("odometer_km").asLong());
            }
        }
        Map<UUID, JsonNode> counters = new HashMap<>();
        for (var row : root.path("cleaningCounters")) {
            UUID id = uuid(row, "train_id");
            if (!time(row, "observed_at").isAfter(start) && (!counters.containsKey(id)
                    || time(row, "observed_at").isAfter(time(counters.get(id), "observed_at")))) counters.put(id, row);
        }
        Map<UUID, Map<String, Long>> credits = new HashMap<>();
        for (var row : root.path("cycleBaselines")) if (ruleSet.equals(uuid(row, "rule_set_id"))
                && !time(row, "recorded_at").isAfter(start))
            credits.computeIfAbsent(uuid(row, "train_id"), k -> new HashMap<>())
                    .merge(row.path("cycle_code").asText(), row.path("credited_nominal_km").asLong(), Math::max);
        Map<UUID, JsonNode> history = new HashMap<>();
        for (var row : root.path("serviceEvents")) history.put(uuid(row, "id"), row);
        for (var row : root.path("serviceCredits")) {
            var event = history.get(uuid(row, "service_event_id"));
            if (event != null && ruleSet.equals(uuid(row, "rule_set_id")) && !time(event, "accepted_at").isAfter(start))
                credits.computeIfAbsent(uuid(event, "train_id"), k -> new HashMap<>())
                        .merge(row.path("covered_cycle_code").asText(), row.path("credited_nominal_km").asLong(), Math::max);
        }
        List<Train> trains = new ArrayList<>();
        for (var row : root.path("trains")) {
            UUID id = uuid(row, "id"); var counter = counters.get(id);
            if (!odometers.containsKey(id)) throw new IllegalArgumentException("Нет пробега состава " + id);
            trains.add(new Train(id, row.path("external_id").asText(), row.path("status").asText(), row.path("location").asText(),
                    odometers.get(id), counter == null ? null : counter.path("completed_trips_since_cleaning").asInt(),
                    counter == null ? "UNKNOWN" : counter.path("confirmation_status").asText(), Map.copyOf(credits.getOrDefault(id, Map.of()))));
        }
        trains.sort(Comparator.comparing(Train::name));
        List<Trip> trips = new ArrayList<>();
        for (var row : root.path("fixedTrips")) {
            var departure = time(row, "departure_at"); var arrival = time(row, "arrival_at");
            if (!departure.isBefore(start) && !arrival.isAfter(end))
                trips.add(new Trip(uuid(row,"id"), uuid(row,"train_id"), row.path("label").asText(), row.path("origin").asText(),
                        row.path("destination").asText(), departure, arrival, row.path("distance_km").asLong()));
        }
        trips.sort(Comparator.comparing(Trip::departure).thenComparing(t -> t.id().toString()));
        List<Occupation> occupations = new ArrayList<>();
        for (var row : root.path("trainOccupancy")) occupations.add(new Occupation(uuid(row,"train_id"),row.path("kind").asText(),time(row,"starts_at"),time(row,"ends_at")));
        for (var row : root.path("frozenWork")) occupations.add(new Occupation(uuid(row,"train_id"),"FROZEN",time(row,"starts_at"),time(row,"ends_at")));
        List<Presence> presences = new ArrayList<>();
        for (var row : root.path("trainPresence")) presences.add(new Presence(uuid(row,"train_id"),row.path("location").asText(),time(row,"starts_at"),time(row,"ends_at"),row.path("confirmation_status").asText()));
        List<Rule> rules = new ArrayList<>();
        for (var row : root.path("cycleRules")) if (ruleSet.equals(uuid(row,"rule_set_id")))
            rules.add(new Rule(row.path("code").asText(),row.path("interval_km").asLong(),row.path("tolerance_basis_points").asInt()));
        return new FleetSource(saved.scenarioId(),saved.id(),saved.snapshotHash(),start,end,List.copyOf(trains),List.copyOf(trips),List.copyOf(occupations),List.copyOf(presences),List.copyOf(rules));
    }
    public Train train(UUID id) { return trains.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Неизвестный состав")); }
    public Trip trip(UUID id) { return trips.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow(() -> new IllegalArgumentException("Рейс отсутствует в оперативном дне")); }
    private static UUID uuid(JsonNode row,String field) { return UUID.fromString(row.path(field).asText()); }
    private static OffsetDateTime time(JsonNode row,String field) { return OffsetDateTime.parse(row.path(field).asText()); }
}
