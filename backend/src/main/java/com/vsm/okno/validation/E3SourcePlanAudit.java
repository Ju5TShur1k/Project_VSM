package com.vsm.okno.validation;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.dto.Dto;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.ScenarioSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Independent E3 source-fidelity audit. It reads the saved D1 payload, never
 * F2's adapter or obligation generator. The remaining E3 rules must be checked
 * before this scope can issue PASS.
 */
public final class E3SourcePlanAudit {
    private final ObjectMapper json = new ObjectMapper();
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "canonicalization", "scenarioId",
            "scenario", "ruleSets", "cycleRules", "trains", "odometerReadings", "resources",
            "resourceAvailability", "cycleResources", "cycleBaselines", "fixedTrips", "serviceEvents",
            "serviceCredits", "trainPresence", "trainOccupancy", "cleaningCounters", "frozenWork",
            "resourceOutages");

    private record Window(UUID train, String resource, int from, int to) {}

    public ValidationReport report(SourceSnapshot saved, ScenarioSnapshot prepared, PlannerResult result) {
        List<Dto.Validation> findings = new ArrayList<>(IndependentIntervalAudit.check(prepared, result));
        String version = null, confirmation = null;
        try {
            require(saved != null, "D2_SOURCE_MISSING", "Не найден исходный snapshot");
            require(saved.scenarioId().equals(prepared.scenarioId())
                            && saved.snapshotHash().equals(prepared.snapshotHash()),
                    "D2_SOURCE_MISMATCH", "Проекция относится к другому snapshot");
            require("1.4".equals(prepared.schemaVersion()), "D2_E3_SCHEMA", "Ожидается E3 snapshot версии 1.4");
            require("d1-source-1.0".equals(saved.schemaVersion())
                            && "pg-jsonb-text-v1".equals(saved.canonicalization()),
                    "D2_SOURCE_VERSION", "Версия исходного snapshot не поддерживается");
            require(PlanFingerprint.sha256(saved.canonicalPayload()).equals(saved.snapshotHash()),
                    "D2_SOURCE_HASH", "Hash исходного payload не совпадает");
            JsonNode root = json.readTree(saved.canonicalPayload());
            require(root.isObject(), "D2_SOURCE_INVALID", "Исходный payload не объект");
            for (var property : root.properties()) require(ROOT_FIELDS.contains(property.getKey()),
                    "D2_UNSUPPORTED_SOURCE_FIELD", "Неподдерживаемое поле " + property.getKey());
            require(saved.scenarioId().equals(uuid(root, "scenarioId"))
                            && saved.schemaVersion().equals(text(root, "schemaVersion"))
                            && saved.canonicalization().equals(text(root, "canonicalization")),
                    "D2_SOURCE_MISMATCH", "Метаданные payload не совпадают");
            for (String field : ROOT_FIELDS) {
                if (Set.of("schemaVersion", "canonicalization", "scenarioId", "scenario",
                        "resourceOutages").contains(field)) continue;
                for (JsonNode row : rows(root, field)) {
                    require(row.isObject(), "D2_SOURCE_INVALID", "Строка " + field + " не объект");
                    if (row.has("scenario_id")) require(saved.scenarioId().equals(uuid(row, "scenario_id")),
                            "D2_FOREIGN_SOURCE_ROW", "Чужой сценарий в " + field);
                    text(row, "source");
                }
            }
            JsonNode scenario = root.path("scenario");
            require(saved.scenarioId().equals(uuid(scenario, "id")), "D2_SOURCE_MISMATCH", "ID сценария не совпадает");
            OffsetDateTime start = time(scenario, "horizon_start"), end = time(scenario, "horizon_end");
            require(start.isEqual(prepared.horizonStart()) && end.isEqual(prepared.horizonEnd()),
                    "D2_HORIZON_CHANGED", "Горизонт проекции отличается от источника");
            UUID activeRuleSet = uuid(scenario, "rule_set_id");
            JsonNode ruleSet = index(root, "ruleSets", "id").get(activeRuleSet);
            require(ruleSet != null, "D2_RULES_MISSING", "Нет активной версии правил");
            version = text(ruleSet, "version");
            confirmation = text(ruleSet, "confirmation_status");
            require(Set.of("SYNTHETIC", "CONFIRMED").contains(confirmation),
                    "D2_UNCONFIRMED_RULES", "Правила не подтверждены");
            require("ABSOLUTE_GRID".equals(text(ruleSet, "mileage_policy"))
                            && "NOMINAL_MILESTONE".equals(text(ruleSet, "tolerance_basis")),
                    "D2_POLICY_UNSUPPORTED", "Политика пробега не поддерживается");

            Map<UUID, JsonNode> trains = index(root, "trains", "id");
            Set<UUID> projectedTrains = new HashSet<>();
            for (var train : prepared.trains()) {
                projectedTrains.add(train.id());
                JsonNode raw = trains.get(train.id());
                if (raw == null || !train.externalId().equals(text(raw, "external_id")))
                    issue(findings, "D2_E3_TRAIN_CHANGED", "Состав изменён в проекции", train.id());
            }
            if (!projectedTrains.equals(trains.keySet()))
                issue(findings, "D2_E3_TRAIN_SET", "Набор составов отличается от источника", null);

            Map<String, JsonNode> resources = new HashMap<>();
            for (JsonNode raw : rows(root, "resources")) {
                String id = text(raw, "id");
                require(resources.putIfAbsent(id, raw) == null, "D2_SOURCE_DUPLICATE", "Повтор ресурса " + id);
            }
            Set<String> projectedResources = new HashSet<>();
            prepared.resources().forEach(resource -> projectedResources.add(resource.id()));
            if (!projectedResources.equals(resources.keySet()))
                issue(findings, "D2_E3_RESOURCE_SET", "Набор ресурсов отличается от источника", null);
            JsonNode outages = root.path("resourceOutages");
            require(outages.isMissingNode() || outages.isArray(), "D2_E3_OUTAGE_SOURCE",
                    "Отключения ресурсов должны быть массивом");
            if (outages.isArray()) for (JsonNode outage : outages) {
                require(saved.scenarioId().equals(uuid(outage, "scenario_id"))
                                && resources.containsKey(text(outage, "resource_id")),
                        "D2_E3_OUTAGE_SOURCE", "Отключение относится к чужому ресурсу");
                text(outage, "source");
                OffsetDateTime from = time(outage, "starts_at"), to = time(outage, "ends_at");
                require(!from.isBefore(start) && !to.isAfter(end) && from.isBefore(to),
                        "D2_E3_OUTAGE_SOURCE", "Неверный интервал отключения");
                for (JsonNode available : rows(root, "resourceAvailability")) {
                    if (text(outage, "resource_id").equals(text(available, "resource_id"))
                            && from.isBefore(time(available, "ends_at"))
                            && time(available, "starts_at").isBefore(to))
                        issue(findings, "D2_E3_OUTAGE_AVAILABILITY",
                                "Отключённый ресурс остался доступен", uuid(outage, "id"));
                }
            }

            Map<String, Set<String>> resourcesByCycle = new HashMap<>();
            for (JsonNode mapping : rows(root, "cycleResources")) {
                if (activeRuleSet.equals(uuid(mapping, "rule_set_id")))
                    resourcesByCycle.computeIfAbsent(text(mapping, "cycle_code"), ignored -> new HashSet<>())
                            .add(text(mapping, "resource_id"));
            }
            Set<Set<String>> validResourceOptions = new HashSet<>(resourcesByCycle.values());
            for (var block : prepared.blocks()) {
                if (!validResourceOptions.contains(new HashSet<>(block.allowedResourceIds())))
                    issue(findings, "D2_E3_WORK_RESOURCE_OPTIONS", "Работа использует не исходный набор путей", block.id());
            }

            Map<UUID, JsonNode> trips = index(root, "fixedTrips", "id");
            Set<UUID> projectedTrips = new HashSet<>();
            for (var trip : prepared.fixedTrips()) {
                projectedTrips.add(trip.id());
                JsonNode raw = trips.get(trip.id());
                if (raw == null || !trip.trainId().equals(uuid(raw, "train_id"))
                        || !trip.label().equals(text(raw, "label"))
                        || trip.distanceKm() != number(raw, "distance_km")
                        || trip.startMinute() != minute(start, time(raw, "departure_at"))
                        || trip.endMinute() != minute(start, time(raw, "arrival_at")))
                    issue(findings, "D2_E3_TRIP_CHANGED", "Рейс изменён в проекции", trip.id());
            }
            if (!projectedTrips.equals(trips.keySet()))
                issue(findings, "D2_E3_TRIP_SET", "Набор рейсов отличается от источника", null);

            Map<UUID, JsonNode> occupancies = index(root, "trainOccupancy", "id");
            Map<UUID, JsonNode> frozen = index(root, "frozenWork", "id");
            Set<UUID> expectedOccupancy = new HashSet<>(frozen.keySet());
            Set<UUID> reserveTrains = new HashSet<>();
            trains.forEach((id, raw) -> { if ("RESERVE".equals(text(raw, "status"))) reserveTrains.add(id); });
            for (JsonNode raw : occupancies.values()) {
                String kind = text(raw, "kind");
                if (!"RESERVE".equals(kind)) expectedOccupancy.add(uuid(raw, "id"));
                else require(reserveTrains.contains(uuid(raw, "train_id")),
                        "D2_E3_RESERVE_SOURCE", "Резервная занятость у нерезервного состава");
            }
            if (!reserveTrains.equals(prepared.operations().protectedReserveTrainIds()))
                issue(findings, "D2_E3_RESERVE_CHANGED", "Список защищённого резерва отличается", null);
            for (UUID train : reserveTrains) {
                List<JsonNode> reserveRows = occupancies.values().stream()
                        .filter(row -> "RESERVE".equals(text(row, "kind"))
                                && train.equals(uuid(row, "train_id")))
                        .sorted((a, b) -> time(a, "starts_at").compareTo(time(b, "starts_at"))).toList();
                OffsetDateTime until = start;
                for (JsonNode row : reserveRows) {
                    if (time(row, "starts_at").isAfter(until)) break;
                    if (time(row, "ends_at").isAfter(until)) until = time(row, "ends_at");
                }
                if (until.isBefore(end)) issue(findings, "D2_E3_RESERVE_EVIDENCE",
                        "Резервная занятость не покрывает горизонт", train);
                String city = text(trains.get(train), "location");
                boolean presence = false;
                for (JsonNode row : rows(root, "trainPresence")) {
                    if (train.equals(uuid(row, "train_id")) && city.equals(text(row, "location"))
                            && !time(row, "starts_at").isAfter(start)
                            && !time(row, "ends_at").isBefore(end)) presence = true;
                }
                if (!presence) issue(findings, "D2_E3_RESERVE_PRESENCE",
                        "Нет полного окна присутствия резервного состава в исходном городе", train);
            }
            Map<UUID, JsonNode> counters = index(root, "cleaningCounters", "train_id");
            if (!counters.keySet().equals(trains.keySet()))
                issue(findings, "D2_E3_CLEANING_COUNTER_SET", "Нет счётчика уборки для каждого состава", null);
            for (JsonNode counter : counters.values()) {
                if (number(counter, "completed_trips_since_cleaning") < 0
                        || !Set.of("SYNTHETIC", "CONFIRMED").contains(text(counter, "confirmation_status")))
                    issue(findings, "D2_E3_CLEANING_COUNTER_INVALID", "Некорректный исходный счётчик уборки",
                            uuid(counter, "train_id"));
            }
            Set<UUID> actualOccupancy = new HashSet<>();
            for (var occupancy : prepared.operations().fixedOccupancies()) {
                actualOccupancy.add(occupancy.id());
                JsonNode raw = occupancies.get(occupancy.id());
                if (raw != null && !"RESERVE".equals(text(raw, "kind"))) {
                    if (!uuid(raw, "train_id").equals(occupancy.trainId()) || occupancy.resourceId() != null
                            || minute(start, time(raw, "starts_at")) != occupancy.startMinute()
                            || minute(start, time(raw, "ends_at")) != occupancy.endMinute())
                        issue(findings, "D2_E3_OCCUPANCY_CHANGED", "Занятость состава изменена", occupancy.id());
                } else if ((raw = frozen.get(occupancy.id())) != null) {
                    UUID train = uuid(raw, "train_id");
                    int from = minute(start, time(raw, "starts_at")), to = minute(start, time(raw, "ends_at"));
                    boolean covered = occupancies.values().stream().anyMatch(other ->
                            "UNAVAILABLE".equals(text(other, "kind")) && train.equals(uuid(other, "train_id"))
                                    && minute(start, time(other, "starts_at")) <= from
                                    && to <= minute(start, time(other, "ends_at")));
                    if ((!covered && !train.equals(occupancy.trainId()))
                            || (covered && occupancy.trainId() != null)
                            || !text(raw, "resource_id").equals(occupancy.resourceId())
                            || from != occupancy.startMinute() || to != occupancy.endMinute())
                        issue(findings, "D2_E3_FROZEN_CHANGED", "Закреплённая работа изменена", occupancy.id());
                } else issue(findings, "D2_E3_OCCUPANCY_EXTRA", "Лишняя занятость в проекции", occupancy.id());
            }
            if (!expectedOccupancy.equals(actualOccupancy))
                issue(findings, "D2_E3_OCCUPANCY_SET", "Набор занятостей/закреплённых работ отличается", null);

            Map<Window, Integer> expectedWindows = new HashMap<>();
            for (JsonNode presence : rows(root, "trainPresence")) {
                for (JsonNode availability : rows(root, "resourceAvailability")) {
                    String resource = text(availability, "resource_id");
                    JsonNode resourceRow = resources.get(resource);
                    require(resourceRow != null, "D2_E3_RESOURCE_UNKNOWN", "Неизвестный ресурс " + resource);
                    if (!text(resourceRow, "location").equals(text(presence, "location"))) continue;
                    OffsetDateTime from = max(start, time(presence, "starts_at"), time(availability, "starts_at"));
                    OffsetDateTime to = min(end, time(presence, "ends_at"), time(availability, "ends_at"));
                    if (from.isBefore(to)) expectedWindows.merge(new Window(uuid(presence, "train_id"), resource,
                            minute(start, from), minute(start, to)), 1, Integer::sum);
                }
            }
            Map<Window, Integer> actualWindows = new HashMap<>();
            prepared.operations().serviceWindows().forEach(window -> actualWindows.merge(
                    new Window(window.trainId(), window.resourceId(), window.startMinute(), window.endMinute()),
                    1, Integer::sum));
            if (!expectedWindows.equals(actualWindows))
                issue(findings, "D2_E3_WINDOW_SET", "Окна присутствия/ресурса отличаются от источника", null);
        } catch (SourceProblem error) {
            issue(findings, error.code, error.getMessage(), null);
        } catch (RuntimeException error) {
            issue(findings, "D2_E3_SOURCE_INVALID", "Нельзя полностью прочитать источник: " + error.getMessage(), null);
        }
        if (findings.stream().noneMatch(f -> "CRITICAL".equals(f.severity())))
            findings.add(new Dto.Validation("VALIDATION_NOT_PERFORMED", "INFO",
                    "E3: независимая проверка пробегов, уборки, выпуска, резерва по городам и переназначения рейсов ещё не завершена"));
        return ValidationReport.of(prepared, result, saved == null ? null : saved.id(),
                "E3_SOURCE_FIDELITY", version, confirmation, null, List.of(), findings);
    }

    private static JsonNode rows(JsonNode root, String field) {
        JsonNode value = root.path(field);
        require(value.isArray(), "D2_SOURCE_INCOMPLETE", "Отсутствует массив " + field);
        return value;
    }
    private static Map<UUID, JsonNode> index(JsonNode root, String field, String key) {
        Map<UUID, JsonNode> result = new HashMap<>();
        for (JsonNode row : rows(root, field)) {
            UUID id = uuid(row, key);
            require(result.putIfAbsent(id, row) == null, "D2_SOURCE_DUPLICATE", "Повтор ID в " + field);
        }
        return result;
    }
    private static String text(JsonNode row, String key) {
        JsonNode value = row.path(key);
        require(value.isTextual() && !value.asText().isBlank(), "D2_SOURCE_INCOMPLETE", "Отсутствует " + key);
        return value.asText();
    }
    private static long number(JsonNode row, String key) {
        JsonNode value = row.path(key);
        require(value.isIntegralNumber() && value.canConvertToLong(), "D2_SOURCE_INVALID", "Ожидается целое " + key);
        return value.asLong();
    }
    private static UUID uuid(JsonNode row, String key) { return UUID.fromString(text(row, key)); }
    private static OffsetDateTime time(JsonNode row, String key) { return OffsetDateTime.parse(text(row, key)); }
    private static int minute(OffsetDateTime start, OffsetDateTime at) {
        Duration elapsed = Duration.between(start, at);
        require(elapsed.getNano() == 0 && elapsed.getSeconds() % 60 == 0,
                "D2_TIME_PRECISION", "Время должно быть целой минутой");
        return Math.toIntExact(elapsed.toMinutes());
    }
    private static OffsetDateTime max(OffsetDateTime a, OffsetDateTime b, OffsetDateTime c) {
        OffsetDateTime ab = a.isAfter(b) ? a : b;
        return ab.isAfter(c) ? ab : c;
    }
    private static OffsetDateTime min(OffsetDateTime a, OffsetDateTime b, OffsetDateTime c) {
        OffsetDateTime ab = a.isBefore(b) ? a : b;
        return ab.isBefore(c) ? ab : c;
    }
    private static void issue(List<Dto.Validation> findings, String code, String message, Object object) {
        findings.add(new Dto.Validation(code, "CRITICAL", message, object == null ? null : object.toString(), null, null));
    }
    private static void require(boolean condition, String code, String message) {
        if (!condition) throw new SourceProblem(code, message);
    }
    private static final class SourceProblem extends RuntimeException {
        private final String code;
        private SourceProblem(String code, String message) { super(message); this.code = code; }
    }
}
