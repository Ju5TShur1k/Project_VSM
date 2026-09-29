package com.vsm.okno.validation;

import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.ScenarioSnapshot;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Counts for a validated MODEL plan. Service minutes are train-minutes, not fleet readiness. */
public record PlanMetrics(String scope, int scheduledTripCount, int conflictingTripCount,
                          int requiredServiceCount, int placedServiceCount, int missingServiceCount,
                          long trainServiceMinutes, long makespanMinutes, int peakConcurrentService,
                          List<ResourceLoad> resourceLoads) {
    public PlanMetrics { resourceLoads = List.copyOf(resourceLoads); }
    public record ResourceLoad(String resourceId, long busyMinutes, double horizonSharePercent) {}

    public static PlanMetrics calculate(ScenarioSnapshot source, PlannerResult result, ValidationReport report) {
        if (!"PASS".equals(report.status()) || report.requiredServiceCount() == null) return null;
        Map<String, Long> byResource = new TreeMap<>();
        Map<Integer, Integer> edges = new TreeMap<>();
        long total = 0, makespan = 0;
        for (var b : result.blocks()) {
            long duration = Duration.between(b.startAt(), b.endAt()).toMinutes();
            total += duration;
            byResource.merge(b.resourceId(), duration, Long::sum);
            int start = Math.toIntExact(Duration.between(source.horizonStart(), b.startAt()).toMinutes());
            int end = Math.toIntExact(Duration.between(source.horizonStart(), b.endAt()).toMinutes());
            edges.merge(start, 1, Integer::sum);
            edges.merge(end, -1, Integer::sum);
            makespan = Math.max(makespan, end);
        }
        int concurrent = 0, peak = 0;
        for (int delta : edges.values()) { concurrent += delta; peak = Math.max(peak, concurrent); }
        int conflictingTrips = (int) source.fixedTrips().stream().filter(t -> result.blocks().stream()
                .anyMatch(b -> t.trainId().equals(b.trainId())
                        && b.startAt().isBefore(source.horizonStart().plusMinutes(t.endMinute()))
                        && source.horizonStart().plusMinutes(t.startMinute()).isBefore(b.endAt()))).count();
        List<ResourceLoad> loads = new ArrayList<>();
        source.resources().stream().sorted(Comparator.comparing(ScenarioSnapshot.Resource::id)).forEach(r -> {
            long busy = byResource.getOrDefault(r.id(), 0L);
            loads.add(new ResourceLoad(r.id(), busy, busy * 100.0 / source.horizonMinutes()));
        });
        return new PlanMetrics(report.scope(), source.fixedTrips().size(), conflictingTrips,
                report.requiredServiceCount(), result.blocks().size(),
                Math.max(0, report.requiredServiceCount() - result.blocks().size()), total, makespan, peak, loads);
    }
}
