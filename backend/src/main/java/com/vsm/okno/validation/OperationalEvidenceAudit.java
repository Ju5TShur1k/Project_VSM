package com.vsm.okno.validation;

import com.vsm.okno.dto.Dto;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.ScenarioSnapshot;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * D2 E3 checks with explicit evidence. No inferred defaults or solver calls.
 * These checks alone do not grant PASS: D1/F1 must supply source-bound policies.
 */
public final class OperationalEvidenceAudit {
    private OperationalEvidenceAudit() {}
    public record CleaningPolicy(int tripsPerCleaning, int minMinutes, int maxMinutes, String source) {}
    public record ReserveWindow(UUID trainId, String location, OffsetDateTime startAt, OffsetDateTime endAt, String source) {}
    public record ReserveRequirement(String location, OffsetDateTime startAt, OffsetDateTime endAt, int minimum, String source) {}
    public record CalendarDue(UUID blockId, OffsetDateTime dueAt, String source) {}

    public static List<Dto.Validation> cleaning(ScenarioSnapshot source, PlannerResult result,
                                                Map<UUID, Integer> initialCounters, CleaningPolicy policy) {
        List<Dto.Validation> issues = new ArrayList<>();
        if (policy == null || policy.tripsPerCleaning() <= 0 || policy.minMinutes() <= 0
                || policy.maxMinutes() < policy.minMinutes() || blank(policy.source())) {
            add(issues, "D2_CLEANING_POLICY_MISSING", "Нет явного правила уборки", null, null, null); return issues;
        }
        if (!Set.of("1.3", "1.4").contains(source.schemaVersion())) {
            add(issues, "D2_CLEANING_CLASSIFICATION_MISSING", "Уборка должна иметь отдельный тип работы", null, null, null); return issues;
        }
        Map<UUID, ScenarioSnapshot.ServiceBlock> required = source.blocks().stream().collect(Collectors.toMap(ScenarioSnapshot.ServiceBlock::id, Function.identity()));
        Map<UUID, List<PlannerResult.PlannedBlock>> cleans = new HashMap<>();
        for (var b : result.blocks()) {
            var raw = required.get(b.blockId());
            if (raw != null && raw.kind() == ScenarioSnapshot.ServiceBlock.Kind.CLEANING) {
                cleans.computeIfAbsent(b.trainId(), ignored -> new ArrayList<>()).add(b);
                Duration duration = Duration.between(b.startAt(), b.endAt());
                if (duration.compareTo(Duration.ofMinutes(policy.minMinutes())) < 0
                        || duration.compareTo(Duration.ofMinutes(policy.maxMinutes())) > 0)
                    add(issues, "D2_CLEANING_DURATION", "Уборка не соответствует исходной длительности", b.blockId(), b.startAt(), b.endAt());
            }
        }
        Map<UUID, List<ScenarioSnapshot.FixedTrip>> trips = source.fixedTrips().stream().collect(Collectors.groupingBy(ScenarioSnapshot.FixedTrip::trainId));
        for (var entry : trips.entrySet()) {
            UUID train = entry.getKey(); Integer counter = initialCounters.get(train);
            if (counter == null || counter < 0 || counter > policy.tripsPerCleaning()) {
                add(issues, "D2_CLEANING_COUNTER_MISSING", "Нет допустимого начального счётчика уборки", train, null, null); continue;
            }
            List<ScenarioSnapshot.FixedTrip> ordered = entry.getValue().stream().sorted(Comparator.comparingInt(ScenarioSnapshot.FixedTrip::startMinute)).toList();
            OffsetDateTime fourthArrival = source.horizonStart();
            Set<UUID> used = new HashSet<>();
            for (var trip : ordered) {
                OffsetDateTime departure = source.horizonStart().plusMinutes(trip.startMinute());
                if (counter == policy.tripsPerCleaning()) {
                    OffsetDateTime earliest = fourthArrival;
                    var accepted = cleans.getOrDefault(train, List.of()).stream()
                            .filter(c -> !used.contains(c.blockId()) && SourcePlanAudit.contains(earliest, departure, c.startAt(), c.endAt()))
                            .min(Comparator.comparing(PlannerResult.PlannedBlock::endAt)).orElse(null);
                    if (accepted == null) add(issues, "D2_CLEANING_MISSING", "Уборка после " + counter + " рейсов не закончена до следующего отправления", train, earliest, departure);
                    else used.add(accepted.blockId());
                    counter = 0;
                }
                counter++; fourthArrival = source.horizonStart().plusMinutes(trip.endMinute());
            }
            if (counter == policy.tripsPerCleaning()) {
                OffsetDateTime earliest = fourthArrival;
                boolean completed = cleans.getOrDefault(train, List.of()).stream().anyMatch(c -> !used.contains(c.blockId())
                        && SourcePlanAudit.contains(earliest, source.horizonEnd(), c.startAt(), c.endAt()));
                if (!completed) issues.add(new Dto.Validation("D2_CLEANING_PENDING", "INFO", "Уборка остаётся обязательством следующего горизонта",
                        train.toString(), earliest.toString(), source.horizonEnd().toString()));
            }
        }
        return List.copyOf(issues);
    }

