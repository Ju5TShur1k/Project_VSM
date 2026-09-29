package com.vsm.okno.validation;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.dto.Dto;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.ScenarioSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Independent E2 source audit. Reads the saved canonical payload, not live tables,
 * F2's obligation generator or its slot/search helpers. Unsupported E3 is an error.
 */
public final class SourcePlanAudit {
    private final ObjectMapper json = new ObjectMapper();
    private static final Set<String> ROOT_FIELDS = Set.of("schemaVersion", "canonicalization", "scenarioId",
            "scenario", "ruleSets", "cycleRules", "trains", "odometerReadings", "resources",
            "resourceAvailability", "cycleResources", "cycleBaselines", "fixedTrips", "serviceEvents",
            "serviceCredits", "trainPresence", "trainOccupancy", "cleaningCounters", "frozenWork");

    private record Rule(String code, long interval, int tolerance, int duration, int rank, String resource) {}
    private record Trip(UUID id, UUID train, String label, OffsetDateTime departure, OffsetDateTime arrival,
                        long distance, String origin, String destination) {}
    private record Expected(UUID id, UUID train, long nominal, Rule senior, long lower, long upper) {}

    public ValidationReport report(SourceSnapshot saved, ScenarioSnapshot prepared, PlannerResult result) {
        List<Dto.Validation> findings = new ArrayList<>(IndependentIntervalAudit.check(prepared, result));
        String ruleVersion = null, confirmation = null;
        Integer requiredCount = null;
        List<ValidationReport.PendingMilestone> pending = new ArrayList<>();
        try {
            need(saved != null, "D2_SOURCE_MISSING", "Не найден неизменяемый исходный snapshot", null);
            need(saved.scenarioId().equals(prepared.scenarioId())
                            && saved.snapshotHash().equals(prepared.snapshotHash()), "D2_SOURCE_MISMATCH",
                    "Подготовленный вход не соответствует сохранённому исходному snapshot", saved.id());
            need("d1-source-1.0".equals(saved.schemaVersion())
                            && "pg-jsonb-text-v1".equals(saved.canonicalization()), "D2_SOURCE_VERSION",
                    "Версия источника или канонизации не поддерживается", saved.id());
            need(PlanFingerprint.sha256(saved.canonicalPayload()).equals(saved.snapshotHash()), "D2_SOURCE_HASH",
                    "SHA-256 исходного payload не совпадает с сохранённым hash", saved.id());
            JsonNode root = json.readTree(saved.canonicalPayload());
            need(root.isObject(), "D2_SOURCE_INVALID", "Исходный payload должен быть объектом", saved.id());
            for (var property : root.properties()) {
                need(ROOT_FIELDS.contains(property.getKey()), "D2_UNSUPPORTED_SOURCE_FIELD",
                        "Неподдерживаемое поле источника: " + property.getKey(), property.getKey());
            }
            need(saved.scenarioId().equals(uuid(root, "scenarioId"))
                            && saved.schemaVersion().equals(text(root, "schemaVersion"))
                            && saved.canonicalization().equals(text(root, "canonicalization")), "D2_SOURCE_MISMATCH",
                    "Метаданные payload не совпадают с записью snapshot", saved.id());
            for (String field : ROOT_FIELDS) {
                if (Set.of("schemaVersion", "canonicalization", "scenarioId", "scenario").contains(field)) continue;
                for (JsonNode row : rows(root, field)) {
                    need(row.isObject(), "D2_SOURCE_INVALID", "Строка " + field + " должна быть объектом", field);
                    if (row.has("scenario_id")) need(saved.scenarioId().equals(uuid(row, "scenario_id")),
                            "D2_FOREIGN_SOURCE_ROW", "В payload попала строка другого сценария: " + field, field);
                    text(row, "source");
                }
            }
            for (String field : List.of("trainOccupancy", "cleaningCounters", "frozenWork")) {
                need(rows(root, field).isEmpty(), "D2_E3_SOURCE_UNSUPPORTED",
                        "E2-проверка не покрывает " + field + "; нужны исходные правила E3", field);
            }
            need("1.2".equals(prepared.schemaVersion()) && prepared.operations().fixedOccupancies().isEmpty()
                            && prepared.operations().frozenPlacements().isEmpty()
                            && prepared.operations().releaseRequirements().isEmpty()
                            && prepared.operations().hotReserve() == null
                            && prepared.operations().protectedReserveTrainIds().isEmpty(), "D2_E3_SOURCE_UNSUPPORTED",
                    "Подготовленный вход содержит ограничения вне поддерживаемой области E2", saved.id());

            JsonNode scenario = root.path("scenario");
            need(saved.scenarioId().equals(uuid(scenario, "id")), "D2_SOURCE_MISMATCH", "ID scenario не совпадает", saved.id());
            OffsetDateTime start = time(scenario, "horizon_start"), end = time(scenario, "horizon_end");
            need(start.isEqual(prepared.horizonStart()) && end.isEqual(prepared.horizonEnd()), "D2_HORIZON_CHANGED",
                    "План рассчитан на другом горизонте", saved.id());
            UUID ruleId = uuid(scenario, "rule_set_id");
            Map<UUID, JsonNode> ruleSets = index(root, "ruleSets", "id");
            JsonNode ruleSet = ruleSets.get(ruleId);
            need(ruleSet != null, "D2_RULES_MISSING", "Отсутствует активная версия правил", ruleId);
            ruleVersion = text(ruleSet, "version");
            confirmation = text(ruleSet, "confirmation_status");
            need(Set.of("SYNTHETIC", "CONFIRMED").contains(confirmation), "D2_UNCONFIRMED_RULES",
                    "Неподтверждённые правила нельзя использовать для согласования", ruleId);
            need("ABSOLUTE_GRID".equals(text(ruleSet, "mileage_policy"))
                            && "NOMINAL_MILESTONE".equals(text(ruleSet, "tolerance_basis")), "D2_POLICY_UNSUPPORTED",
                    "Независимая проверка поддерживает только явную абсолютную сетку и допуск к номинальному рубежу", ruleId);

            Map<String, JsonNode> resources = new HashMap<>();
            for (JsonNode row : rows(root, "resources")) {
                String resource = text(row, "id");
                need(resources.putIfAbsent(resource, row) == null, "D2_SOURCE_DUPLICATE", "Ресурс повторяется", resource);
                text(row, "location");
            }
            Map<String, String> mapping = new HashMap<>();
            for (JsonNode row : rows(root, "cycleResources")) {
                need(ruleId.equals(uuid(row, "rule_set_id")), "D2_RULE_VERSION_CHANGED", "Связь ресурса относится к другой версии правил", ruleId);
                String cycle = text(row, "cycle_code"), resource = text(row, "resource_id");
                need(resources.containsKey(resource), "D2_UNKNOWN_RESOURCE", "Неизвестный ресурс", resource);
                need(mapping.putIfAbsent(cycle, resource) == null, "D2_ALTERNATIVE_RESOURCE_UNSUPPORTED",
                        "Для нескольких допустимых ресурсов нужен контракт E3", cycle);
            }
            Map<String, Rule> rules = new TreeMap<>();
            for (JsonNode row : rows(root, "cycleRules")) {
                if (!ruleId.equals(uuid(row, "rule_set_id"))) continue;
                String code = text(row, "code"), resource = mapping.get(code);
                need(resource != null, "D2_RULE_RESOURCE_MISSING", "Нет ресурса для цикла", code);
                Rule rule = new Rule(code, number(row, "interval_km"), integer(row, "tolerance_basis_points"),
                        integer(row, "duration_minutes"), integer(row, "rank"), resource);
                need(rule.interval() > 0 && rule.duration() > 0 && rule.tolerance() >= 0 && rule.tolerance() < 10000
                                && rule.rank() >= 0, "D2_RULE_INVALID", "Некорректные численные правила цикла", code);
                need(rules.putIfAbsent(code, rule) == null, "D2_SOURCE_DUPLICATE", "Цикл повторяется", code);
            }
            need(!rules.isEmpty() && rules.keySet().equals(mapping.keySet()), "D2_RULES_MISSING", "Неполный каталог циклов/ресурсов", ruleId);

            Map<UUID, JsonNode> trains = index(root, "trains", "id");
            need(!trains.isEmpty(), "D2_SOURCE_INCOMPLETE", "Список составов пуст", saved.id());
            Map<UUID, Trip> trips = new HashMap<>();
            Map<UUID, List<Trip>> tripsByTrain = new HashMap<>();
            for (JsonNode row : rows(root, "fixedTrips")) {
                Trip t = new Trip(uuid(row, "id"), uuid(row, "train_id"), text(row, "label"),
                        time(row, "departure_at"), time(row, "arrival_at"), number(row, "distance_km"),
                        text(row, "origin"), text(row, "destination"));
                need(trains.containsKey(t.train()) && t.departure().isBefore(t.arrival()) && t.distance() > 0
                                && !t.departure().isBefore(start) && !t.arrival().isAfter(end), "D2_TRIP_INVALID",
                        "Некорректный рейс, состав или горизонт", t.id());
                minute(start, t.departure()); minute(start, t.arrival());
                need(trips.putIfAbsent(t.id(), t) == null, "D2_SOURCE_DUPLICATE", "Рейс повторяется", t.id());
                tripsByTrain.computeIfAbsent(t.train(), ignored -> new ArrayList<>()).add(t);
            }
            for (var entry : tripsByTrain.entrySet()) {
                entry.getValue().sort(Comparator.comparing(Trip::departure));
                String city = text(trains.get(entry.getKey()), "location");
                OffsetDateTime freeAt = start;
                for (Trip t : entry.getValue()) {
                    need(!t.departure().isBefore(freeAt), "D2_SOURCE_TRIP_OVERLAP", "Состав назначен в пересекающиеся рейсы", t.id());
                    need(city.equals(t.origin()), "D2_ROUTE_DISCONTINUITY", "Состав не находится в городе отправления", t.id());
                    city = t.destination(); freeAt = t.arrival();
                }
            }
            compareProjection(prepared, trains, resources, trips, findings);
            for (JsonNode row : rows(root, "trainPresence")) {
                need(trains.containsKey(uuid(row, "train_id")), "D2_UNKNOWN_TRAIN", "Присутствие неизвестного состава", uuid(row, "id"));
                need(Set.of("CONFIRMED", "SYNTHETIC").contains(text(row, "confirmation_status")),
                        "D2_UNCONFIRMED_PRESENCE", "Окно присутствия не подтверждено", uuid(row, "id"));
                need(time(row, "starts_at").isBefore(time(row, "ends_at")), "D2_PRESENCE_INVALID", "Пустое окно присутствия", uuid(row, "id"));
            }
            for (JsonNode row : rows(root, "resourceAvailability")) {
                need(resources.containsKey(text(row, "resource_id")), "D2_UNKNOWN_RESOURCE", "Календарь неизвестного ресурса", uuid(row, "id"));
                need(time(row, "starts_at").isBefore(time(row, "ends_at")), "D2_RESOURCE_CALENDAR_INVALID", "Пустое окно ресурса", uuid(row, "id"));
            }

            Map<UUID, JsonNode> events = index(root, "serviceEvents", "id");
            Map<UUID, Map<String, Long>> credits = new HashMap<>();
            for (JsonNode row : rows(root, "cycleBaselines")) {
                need(trains.containsKey(uuid(row, "train_id")), "D2_UNKNOWN_TRAIN", "История неизвестного состава", uuid(row, "train_id"));
                if (!ruleId.equals(uuid(row, "rule_set_id"))) continue;
                need(!time(row, "recorded_at").isAfter(start), "D2_FUTURE_BASELINE", "Начальный зачёт записан после начала горизонта", uuid(row, "train_id"));
                credit(credits, uuid(row, "train_id"), text(row, "cycle_code"), number(row, "credited_nominal_km"), rules);
            }
            for (JsonNode event : events.values()) {
                need(trains.containsKey(uuid(event, "train_id")) && ruleSets.containsKey(uuid(event, "rule_set_id")),
                        "D2_HISTORY_INVALID", "Неизвестный состав или версия правил события ТО", uuid(event, "id"));
                need(!time(event, "accepted_at").isBefore(time(event, "completed_at")), "D2_HISTORY_INVALID",
                        "Приёмка не может быть раньше завершения работы", uuid(event, "id"));
                need(!time(event, "accepted_at").isAfter(start), "D2_IN_HORIZON_HISTORY_UNSUPPORTED",
                        "Фактические приёмки внутри будущего горизонта требуют другого контракта", uuid(event, "id"));
            }
            for (JsonNode row : rows(root, "serviceCredits")) {
                JsonNode event = events.get(uuid(row, "service_event_id"));
                need(event != null && uuid(event, "rule_set_id").equals(uuid(row, "rule_set_id")), "D2_HISTORY_INVALID",
                        "Зачёт не связан с принятым событием той же версии правил", uuid(row, "service_event_id"));
                if (ruleId.equals(uuid(row, "rule_set_id"))) {
                    Rule performed = rules.get(text(event, "performed_cycle_code"));
                    Rule covered = rules.get(text(row, "covered_cycle_code"));
                    long nominal = number(row, "credited_nominal_km"), actual = number(event, "actual_odometer_km");
                    need(performed != null && covered != null && performed.rank() >= covered.rank()
                                    && nominal >= 0 && nominal % covered.interval() == 0
                                    && actual >= threshold(nominal, 10000 - covered.tolerance(), true)
                                    && actual <= threshold(nominal, 10000 + covered.tolerance(), false), "D2_HISTORY_CREDIT_INVALID",
                            "Зачёт не соответствует выполненному циклу или допустимому пробегу", uuid(event, "id"));
                    credit(credits, uuid(event, "train_id"), covered.code(), nominal, rules);
                }
            }
            Map<UUID, Long> initialKm = new HashMap<>();
            Map<UUID, OffsetDateTime> readingAt = new HashMap<>();
            for (JsonNode row : rows(root, "odometerReadings")) {
                UUID train = uuid(row, "train_id"); OffsetDateTime observed = time(row, "observed_at");
                need(trains.containsKey(train) && number(row, "odometer_km") >= 0, "D2_ODOMETER_INVALID", "Некорректный замер пробега", train);
                need(!observed.isAfter(start), "D2_IN_HORIZON_ODOMETER_UNSUPPORTED", "Замеры внутри горизонта требуют нового адаптера", train);
                if (!readingAt.containsKey(train) || observed.isAfter(readingAt.get(train))) {
                    readingAt.put(train, observed); initialKm.put(train, number(row, "odometer_km"));
                }
            }
            List<Expected> expected = new ArrayList<>();
            for (var entry : trains.entrySet()) {
                UUID train = entry.getKey();
                need("AVAILABLE".equals(text(entry.getValue(), "status")), "D2_TRAIN_STATE_UNSUPPORTED",
                        "E2 не проверяет состав в этом состоянии", train);
                Long initial = initialKm.get(train);
                need(initial != null, "D2_ODOMETER_MISSING", "Нет замера пробега к началу горизонта", train);
                long finalKm = initial;
                for (Trip t : tripsByTrain.getOrDefault(train, List.of())) finalKm = Math.addExact(finalKm, t.distance());
                Map<Long, List<Rule>> due = new TreeMap<>();
                for (Rule rule : rules.values()) {
                    Long lastCredit = credits.getOrDefault(train, Map.of()).get(rule.code());
                    need(lastCredit != null && lastCredit >= 0 && lastCredit <= initial && lastCredit % rule.interval() == 0,
                            "D2_CREDIT_MISSING_OR_INVALID", "Нет допустимого начального зачёта " + rule.code(), train);
                    long count = (finalKm - lastCredit) / rule.interval();
                    need(count <= 100000, "D2_SOURCE_TOO_LARGE", "Слишком много рубежей в одном горизонте", train);
                    for (long n = 1; n <= count; n++) due.computeIfAbsent(Math.addExact(lastCredit, Math.multiplyExact(n, rule.interval())),
                            ignored -> new ArrayList<>()).add(rule);
                    long next = Math.multiplyExact(Math.addExact(finalKm / rule.interval(), 1), rule.interval());
                    pending.add(new ValidationReport.PendingMilestone(train, rule.code(), next, next - finalKm));
                }
                for (var milestone : due.entrySet()) {
                    List<Rule> covered = milestone.getValue(); covered.sort(Comparator.comparingInt(Rule::rank).reversed());
                    need(covered.size() == 1 || covered.get(0).rank() != covered.get(1).rank(), "D2_AMBIGUOUS_ABSORPTION",
                            "Не определён старший цикл на рубеже " + milestone.getKey(), train);
                    long lower = 0, upper = Long.MAX_VALUE;
                    for (Rule rule : covered) {
                        lower = Math.max(lower, threshold(milestone.getKey(), 10000 - rule.tolerance(), true));
                        upper = Math.min(upper, threshold(milestone.getKey(), 10000 + rule.tolerance(), false));
                    }
                    need(initial <= upper, "D2_ALREADY_OVERDUE", "На начало горизонта цикл уже просрочен", train);
                    Rule senior = covered.getFirst();
                    // This UUID formula is the documented E2 handoff identity, not F2 business logic.
                    UUID id = UUID.nameUUIDFromBytes((saved.scenarioId() + ":" + train + ":" + milestone.getKey()
                            + ":" + senior.code()).getBytes(StandardCharsets.UTF_8));
                    expected.add(new Expected(id, train, milestone.getKey(), senior, lower, upper));
                }
            }
            requiredCount = expected.size();
            checkRequiredProjection(expected, prepared, findings);
            if (Set.of(PlannerResult.SolverStatus.OPTIMAL, PlannerResult.SolverStatus.FEASIBLE).contains(result.solverStatus())) {
                checkPlacements(root, expected, initialKm, tripsByTrain, trains, resources, prepared, result, findings);
            }
            findings.add(new Dto.Validation("D2_SCOPE_E2", "INFO",
                    "Проверены циклы, пробег, рейсы, присутствие и ресурсы E2. Уборка, резерв, выпуск и годовые работы не входят в этот набор."));
            if ("SYNTHETIC".equals(confirmation)) findings.add(new Dto.Validation("D2_MODEL_RULES", "WARNING",
                    "PASS означает согласованность модельного плана с его снимком; он не удостоверяет фактический допуск поезда."));
        } catch (SourceProblem e) {
            findings.add(new Dto.Validation(e.code, "CRITICAL", e.getMessage(), e.object, null, null));
        } catch (RuntimeException e) {
            findings.add(new Dto.Validation("D2_SOURCE_INVALID", "CRITICAL", "Невозможно полностью прочитать источник: " + e.getMessage()));
        }
        pending.sort(Comparator.comparing((ValidationReport.PendingMilestone p) -> p.trainId().toString()).thenComparing(ValidationReport.PendingMilestone::cycleCode));
        return ValidationReport.of(prepared, result, saved == null ? null : saved.id(),
                "SYNTHETIC".equals(confirmation) ? "E2_MODEL" : "E2_SOURCE", ruleVersion, confirmation, requiredCount, pending, findings);
    }

