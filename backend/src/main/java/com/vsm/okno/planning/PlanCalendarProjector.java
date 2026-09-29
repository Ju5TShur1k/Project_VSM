package com.vsm.okno.planning;

import com.vsm.okno.dto.Dto;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Read model from the exact immutable snapshot and solver result used by a plan. */
public final class PlanCalendarProjector {
    private PlanCalendarProjector() {}

    public static Dto.PlanCalendar project(ScenarioSnapshot source, PlannerResult result,
                                           Map<UUID, MileageObligationGenerator.Obligation> obligations,
                                           String validationStatus) {
        if (!source.scenarioId().equals(result.scenarioId())
                || !source.snapshotHash().equals(result.snapshotHash())) {
            throw new IllegalArgumentException("calendar source/result mismatch");
        }
        var events = new ArrayList<Dto.CalendarEvent>();
        source.fixedTrips().forEach(trip -> events.add(new Dto.CalendarEvent(
                trip.id(), "TRIP", trip.trainId(), null,
                "Рейс " + trip.label() + " · " + trip.distanceKm() + " км",
                source.horizonStart().plusMinutes(trip.startMinute()).toString(),
                source.horizonStart().plusMinutes(trip.endMinute()).toString(),
                trip.source(), "Время рейса задано в сохранённых исходных данных; планировщик его не сдвигает.",
                null, null, null, null, null)));
        var blocks = source.blocks().stream().collect(Collectors.toMap(
                ScenarioSnapshot.ServiceBlock::id, Function.identity()));
        result.blocks().forEach(placed -> {
            var block = blocks.get(placed.blockId());
            if (block == null) throw new IllegalArgumentException("unknown planned block");
            var obligation = obligations.get(placed.blockId());
            var covers = obligation == null ? null : obligation.covers().stream()
                    .map(c -> c.code() + "@" + c.nominalKm()).toList();
            events.add(new Dto.CalendarEvent(placed.blockId(), "SERVICE", placed.trainId(),
                    placed.resourceId(), obligation == null
                            ? (block.kind() == ScenarioSnapshot.ServiceBlock.Kind.CLEANING ? "Уборка · "
                            : "Работа · ") + block.durationMinutes() + " мин"
                            : obligation.cycleCode() + " · " + block.durationMinutes() + " мин",
                    placed.startAt().toString(), placed.endAt().toString(),
                    obligation == null ? source.provenance() : obligation.ruleSource(),
                    obligation == null ? (block.kind() == ScenarioSnapshot.ServiceBlock.Kind.CLEANING
                            ? "Уборка после четвёртого рейса и до следующего отправления; мощность бригады в D1 не задана."
                            : "Слот найден планировщиком в допустимом окне.")
                            : "Работа размещена после достижения пробега " + obligation.releaseOdometerKm()
                            + " км, до ограничения " + obligation.dueOdometerKm()
                            + " км, с учётом рейсов и доступности пути.",
                    obligation == null ? null : obligation.cycleCode(), covers,
                    obligation == null ? null : obligation.releaseOdometerKm(),
                    obligation == null ? null : obligation.dueOdometerKm(),
                    obligation == null ? null : obligation.dueAt().toString()));
        });
        return new Dto.PlanCalendar(source.scenarioId(), source.snapshotHash(), source.provenance(),
                result.policy().name(), result.solverStatus().name(), validationStatus,
                "PASS".equals(validationStatus), source.horizonStart().toString(),
                source.horizonEnd().toString(),
                source.trains().stream().map(t -> new Dto.CalendarLane(t.id().toString(), t.externalId())).toList(),
                source.resources().stream().map(r -> new Dto.CalendarLane(r.id(), r.id())).toList(), events);
    }
}