    public static List<Dto.Validation> reserve(ScenarioSnapshot source, PlannerResult result,
                                               List<ReserveWindow> eligibility, List<ReserveRequirement> requirements) {
        List<Dto.Validation> issues = new ArrayList<>();
        if (requirements.isEmpty() || eligibility.isEmpty()) {
            add(issues, "D2_RESERVE_EVIDENCE_MISSING", "Нет правил по городам и явных окон пригодности к резерву", null, null, null); return issues;
        }
        Set<UUID> trains = source.trains().stream().map(ScenarioSnapshot.Train::id).collect(Collectors.toSet());
        for (ReserveWindow w : eligibility) {
            if (!trains.contains(w.trainId()) || blank(w.location()) || blank(w.source()) || !valid(w.startAt(), w.endAt())) {
                add(issues, "D2_RESERVE_EVIDENCE_INVALID", "Некорректное окно пригодности резерва", w.trainId(), w.startAt(), w.endAt()); return issues;
            }
        }
        for (int i = 0; i < eligibility.size(); i++) for (int j = i + 1; j < eligibility.size(); j++) {
            var a = eligibility.get(i); var b = eligibility.get(j);
            if (a.trainId().equals(b.trainId()) && !a.location().equals(b.location())
                    && SourcePlanAudit.overlaps(a.startAt(), a.endAt(), b.startAt(), b.endAt()))
                add(issues, "D2_RESERVE_TWO_LOCATIONS", "Один резервный состав одновременно указан в двух городах", a.trainId(), a.startAt(), a.endAt());
        }
        for (ReserveRequirement requirement : requirements) {
            if (blank(requirement.location()) || blank(requirement.source()) || !valid(requirement.startAt(), requirement.endAt())
                    || requirement.minimum() < 0 || requirement.startAt().isBefore(source.horizonStart())
                    || requirement.endAt().isAfter(source.horizonEnd())) {
                add(issues, "D2_RESERVE_POLICY_INVALID", "Некорректный период/минимум резерва", requirement.location(), requirement.startAt(), requirement.endAt()); continue;
            }
            TreeSet<OffsetDateTime> boundaries = new TreeSet<>(Comparator.comparing(OffsetDateTime::toInstant));
            boundaries.add(requirement.startAt()); boundaries.add(requirement.endAt());
            eligibility.forEach(w -> { boundaries.add(w.startAt()); boundaries.add(w.endAt()); });
            result.blocks().forEach(b -> { boundaries.add(b.startAt()); boundaries.add(b.endAt()); });
            source.fixedTrips().forEach(t -> { boundaries.add(source.horizonStart().plusMinutes(t.startMinute())); boundaries.add(source.horizonStart().plusMinutes(t.endMinute())); });
            source.operations().fixedOccupancies().forEach(o -> { boundaries.add(source.horizonStart().plusMinutes(o.startMinute())); boundaries.add(source.horizonStart().plusMinutes(o.endMinute())); });
            OffsetDateTime previous = null;
            for (OffsetDateTime at : boundaries) {
                if (previous != null && !previous.isBefore(requirement.startAt()) && !at.isAfter(requirement.endAt())) {
                    OffsetDateTime from = previous;
                    long ready = eligibility.stream().filter(w -> w.location().equals(requirement.location())
                                    && SourcePlanAudit.contains(w.startAt(), w.endAt(), from, at))
                            .map(ReserveWindow::trainId).distinct().filter(id -> !busy(source, result, id, from, at)).count();
                    if (ready < requirement.minimum()) add(issues, "D2_RESERVE_CITY_SHORTAGE", "В " + requirement.location()
                            + " доступно " + ready + ", требуется " + requirement.minimum(), requirement.location(), from, at);
                }
                previous = at;
            }
        }
        return List.copyOf(issues);
    }