    private static void checkPlacements(JsonNode root, List<Expected> expected, Map<UUID, Long> initial,
                                         Map<UUID, List<Trip>> trips, Map<UUID, JsonNode> trains,
                                         Map<String, JsonNode> resources, ScenarioSnapshot source,
                                         PlannerResult result, List<Dto.Validation> findings) {
        Map<UUID, PlannerResult.PlannedBlock> placed = new HashMap<>();
        Set<UUID> ids = new HashSet<>(); expected.forEach(e -> ids.add(e.id()));
        for (var b : result.blocks()) {
            if (!ids.contains(b.blockId())) issue(findings, "D2_UNKNOWN_WORK", "Работа не вытекает из исходных правил", b);
            if (placed.putIfAbsent(b.blockId(), b) != null) issue(findings, "D2_DUPLICATE_WORK", "Обязательная работа повторяется", b);
        }
        Map<UUID, OffsetDateTime> previousEnd = new HashMap<>();
        for (Expected e : expected) {
            var b = placed.get(e.id());
            if (b == null) {
                findings.add(new Dto.Validation("D2_REQUIRED_WORK_MISSING", "CRITICAL", "Нет работы "
                        + e.senior().code() + "@" + e.nominal(), e.id().toString(), null, null));
                continue;
            }
            if (!e.train().equals(b.trainId()) || !e.senior().resource().equals(b.resourceId())) {
                issue(findings, "D2_WORK_ASSIGNMENT", "Изменён состав или ресурс обязательного цикла", b); continue;
            }
            if (!Duration.between(b.startAt(), b.endAt()).equals(Duration.ofMinutes(e.senior().duration())))
                issue(findings, "D2_WORK_DURATION", "Длительность не соответствует исходному циклу " + e.senior().code(), b);
            long atStart = initial.get(e.train());
            List<Trip> trainTrips = trips.getOrDefault(e.train(), List.of());
            String city = text(trains.get(e.train()), "location");
            for (Trip t : trainTrips) {
                if (!t.arrival().isAfter(b.startAt())) { atStart = Math.addExact(atStart, t.distance()); city = t.destination(); }
                if (overlaps(b.startAt(), b.endAt(), t.departure(), t.arrival()))
                    issue(findings, "D2_WORK_TRIP_CONFLICT", "Работа пересекается с фиксированным рейсом " + t.label(), b);
            }
            if (atStart < e.lower()) issue(findings, "D2_MILEAGE_TOO_EARLY", "До нижней границы пробега " + e.lower()
                    + " км не хватает " + (e.lower() - atStart) + " км", b);
            if (atStart > e.upper()) issue(findings, "D2_MILEAGE_OVERDUE", "Превышена верхняя граница пробега " + e.upper() + " км", b);
            long odometer = initial.get(e.train());
            for (Trip t : trainTrips) {
                odometer = Math.addExact(odometer, t.distance());
                if (odometer > e.upper() && t.departure().isBefore(b.endAt())) {
                    issue(findings, "D2_MILEAGE_DEADLINE", "Работа должна завершиться до отправления рейса " + t.label(), b); break;
                }
            }
            OffsetDateTime previous = previousEnd.put(e.train(), b.endAt());
            if (previous != null && b.startAt().isBefore(previous)) issue(findings, "D2_MILESTONE_ORDER", "Нарушен порядок номинальных рубежей", b);
            JsonNode resource = resources.get(b.resourceId());
            if (resource == null || !city.equals(text(resource, "location")))
                issue(findings, "D2_LOCATION", "В момент обслуживания состав находится в другом городе", b);
            boolean presence = false, available = false;
            for (JsonNode p : rows(root, "trainPresence")) {
                if (e.train().equals(uuid(p, "train_id")) && resource != null
                        && text(p, "location").equals(text(resource, "location"))
                        && contains(time(p, "starts_at"), time(p, "ends_at"), b.startAt(), b.endAt())) presence = true;
            }
            for (JsonNode a : rows(root, "resourceAvailability")) {
                if (b.resourceId().equals(text(a, "resource_id"))
                        && contains(time(a, "starts_at"), time(a, "ends_at"), b.startAt(), b.endAt())) available = true;
            }
            if (!presence) issue(findings, "D2_SOURCE_PRESENCE", "Работа целиком не помещается в исходное окно присутствия состава", b);
            if (!available) issue(findings, "D2_SOURCE_RESOURCE_WINDOW", "Работа целиком не помещается в исходный календарь ресурса", b);
            if (b.startAt().isBefore(source.horizonStart()) || b.endAt().isAfter(source.horizonEnd()))
                issue(findings, "D2_HORIZON", "Работа находится вне исходного горизонта", b);
        }
    }

