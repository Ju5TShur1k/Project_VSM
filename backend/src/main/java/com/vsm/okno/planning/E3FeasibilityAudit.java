package com.vsm.okno.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Necessary-condition check before a potentially expensive full-fleet solve.
 * A missing contiguous train/resource window proves the current fixed-turn
 * scenario infeasible; passing this check does not prove feasibility.
 */
public final class E3FeasibilityAudit {
    private E3FeasibilityAudit() {}

    private record Span(int start, int end) {}

    public static List<PlannerResult.Diagnostic> noContiguousWindows(
            ScenarioSnapshot snapshot, List<MileageObligationGenerator.Obligation> obligations) {
        Map<UUID, MileageObligationGenerator.Obligation> byBlock = obligations.stream()
                .collect(Collectors.toMap(MileageObligationGenerator.Obligation::blockId, Function.identity()));
        Map<UUID, String> trainNames = snapshot.trains().stream()
                .collect(Collectors.toMap(ScenarioSnapshot.Train::id, ScenarioSnapshot.Train::externalId));
        List<PlannerResult.Diagnostic> blockers = new ArrayList<>();
        for (var block : snapshot.blocks()) {
            if (!missingWindow(snapshot, block)) continue;
            var obligation = byBlock.get(block.id());
            String cycle = obligation == null ? block.kind().name() : obligation.cycleCode();
            blockers.add(new PlannerResult.Diagnostic("E3_NO_CONTIGUOUS_SERVICE_WINDOW",
                    "Состав " + trainNames.getOrDefault(block.trainId(), block.trainId().toString())
                            + ": " + cycle + " требует " + block.durationMinutes()
                            + " мин непрерывного окна; закреплённые рейсы, занятость и окна ресурсов"
                            + " такого интервала не оставляют. Работа " + block.id()));
        }
        return List.copyOf(blockers);
    }

    /** Block identities for candidate search; this is only a necessary-condition diagnostic. */
    public static List<UUID> blockedBlockIds(ScenarioSnapshot snapshot) {
        return snapshot.blocks().stream().filter(block -> missingWindow(snapshot, block))
                .map(ScenarioSnapshot.ServiceBlock::id).toList();
    }

    private static boolean missingWindow(ScenarioSnapshot snapshot, ScenarioSnapshot.ServiceBlock block) {
        return snapshot.operations().serviceWindows().stream()
                .filter(window -> window.trainId().equals(block.trainId())
                        && block.allowedResourceIds().contains(window.resourceId()))
                .noneMatch(window -> hasFreeSegment(snapshot, block, window));
    }

    private static boolean hasFreeSegment(ScenarioSnapshot source, ScenarioSnapshot.ServiceBlock block,
                                          OperationalConstraints.ServiceWindow window) {
        int from = Math.max(window.startMinute(), block.earliestStartMinute());
        int to = Math.min(window.endMinute(), block.latestEndMinute());
        if (to - from < block.durationMinutes()) return false;
        List<Span> busy = new ArrayList<>();
        for (var trip : source.fixedTrips()) if (trip.trainId().equals(block.trainId())) {
            busy.add(new Span(trip.startMinute(), trip.endMinute()));
        }
        for (var occupancy : source.operations().fixedOccupancies()) {
            if (block.trainId().equals(occupancy.trainId())
                    || window.resourceId().equals(occupancy.resourceId())) {
                busy.add(new Span(occupancy.startMinute(), occupancy.endMinute()));
            }
        }
        busy.sort(Comparator.comparingInt(Span::start));
        int cursor = from;
        for (Span span : busy) {
            if (span.end() <= cursor || span.start() >= to) continue;
            if (span.start() - cursor >= block.durationMinutes()) return true;
            cursor = Math.max(cursor, span.end());
        }
        return to - cursor >= block.durationMinutes();
    }
}
