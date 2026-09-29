package com.vsm.okno.validation;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.dto.Dto;
import com.vsm.okno.planning.E3MileageObligationRecalculator;
import com.vsm.okno.planning.E3TripAssignmentLedger;
import com.vsm.okno.planning.MileageObligationGenerator;
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

/** Independent reconstruction of mileage obligations after effective trip assignment. */
public final class E3MileageObligationAudit {
    private record Rule(String code, long interval, int tolerance, int duration, int rank,
                        String source, Set<String> resources) {}
    private record Expected(UUID id, UUID trainId, String cycle, long nominal, long lower, long upper,
                            OffsetDateTime releaseAt, OffsetDateTime dueAt, int duration,
                            String source, Set<String> resources,
                            List<MileageObligationGenerator.CoveredCycle> covers, UUID predecessor) {}

    private final ObjectMapper json = new ObjectMapper();

    public List<Dto.Validation> check(SourceSnapshot saved, E3TripAssignmentLedger.Assignment assignment,
                                      E3MileageObligationRecalculator.Recalculated recalculated,
                                      OffsetDateTime frozenUntil, int preparationMinutes) {
        List<Dto.Validation> issues = new ArrayList<>(new E3TripAssignmentAudit()
                .check(saved, assignment, frozenUntil, preparationMinutes));
        if (!issues.isEmpty()) return List.copyOf(issues);
        try {
            require(saved.scenarioId().equals(recalculated.scenarioId())
                            && saved.snapshotHash().equals(recalculated.snapshotHash()),
                    "D2_E3_OBLIGATION_SOURCE", "Обязанности относятся к другому snapshot");
            JsonNode root = json.readTree(saved.canonicalPayload());
            JsonNode scenario = root.path("scenario");
            OffsetDateTime start = time(scenario, "horizon_start"), end = time(scenario, "horizon_end");
            UUID ruleSetId = uuid(scenario, "rule_set_id");
            JsonNode ruleSet = null;
            for (JsonNode row : rows(root, "ruleSets")) if (ruleSetId.equals(uuid(row, "id"))) ruleSet = row;
            require(ruleSet != null && Set.of("SYNTHETIC", "CONFIRMED")
                            .contains(text(ruleSet, "confirmation_status"))
                            && "ABSOLUTE_GRID".equals(text(ruleSet, "mileage_policy"))
                            && "NOMINAL_MILESTONE".equals(text(ruleSet, "tolerance_basis")),
                    "D2_E3_RULE_POLICY", "Исходная политика пробега не поддерживается");

            Set<String> resourceIds = new HashSet<>();
            for (JsonNode row : rows(root, "resources"))
                require(resourceIds.add(text(row, "id")),
                        "D2_E3_RESOURCE_SOURCE", "Ресурс повторяется в источнике");
            Map<String, Set<String>> paths = new HashMap<>();
            for (JsonNode row : rows(root, "cycleResources")) {
                if (ruleSetId.equals(uuid(row, "rule_set_id"))) {
                    String resource = text(row, "resource_id");
                    require(resourceIds.contains(resource), "D2_E3_RESOURCE_SOURCE",
                            "Для цикла указан неизвестный ресурс");
                    require(paths.computeIfAbsent(text(row, "cycle_code"), ignored -> new HashSet<>())
                                    .add(resource), "D2_E3_RESOURCE_SOURCE",
                            "Ресурс цикла повторяется в источнике");
                }
            }
            Map<String, Rule> rules = new HashMap<>();
            for (JsonNode row : rows(root, "cycleRules")) {
                if (!ruleSetId.equals(uuid(row, "rule_set_id"))) continue;
                String code = text(row, "code");
                Rule rule = new Rule(code, number(row, "interval_km"),
                        Math.toIntExact(number(row, "tolerance_basis_points")),
                        Math.toIntExact(number(row, "duration_minutes")),
                        Math.toIntExact(number(row, "rank")), text(row, "source"),
                        Set.copyOf(paths.getOrDefault(code, Set.of())));
                require(rule.interval() > 0 && rule.tolerance() >= 0 && rule.tolerance() < 10000
                                && rule.duration() > 0 && rule.rank() >= 0 && !rule.resources().isEmpty()
                                && rules.putIfAbsent(code, rule) == null,
                        "D2_E3_RULE_INVALID", "Неверное исходное правило " + code);
            }
            require(!rules.isEmpty(), "D2_E3_RULES_MISSING", "Нет активных правил ТО");

            Map<UUID, Map<String, Long>> credits = new HashMap<>();
            for (JsonNode row : rows(root, "cycleBaselines")) {
                if (ruleSetId.equals(uuid(row, "rule_set_id")) && !time(row, "recorded_at").isAfter(start))
                    credit(credits, uuid(row, "train_id"), text(row, "cycle_code"),
                            number(row, "credited_nominal_km"));
            }
            Map<UUID, JsonNode> events = new HashMap<>();
            for (JsonNode row : rows(root, "serviceEvents")) events.put(uuid(row, "id"), row);
            for (JsonNode row : rows(root, "serviceCredits")) {
                JsonNode event = events.get(uuid(row, "service_event_id"));
                if (event != null && ruleSetId.equals(uuid(row, "rule_set_id"))
                        && !time(event, "accepted_at").isAfter(start))
                    credit(credits, uuid(event, "train_id"), text(row, "covered_cycle_code"),
                            number(row, "credited_nominal_km"));
            }

            Map<UUID, List<E3TripAssignmentLedger.AssignedTrip>> tripsByTrain = new HashMap<>();
            Map<UUID, ScenarioSnapshot.FixedTrip> actualTrips = new HashMap<>();
            for (var trip : recalculated.effectiveTrips()) {
                if (actualTrips.putIfAbsent(trip.id(), trip) != null)
                    issue(issues, "D2_E3_EFFECTIVE_TRIP_SET", "Повтор рейса в пробеговой проекции", trip.id());
            }
            for (var trip : assignment.trips()) {
                tripsByTrain.computeIfAbsent(trip.effectiveTrainId(), ignored -> new ArrayList<>()).add(trip);
                var projected = actualTrips.get(trip.id());
                if (projected == null || !trip.effectiveTrainId().equals(projected.trainId())
                        || !trip.label().equals(projected.label())
                        || trip.distanceKm() != projected.distanceKm()
                        || !trip.departureAt().isEqual(start.plusMinutes(projected.startMinute()))
                        || !trip.arrivalAt().isEqual(start.plusMinutes(projected.endMinute())))
                    issue(issues, "D2_E3_EFFECTIVE_TRIP_CHANGED", "Рейс изменён при генерации ТО", trip.id());
            }
            if (actualTrips.size() != assignment.trips().size())
                issue(issues, "D2_E3_EFFECTIVE_TRIP_SET", "Число рейсов изменилось при генерации ТО", null);

            List<Expected> expected = new ArrayList<>();
            for (var train : assignment.trains().values()) {
                UUID trainId = train.trainId();
                List<E3TripAssignmentLedger.AssignedTrip> trips = tripsByTrain.getOrDefault(trainId, List.of())
                        .stream().sorted(Comparator.comparing(E3TripAssignmentLedger.AssignedTrip::departureAt)).toList();
                long initialKm = train.initialKm(), finalKm = train.finalKm();
                Map<Long, List<Rule>> byNominal = new TreeMap<>();
                for (Rule rule : rules.values()) {
                    Long last = credits.getOrDefault(trainId, Map.of()).get(rule.code());
                    require(last != null && last >= 0 && last <= initialKm && last % rule.interval() == 0,
                            "D2_E3_CREDIT_INVALID", "Начальный зачёт отсутствует или неверен: " + rule.code());
                    long count = (finalKm - last) / rule.interval();
                    require(count >= 0 && count <= 100000, "D2_E3_SOURCE_TOO_LARGE", "Неверное число рубежей");
                    for (long n = 1; n <= count; n++) {
                        long nominal = Math.addExact(last, Math.multiplyExact(n, rule.interval()));
                        byNominal.computeIfAbsent(nominal, ignored -> new ArrayList<>()).add(rule);
                    }
                }
                UUID previous = null;
                for (var milestone : byNominal.entrySet()) {
                    List<Rule> due = milestone.getValue();
                    due.sort(Comparator.comparingInt(Rule::rank).reversed());
                    require(due.size() == 1 || due.get(0).rank() != due.get(1).rank(),
                            "D2_E3_AMBIGUOUS_CYCLE", "Два старших цикла на одном рубеже");
                    long nominal = milestone.getKey(), lower = 0, upper = Long.MAX_VALUE;
                    for (Rule rule : due) {
                        lower = Math.max(lower, threshold(nominal, 10000 - rule.tolerance(), true));
                        upper = Math.min(upper, threshold(nominal, 10000 + rule.tolerance(), false));
                    }
                    require(initialKm <= upper, "D2_E3_ALREADY_OVERDUE", "Цикл просрочен до начала горизонта");
                    OffsetDateTime release = firstArrivalAtOrAbove(trips, initialKm, lower, start);
                    OffsetDateTime deadline = firstDepartureExceeding(trips, initialKm, upper, end);
                    Rule senior = due.getFirst();
                    UUID blockId = UUID.nameUUIDFromBytes((saved.scenarioId() + ":" + trainId + ":"
                            + nominal + ":" + senior.code()).getBytes(StandardCharsets.UTF_8));
                    expected.add(new Expected(blockId, trainId, senior.code(), nominal, lower, upper,
                            release, deadline, senior.duration(), senior.source(), senior.resources(),
                            due.stream().map(rule -> new MileageObligationGenerator.CoveredCycle(rule.code(), nominal))
                                    .toList(), previous));
                    previous = blockId;
                }
            }
            Map<UUID, MileageObligationGenerator.Obligation> actualObligations = new HashMap<>();
            for (var item : recalculated.obligations()) {
                if (actualObligations.putIfAbsent(item.blockId(), item) != null)
                    issue(issues, "D2_E3_OBLIGATION_DUPLICATE", "Работа повторяется", item.blockId());
            }
            Map<UUID, ScenarioSnapshot.ServiceBlock> actualBlocks = new HashMap<>();
            for (var block : recalculated.blocks()) {
                if (actualBlocks.putIfAbsent(block.id(), block) != null)
                    issue(issues, "D2_E3_BLOCK_DUPLICATE", "Блок ТО повторяется", block.id());
            }
            Set<UUID> expectedIds = new HashSet<>();
            for (Expected item : expected) {
                expectedIds.add(item.id());
                var obligation = actualObligations.get(item.id());
                var block = actualBlocks.get(item.id());
                if (obligation == null || block == null) {
                    issue(issues, "D2_E3_REQUIRED_WORK_MISSING", "Обязательная работа исчезла", item.id());
                    continue;
                }
                if (!item.trainId().equals(obligation.trainId())
                        || !item.cycle().equals(obligation.cycleCode())
                        || item.nominal() != obligation.nominalKm()
                        || item.lower() != obligation.releaseOdometerKm()
                        || item.upper() != obligation.dueOdometerKm()
                        || !item.releaseAt().isEqual(obligation.releaseAt())
                        || !item.dueAt().isEqual(obligation.dueAt())
                        || !item.covers().equals(obligation.covers())
                        || !item.source().equals(obligation.ruleSource())
                        || obligation.completionRequiredBeforeNextTrip() != !item.dueAt().isEqual(end))
                    issue(issues, "D2_E3_OBLIGATION_CHANGED", "Рубеж или покрытие цикла неверны", item.id());
                if (!item.trainId().equals(block.trainId())
                        || !item.resources().contains(block.resourceId())
                        || !item.resources().equals(Set.copyOf(block.allowedResourceIds()))
                        || item.duration() != block.durationMinutes()
                        || block.kind() != ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE
                        || minute(start, item.releaseAt()) != block.earliestStartMinute()
                        || minute(start, item.dueAt()) != block.latestEndMinute()
                        || !block.predecessorIds().equals(item.predecessor() == null
                        ? List.of() : List.of(item.predecessor())))
                    issue(issues, "D2_E3_BLOCK_CHANGED", "Длительность или окно ТО неверны", item.id());
            }
            if (!expectedIds.equals(actualObligations.keySet()) || !expectedIds.equals(actualBlocks.keySet()))
                issue(issues, "D2_E3_WORK_SET", "Набор пробеговых работ отличается от источника", null);
        } catch (SourceProblem error) {
            issue(issues, error.code, error.getMessage(), null);
        } catch (RuntimeException error) {
            issue(issues, "D2_E3_OBLIGATION_INVALID", "Нельзя полностью проверить рубежи: " + error.getMessage(), null);
        }
        return List.copyOf(issues);
    }