    private static void compareProjection(ScenarioSnapshot prepared, Map<UUID, JsonNode> trains,
                                           Map<String, JsonNode> resources, Map<UUID, Trip> trips, List<Dto.Validation> findings) {
        Set<UUID> projectedTrains = new HashSet<>();
        for (var t : prepared.trains()) {
            projectedTrains.add(t.id()); JsonNode raw = trains.get(t.id());
            if (raw == null || !t.externalId().equals(text(raw, "external_id")))
                findings.add(new Dto.Validation("D2_PROJECTION_TRAIN", "CRITICAL", "Изменён состав в проекции", t.id().toString(), null, null));
        }
        Set<String> projectedResources = new HashSet<>(); prepared.resources().forEach(r -> projectedResources.add(r.id()));
        if (!projectedTrains.equals(trains.keySet()) || !projectedResources.equals(resources.keySet()))
            findings.add(new Dto.Validation("D2_PROJECTION_INCOMPLETE", "CRITICAL", "Проекция потеряла или добавила составы/ресурсы"));
        Set<UUID> projectedTrips = new HashSet<>();
        for (var t : prepared.fixedTrips()) {
            projectedTrips.add(t.id()); Trip raw = trips.get(t.id());
            if (raw == null || !raw.train().equals(t.trainId()) || raw.distance() != t.distanceKm()
                    || !raw.label().equals(t.label()) || !prepared.horizonStart().plusMinutes(t.startMinute()).isEqual(raw.departure())
                    || !prepared.horizonStart().plusMinutes(t.endMinute()).isEqual(raw.arrival()))
                findings.add(new Dto.Validation("D2_FIXED_TRIP_CHANGED", "CRITICAL", "Изменён фиксированный рейс", t.id().toString(), null, null));
        }
        if (!projectedTrips.equals(trips.keySet())) findings.add(new Dto.Validation("D2_FIXED_TRIP_MISSING", "CRITICAL", "Проекция потеряла или добавила фиксированные рейсы"));
    }