    public static List<Dto.Validation> frozen(PlannerResult result, List<PlannerResult.PlannedBlock> pinnedSource) {
        List<Dto.Validation> issues = new ArrayList<>();
        for (var fixed : pinnedSource) {
            List<PlannerResult.PlannedBlock> matches = result.blocks().stream().filter(b -> b.blockId().equals(fixed.blockId())).toList();
            if (matches.size() != 1 || !matches.getFirst().trainId().equals(fixed.trainId())
                    || !matches.getFirst().resourceId().equals(fixed.resourceId())
                    || !matches.getFirst().startAt().isEqual(fixed.startAt()) || !matches.getFirst().endAt().isEqual(fixed.endAt()))
                add(issues, "D2_FROZEN_WORK_CHANGED", "Закреплённая исходная работа исчезла или изменена", fixed.blockId(), fixed.startAt(), fixed.endAt());
        }
        return List.copyOf(issues);
    }

    public static List<Dto.Validation> release(ScenarioSnapshot source, PlannerResult result) {
        List<Dto.Validation> issues = new ArrayList<>();
        for (var gate : source.operations().releaseRequirements()) {
            var work = result.blocks().stream().filter(b -> b.blockId().equals(gate.maintenanceBlockId())).findFirst().orElse(null);
            var check = result.blocks().stream().filter(b -> b.blockId().equals(gate.checkBlockId())).findFirst().orElse(null);
            if (work == null || check == null || !work.trainId().equals(check.trainId()) || check.startAt().isBefore(work.endAt())) {
                add(issues, "D2_RELEASE_CHECK_MISSING_OR_EARLY", "Нет отдельной приёмки после завершения ТО", gate.maintenanceBlockId(),
                        work == null ? null : work.startAt(), check == null ? null : check.endAt()); continue;
            }
            var next = source.fixedTrips().stream().filter(t -> t.trainId().equals(work.trainId())
                            && !source.horizonStart().plusMinutes(t.startMinute()).isBefore(work.startAt()))
                    .min(Comparator.comparingInt(ScenarioSnapshot.FixedTrip::startMinute)).orElse(null);
            if (next != null && check.endAt().isAfter(source.horizonStart().plusMinutes(next.startMinute())))
                add(issues, "D2_RELEASE_AFTER_DEPARTURE", "Плановая приёмка заканчивается после отправления", gate.checkBlockId(), check.startAt(), check.endAt());
        }
        if (!source.operations().releaseRequirements().isEmpty()) issues.add(new Dto.Validation("D2_PLANNED_RELEASE_ONLY", "INFO",
                "Проверено место плановой приёмки в графике. Фактический допуск требует принятого события исполнения."));
        return List.copyOf(issues);
    }

