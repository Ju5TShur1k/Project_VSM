package com.vsm.okno.planning;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** E3 fixed-turn projection: an accepted cleaning must fit after trip four and before trip five. */
public final class CleaningObligationGenerator {
    public record Rule(int durationMinutes, String resourceId, String source) {
        public Rule {
            if (durationMinutes <= 0) throw new IllegalArgumentException("cleaning duration must be positive");
            if (resourceId == null || resourceId.isBlank()) throw new IllegalArgumentException("cleaning resource required");
            if (source == null || source.isBlank()) throw new IllegalArgumentException("cleaning rule source required");
        }
    }

    public record CleaningObligation(UUID blockId, UUID trainId, UUID fourthTripId, UUID nextTripId,
                                     String ruleSource) {}

    /** Four trips are complete but no fifth trip lies inside this horizon. */
    public record PendingCleaning(UUID trainId, UUID fourthTripId) {}

    public record Projection(ScenarioSnapshot snapshot, List<CleaningObligation> obligations,
                             List<PendingCleaning> pendingAtHorizon) {
        public Projection {
            obligations = List.copyOf(obligations);
            pendingAtHorizon = List.copyOf(pendingAtHorizon);
        }
    }

    public Projection generate(ScenarioSnapshot base, Map<UUID, Integer> completedTripsSinceCleaning, Rule rule) {
        Objects.requireNonNull(base, "base");
        Objects.requireNonNull(rule, "rule");
        if (!"1.2".equals(base.schemaVersion())) throw new IllegalArgumentException("cleaning needs E3 snapshot 1.2");
        Map<UUID, Integer> counters = Map.copyOf(Objects.requireNonNull(completedTripsSinceCleaning,
                "completedTripsSinceCleaning"));
        if (base.resources().stream().noneMatch(resource -> resource.id().equals(rule.resourceId()))) {
            throw new IllegalArgumentException("unknown cleaning resource");
        }
        Map<UUID, List<ScenarioSnapshot.FixedTrip>> tripsByTrain = new TreeMap<>();
        for (ScenarioSnapshot.FixedTrip trip : base.fixedTrips()) {
            tripsByTrain.computeIfAbsent(trip.trainId(), ignored -> new ArrayList<>()).add(trip);
        }
        List<ScenarioSnapshot.ServiceBlock> blocks = new ArrayList<>(base.blocks());
        List<CleaningObligation> obligations = new ArrayList<>();
        List<PendingCleaning> pending = new ArrayList<>();
        for (Map.Entry<UUID, List<ScenarioSnapshot.FixedTrip>> entry : tripsByTrain.entrySet()) {
            UUID trainId = entry.getKey();
            Integer initial = counters.get(trainId);
            if (initial == null || initial < 0 || initial > 3) {
                throw new IllegalArgumentException("initial completed-trip counter 0..3 required for " + trainId);
            }
            List<ScenarioSnapshot.FixedTrip> trips = entry.getValue();
            trips.sort(Comparator.comparingInt(ScenarioSnapshot.FixedTrip::startMinute));
            int completed = initial;
            for (int index = 0; index < trips.size(); index++) {
                completed++;
                if (completed != 4) continue;
                ScenarioSnapshot.FixedTrip fourth = trips.get(index);
                if (index + 1 == trips.size()) {
                    pending.add(new PendingCleaning(trainId, fourth.id()));
                    break;
                }
                ScenarioSnapshot.FixedTrip next = trips.get(index + 1);
                if ((long) fourth.endMinute() + rule.durationMinutes() > next.startMinute()) {
                    throw new IllegalArgumentException("CLEANING_WINDOW_TOO_SHORT: " + trainId
                            + " between " + fourth.id() + " and " + next.id());
                }
                UUID blockId = UUID.nameUUIDFromBytes((base.scenarioId() + ":clean:"
                        + trainId + ":" + fourth.id()).getBytes(StandardCharsets.UTF_8));
                blocks.add(new ScenarioSnapshot.ServiceBlock(blockId, trainId, rule.resourceId(),
                        rule.durationMinutes(), fourth.endMinute(), next.startMinute(), List.of()));
                obligations.add(new CleaningObligation(blockId, trainId, fourth.id(), next.id(), rule.source()));
                completed = 0;
            }
        }
        ScenarioSnapshot projected = new ScenarioSnapshot("1.2", base.scenarioId(), base.snapshotHash(),
                base.provenance(), base.horizonStart(), base.horizonEnd(), base.trains(), base.resources(),
                blocks, base.fixedTrips(), base.operations());
        return new Projection(projected, obligations, pending);
    }
}