    private static void checkRequiredProjection(List<Expected> expected, ScenarioSnapshot source, List<Dto.Validation> findings) {
        Map<UUID, ScenarioSnapshot.ServiceBlock> actual = new HashMap<>(); source.blocks().forEach(b -> actual.put(b.id(), b));
        Set<UUID> ids = new HashSet<>();
        for (Expected e : expected) {
            ids.add(e.id()); var block = actual.get(e.id());
            if (block == null) findings.add(new Dto.Validation("D2_PROJECTION_MISSING_WORK", "CRITICAL",
                    "Генератор не передал обязательный цикл " + e.senior().code() + "@" + e.nominal(), e.id().toString(), null, null));
            else if (!e.train().equals(block.trainId()) || !e.senior().resource().equals(block.resourceId())
                    || e.senior().duration() != block.durationMinutes() || block.kind() != ScenarioSnapshot.ServiceBlock.Kind.GENERAL)
                findings.add(new Dto.Validation("D2_PROJECTION_WORK_CHANGED", "CRITICAL", "Параметры цикла не совпадают с источником", e.id().toString(), null, null));
        }
        if (!ids.equals(actual.keySet())) findings.add(new Dto.Validation("D2_PROJECTION_WORK_SET", "CRITICAL", "Набор работ проекции не совпадает с обязанностями из источника"));
    }

