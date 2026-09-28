package com.vsm.okno.validation;

import com.vsm.okno.dto.Dto;
import com.vsm.okno.planning.OperationalConstraints;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.ScenarioSnapshot;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Independent structural audit of E1-E3 intervals. It does not call a solver or its
 * slot-search helpers. D2 must add source-derived mileage, cleaning and release
 * checks before exposing a PlanValidator bean that can permit approval.
 */
public final class IndependentIntervalAudit {
    private IndependentIntervalAudit() {}

    private record Span(int start, int end, String label) {}

    public static List<Dto.Validation> check(ScenarioSnapshot source, PlannerResult result) {
        List<Dto.Validation> findings = new ArrayList<>();
        if (!source.scenarioId().equals(result.scenarioId())
                || !source.snapshotHash().equals(result.snapshotHash())) {
            add(findings, "SNAPSHOT_MISMATCH", "Результат относится к другому сценарию или snapshot");
            return List.copyOf(findings);
        }
        if (result.solverStatus() != PlannerResult.SolverStatus.FEASIBLE
                && result.solverStatus() != PlannerResult.SolverStatus.OPTIMAL) {
            add(findings, "NO_FEASIBLE_RESULT", "Solver не вернул допустимый план");
            return List.copyOf(findings);
        }

        Map<UUID, ScenarioSnapshot.ServiceBlock> required = new HashMap<>();
        for (var block : source.blocks()) required.put(block.id(), block);
        Map<UUID, Span> placedByBlock = new HashMap<>();
        Map<UUID, List<Span>> byTrain = new HashMap<>();
        Map<String, List<Span>> byResource = new HashMap<>();
        for (var trip : source.fixedTrips()) {
            addSpan(byTrain, trip.trainId(), new Span(trip.startMinute(), trip.endMinute(), "рейс " + trip.id()));
        }
        for (var occupancy : source.operations().fixedOccupancies()) {
            Span span = new Span(occupancy.startMinute(), occupancy.endMinute(), "занятость " + occupancy.id());
            if (occupancy.trainId() != null) addSpan(byTrain, occupancy.trainId(), span);
            if (occupancy.resourceId() != null) addSpan(byResource, occupancy.resourceId(), span);
        }

        for (var planned : result.blocks()) {
            var expected = required.get(planned.blockId());
            if (expected == null) {
                add(findings, "UNKNOWN_BLOCK", "Лишняя работа " + planned.blockId());
                continue;
            }
            if (placedByBlock.containsKey(planned.blockId())) {
                add(findings, "DUPLICATE_BLOCK", "Работа повторяется: " + planned.blockId());
                continue;
            }
            if (!expected.trainId().equals(planned.trainId())
                    || !expected.resourceId().equals(planned.resourceId())) {
                add(findings, "BLOCK_ASSIGNMENT_CHANGED", "Изменён поезд или ресурс работы " + planned.blockId());
                continue;
            }
            Integer start = minute(source.horizonStart().toInstant(), planned.startAt().toInstant());
            Integer end = minute(source.horizonStart().toInstant(), planned.endAt().toInstant());
            if (start == null || end == null || start < 0 || end > source.horizonMinutes()
                    || end - start != expected.durationMinutes()
                    || start < expected.earliestStartMinute() || end > expected.latestEndMinute()) {
                add(findings, "BLOCK_WINDOW_OR_DURATION", "Неверный интервал работы " + planned.blockId());
                continue;
            }
            Span span = new Span(start, end, "работа " + planned.blockId());
            placedByBlock.put(planned.blockId(), span);
            addSpan(byTrain, planned.trainId(), span);
            addSpan(byResource, planned.resourceId(), span);
            if (("1.2".equals(source.schemaVersion()) || "1.3".equals(source.schemaVersion())
                    || "1.4".equals(source.schemaVersion()))
                    && source.operations().serviceWindows().stream().noneMatch(window ->
                    window.trainId().equals(planned.trainId())
                            && window.resourceId().equals(planned.resourceId())
                            && window.startMinute() <= start && end <= window.endMinute())) {
                add(findings, "NO_SERVICE_WINDOW", "Нет полного окна присутствия/ресурса для " + planned.blockId());
            }
        }
        for (var block : source.blocks()) {
            Span own = placedByBlock.get(block.id());
            if (own == null) {
                add(findings, "MISSING_BLOCK", "Обязательная работа отсутствует: " + block.id());
                continue;
            }
            for (UUID predecessor : block.predecessorIds()) {
                Span previous = placedByBlock.get(predecessor);
                if (previous == null || previous.end() > own.start()) {
                    add(findings, "PRECEDENCE", "Нарушен порядок работ для " + block.id());
                }
            }
        }
        for (var frozen : source.operations().frozenPlacements()) {
            Span span = placedByBlock.get(frozen.blockId());
            if (span == null || span.start() != frozen.startMinute()) {
                add(findings, "FROZEN_WORK_MOVED", "Закреплённая работа сдвинута: " + frozen.blockId());
            }
        }
        byTrain.forEach((train, spans) -> checkOverlap(findings, spans, "TRAIN_OVERLAP", train.toString()));
        byResource.forEach((resource, spans) -> checkOverlap(findings, spans, "RESOURCE_OVERLAP", resource));
        checkReserve(source, byTrain, findings);
        return List.copyOf(findings);
    }

    private static void checkReserve(ScenarioSnapshot source, Map<UUID, List<Span>> byTrain,
                                     List<Dto.Validation> findings) {
        var reserve = source.operations().hotReserve();
        if (reserve == null) return;
        TreeSet<Integer> boundaries = new TreeSet<>(Set.of(0, source.horizonMinutes()));
        reserve.eligibleTrainIds().forEach(id -> byTrain.getOrDefault(id, List.of()).forEach(span -> {
            boundaries.add(span.start());
            boundaries.add(span.end());
        }));
        Integer previous = null;
        for (Integer boundary : boundaries) {
            if (previous != null && previous < boundary) {
                int at = previous;
                long ready = reserve.eligibleTrainIds().stream()
                        .filter(id -> byTrain.getOrDefault(id, List.of()).stream()
                                .noneMatch(span -> span.start() <= at && at < span.end()))
                        .count();
                if (ready < OperationalConstraints.HotReserve.REQUIRED_TRAINS) {
                    add(findings, "HOT_RESERVE_SHORTAGE", "В интервале " + previous + "–" + boundary
                            + " мин доступно для резерва только " + ready + " состава(ов)");
                    break;
                }
            }
            previous = boundary;
        }
    }

    private static <K> void addSpan(Map<K, List<Span>> map, K key, Span span) {
        map.computeIfAbsent(key, ignored -> new ArrayList<>()).add(span);
    }

    private static void checkOverlap(List<Dto.Validation> findings, List<Span> spans, String code, String owner) {
        spans.sort(Comparator.comparingInt(Span::start).thenComparingInt(Span::end));
        for (int i = 1; i < spans.size(); i++) {
            if (spans.get(i - 1).end() > spans.get(i).start()) {
                add(findings, code, owner + ": " + spans.get(i - 1).label() + " пересекается с "
                        + spans.get(i).label());
            }
        }
    }

    private static Integer minute(Instant origin, Instant event) {
        Duration elapsed = Duration.between(origin, event);
        if (elapsed.getNano() != 0 || elapsed.getSeconds() % 60 != 0) return null;
        try {
            return Math.toIntExact(elapsed.toMinutes());
        } catch (ArithmeticException ignored) {
            return null;
        }
    }

    private static void add(List<Dto.Validation> findings, String code, String message) {
        findings.add(new Dto.Validation(code, "CRITICAL", message));
    }
}