    public static List<Dto.Validation> preparation(ScenarioSnapshot source, PlannerResult result, Map<UUID, Integer> minutesByTrip) {
        List<Dto.Validation> issues = new ArrayList<>();
        for (var trip : source.fixedTrips()) {
            Integer duration = minutesByTrip.get(trip.id());
            if (duration == null || duration < 0) {
                add(issues, "D2_PREPARATION_RULE_MISSING", "Нет явной длительности подготовки перед рейсом", trip.id(), null, null); continue;
            }
            OffsetDateTime departure = source.horizonStart().plusMinutes(trip.startMinute()), start = departure.minusMinutes(duration);
            for (var b : result.blocks()) if (b.trainId().equals(trip.trainId()) && SourcePlanAudit.overlaps(start, departure, b.startAt(), b.endAt()))
                add(issues, "D2_PREPARATION_CONFLICT", "Работа оставляет недостаточно времени подготовки к отправлению", b.blockId(), start, departure);
            for (var previous : source.fixedTrips()) if (!previous.id().equals(trip.id()) && previous.trainId().equals(trip.trainId())
                    && SourcePlanAudit.overlaps(start, departure, source.horizonStart().plusMinutes(previous.startMinute()), source.horizonStart().plusMinutes(previous.endMinute())))
                add(issues, "D2_PREPARATION_TRIP_CONFLICT", "Подготовка пересекается с предыдущим рейсом", trip.id(), start, departure);
        }
        return List.copyOf(issues);
    }

    public static List<Dto.Validation> locations(PlannerResult result, Map<UUID, String> requiredCityByBlock, Map<String, String> resourceCities) {
        List<Dto.Validation> issues = new ArrayList<>();
        for (var b : result.blocks()) {
            String required = requiredCityByBlock.get(b.blockId());
            if (required == null) continue;
            if (blank(required) || !required.equals(resourceCities.get(b.resourceId())))
                add(issues, "D2_REQUIRED_LOCATION", "Работа должна выполняться в " + required + "; ресурс находится в "
                        + resourceCities.getOrDefault(b.resourceId(), "неизвестном городе"), b.blockId(), b.startAt(), b.endAt());
        }
        return List.copyOf(issues);
    }

    public static List<Dto.Validation> calendar(ScenarioSnapshot source, PlannerResult result, List<CalendarDue> due) {
        List<Dto.Validation> issues = new ArrayList<>();
        for (CalendarDue obligation : due) {
            if (blank(obligation.source()) || obligation.blockId() == null || obligation.dueAt() == null) {
                add(issues, "D2_CALENDAR_RULE_MISSING", "Неполная календарная обязанность", obligation.blockId(), null, null); continue;
            }
            if (obligation.dueAt().isAfter(source.horizonEnd())) {
                issues.add(new Dto.Validation("D2_CALENDAR_PENDING", "INFO", "Календарная работа остаётся за горизонтом",
                        obligation.blockId().toString(), null, obligation.dueAt().toString())); continue;
            }
            var b = result.blocks().stream().filter(p -> p.blockId().equals(obligation.blockId())).findFirst().orElse(null);
            if (b == null || b.endAt().isAfter(obligation.dueAt()))
                add(issues, "D2_CALENDAR_DEADLINE", "Календарная работа отсутствует или просрочена", obligation.blockId(), null, obligation.dueAt());
        }
        return List.copyOf(issues);
    }