    private static void credit(Map<UUID, Map<String, Long>> credits, UUID train, String code, long nominal, Map<String, Rule> rules) {
        need(rules.containsKey(code) && nominal >= 0, "D2_HISTORY_INVALID", "Неизвестный или некорректный зачёт цикла", train);
        credits.computeIfAbsent(train, ignored -> new HashMap<>()).merge(code, nominal, Math::max);
    }

    private static long threshold(long nominal, int multiplier, boolean roundUp) {
        BigInteger[] parts = BigInteger.valueOf(nominal).multiply(BigInteger.valueOf(multiplier)).divideAndRemainder(BigInteger.valueOf(10000));
        return parts[0].add(roundUp && parts[1].signum() != 0 ? BigInteger.ONE : BigInteger.ZERO).longValueExact();
    }

    static boolean overlaps(OffsetDateTime a, OffsetDateTime b, OffsetDateTime c, OffsetDateTime d) { return a.isBefore(d) && c.isBefore(b); }
    static boolean contains(OffsetDateTime a, OffsetDateTime b, OffsetDateTime c, OffsetDateTime d) { return !c.isBefore(a) && !d.isAfter(b); }

    private static void issue(List<Dto.Validation> findings, String code, String message, PlannerResult.PlannedBlock b) {
        findings.add(new Dto.Validation(code, "CRITICAL", message, b.blockId().toString(), b.startAt().toString(), b.endAt().toString()));
    }
    private static JsonNode rows(JsonNode root, String field) {
        JsonNode value = root.path(field); need(value.isArray(), "D2_SOURCE_INCOMPLETE", "Отсутствует массив " + field, field); return value;
    }
    private static Map<UUID, JsonNode> index(JsonNode root, String field, String key) {
        Map<UUID, JsonNode> result = new HashMap<>();
        for (JsonNode row : rows(root, field)) { UUID id = uuid(row, key); need(result.putIfAbsent(id, row) == null, "D2_SOURCE_DUPLICATE", "Повтор ID в " + field, id); }
        return result;
    }
    private static String text(JsonNode row, String field) {
        JsonNode value = row.path(field); need(value.isTextual() && !value.asText().isBlank(), "D2_SOURCE_INCOMPLETE", "Отсутствует " + field, field); return value.asText();
    }
    private static long number(JsonNode row, String field) {
        JsonNode value = row.path(field); need(value.isIntegralNumber() && value.canConvertToLong(), "D2_SOURCE_INVALID", "Ожидается целое число " + field, field); return value.asLong();
    }
    private static int integer(JsonNode row, String field) { return Math.toIntExact(number(row, field)); }
    private static UUID uuid(JsonNode row, String field) { return UUID.fromString(text(row, field)); }
    private static OffsetDateTime time(JsonNode row, String field) { return OffsetDateTime.parse(text(row, field)); }
    private static int minute(OffsetDateTime origin, OffsetDateTime at) {
        Duration d = Duration.between(origin, at); need(d.getNano() == 0 && d.getSeconds() % 60 == 0, "D2_TIME_PRECISION", "Время должно лежать на целой минуте", at); return Math.toIntExact(d.toMinutes());
    }
    private static void need(boolean ok, String code, String message, Object object) { if (!ok) throw new SourceProblem(code, message, object); }
    private static final class SourceProblem extends RuntimeException {
        final String code, object;
        SourceProblem(String code, String message, Object object) { super(message); this.code = code; this.object = object == null ? null : object.toString(); }
    }
}