    private static OffsetDateTime firstArrivalAtOrAbove(List<E3TripAssignmentLedger.AssignedTrip> trips,
                                                         long initial, long lower, OffsetDateTime start) {
        if (initial >= lower) return start;
        long km = initial;
        for (var trip : trips) {
            km = Math.addExact(km, trip.distanceKm());
            if (km >= lower) return trip.arrivalAt();
        }
        throw new SourceProblem("D2_E3_MILEAGE_RELEASE", "Нижняя граница пробега не достигнута");
    }
    private static OffsetDateTime firstDepartureExceeding(List<E3TripAssignmentLedger.AssignedTrip> trips,
                                                           long initial, long upper, OffsetDateTime end) {
        long km = initial;
        for (var trip : trips) {
            if (Math.addExact(km, trip.distanceKm()) > upper) return trip.departureAt();
            km += trip.distanceKm();
        }
        return end;
    }
    private static long threshold(long nominal, int factor, boolean ceil) {
        BigInteger[] parts = BigInteger.valueOf(nominal).multiply(BigInteger.valueOf(factor))
                .divideAndRemainder(BigInteger.valueOf(10000));
        return parts[0].add(ceil && parts[1].signum() != 0 ? BigInteger.ONE : BigInteger.ZERO).longValueExact();
    }
    private static int minute(OffsetDateTime start, OffsetDateTime at) {
        Duration elapsed = Duration.between(start, at);
        require(elapsed.getNano() == 0 && elapsed.getSeconds() % 60 == 0,
                "D2_E3_TIME_PRECISION", "Время не на целой минуте");
        return Math.toIntExact(elapsed.toMinutes());
    }
    private static void credit(Map<UUID, Map<String, Long>> credits, UUID train, String cycle, long km) {
        credits.computeIfAbsent(train, ignored -> new HashMap<>()).merge(cycle, km, Math::max);
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
    private static void issue(List<Dto.Validation> issues, String code, String message, UUID object) {
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