    public static List<Dto.Validation> pairs(ScenarioSnapshot source, Map<UUID, String> pairKeyByTrip) {
        List<Dto.Validation> issues = new ArrayList<>();
        Map<String, List<ScenarioSnapshot.FixedTrip>> groups = new HashMap<>();
        for (var trip : source.fixedTrips()) {
            String key = pairKeyByTrip.get(trip.id());
            if (blank(key)) add(issues, "D2_PAIR_EVIDENCE_MISSING", "Не задана пара для рейса", trip.id(), null, null);
            else groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(trip);
        }
        for (var entry : groups.entrySet()) {
            var group = entry.getValue();
            if (group.size() != 2 || group.get(0).trainId().equals(group.get(1).trainId())
                    || group.get(0).startMinute() != group.get(1).startMinute()
                    || group.get(0).endMinute() != group.get(1).endMinute() || group.get(0).distanceKm() != group.get(1).distanceKm())
                add(issues, "D2_PAIR_INCOMPLETE", "Нет двух согласованных составов парного рейса", entry.getKey(), null, null);
        }
        return List.copyOf(issues);
    }

    public static List<Dto.Validation> maintenanceCapacity(ScenarioSnapshot source, PlannerResult result, int maximum, String policySource) {
        List<Dto.Validation> issues = new ArrayList<>();
        if (maximum <= 0 || blank(policySource)) { add(issues, "D2_CAPACITY_POLICY_MISSING", "Нет явного лимита обслуживания", null, null, null); return issues; }
        if (source.blocks().stream().anyMatch(b -> b.kind() == ScenarioSnapshot.ServiceBlock.Kind.GENERAL)) {
            add(issues, "D2_MAINTENANCE_CLASSIFICATION_MISSING", "Для проверки лимита нужен явный тип каждой работы", null, null, null); return issues;
        }
        Set<UUID> maintenance = source.blocks().stream().filter(b -> b.kind() == ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE
                || b.kind() == ScenarioSnapshot.ServiceBlock.Kind.RELEASE_GATED_MAINTENANCE).map(ScenarioSnapshot.ServiceBlock::id).collect(Collectors.toSet());
        List<PlannerResult.PlannedBlock> work = result.blocks().stream().filter(b -> maintenance.contains(b.blockId())).toList();
        TreeSet<OffsetDateTime> edges = new TreeSet<>(Comparator.comparing(OffsetDateTime::toInstant));
        work.forEach(b -> { edges.add(b.startAt()); edges.add(b.endAt()); }); OffsetDateTime previous = null;
        for (OffsetDateTime edge : edges) {
            if (previous != null) {
                OffsetDateTime from = previous;
                long count = work.stream().filter(b -> SourcePlanAudit.overlaps(from, edge, b.startAt(), b.endAt())).map(PlannerResult.PlannedBlock::trainId).distinct().count();
                if (count > maximum) add(issues, "D2_MAINTENANCE_CAPACITY", "Одновременно обслуживаются " + count + " составов при лимите " + maximum, "depot", from, edge);
            }
            previous = edge;
        }
        return List.copyOf(issues);
    }

    private static boolean busy(ScenarioSnapshot source, PlannerResult result, UUID train, OffsetDateTime from, OffsetDateTime to) {
        return result.blocks().stream().anyMatch(b -> train.equals(b.trainId()) && SourcePlanAudit.overlaps(from, to, b.startAt(), b.endAt()))
                || source.fixedTrips().stream().anyMatch(t -> train.equals(t.trainId()) && SourcePlanAudit.overlaps(from, to,
                source.horizonStart().plusMinutes(t.startMinute()), source.horizonStart().plusMinutes(t.endMinute())))
                || source.operations().fixedOccupancies().stream().anyMatch(o -> train.equals(o.trainId()) && SourcePlanAudit.overlaps(from, to,
                source.horizonStart().plusMinutes(o.startMinute()), source.horizonStart().plusMinutes(o.endMinute())));
    }
    private static boolean valid(OffsetDateTime a, OffsetDateTime b) { return a != null && b != null && a.isBefore(b); }
    private static boolean blank(String value) { return value == null || value.isBlank(); }
    private static void add(List<Dto.Validation> issues, String code, String message, Object object, OffsetDateTime start, OffsetDateTime end) {
        issues.add(new Dto.Validation(code, "CRITICAL", message, object == null ? null : object.toString(), start == null ? null : start.toString(), end == null ? null : end.toString()));
    }
}
