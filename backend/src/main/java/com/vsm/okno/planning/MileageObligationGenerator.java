package com.vsm.okno.planning;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** E2 projection from fixed trips and explicitly credited absolute-grid cycle milestones. */
public final class MileageObligationGenerator {
    public record CycleRule(String code, long intervalKm, int toleranceBasisPoints, int durationMinutes,
                            int rank, String resourceId, String source) {
        public CycleRule {
            requireText(code, "cycle code");
            requireText(resourceId, "resourceId");
            requireText(source, "rule source");
            if (intervalKm <= 0 || toleranceBasisPoints < 0 || toleranceBasisPoints >= 10_000
                    || durationMinutes <= 0 || rank < 0) {
                throw new IllegalArgumentException("invalid cycle rule: " + code);
            }
        }
    }

    public record TrainState(ScenarioSnapshot.Train train, long initialOdometerKm,
                             Map<String, Long> lastCreditedNominalKm) {
        public TrainState {
            Objects.requireNonNull(train, "train");
            lastCreditedNominalKm = Map.copyOf(Objects.requireNonNull(lastCreditedNominalKm, "lastCreditedNominalKm"));
            if (initialOdometerKm < 0) throw new IllegalArgumentException("negative initial odometer");
        }
    }

    public record Input(UUID scenarioId, String snapshotHash, String provenance,
                        OffsetDateTime horizonStart, OffsetDateTime horizonEnd,
                        List<TrainState> trainStates, List<ScenarioSnapshot.Resource> resources,
                        List<ScenarioSnapshot.FixedTrip> fixedTrips, List<CycleRule> rules) {
        public Input {
            Objects.requireNonNull(scenarioId, "scenarioId");
            requireText(snapshotHash, "snapshotHash");
            requireText(provenance, "provenance");
            Objects.requireNonNull(horizonStart, "horizonStart");
            Objects.requireNonNull(horizonEnd, "horizonEnd");
            trainStates = List.copyOf(Objects.requireNonNull(trainStates, "trainStates"));
            resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
            fixedTrips = List.copyOf(Objects.requireNonNull(fixedTrips, "fixedTrips"));
            rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
            if (trainStates.isEmpty() || rules.isEmpty()) throw new IllegalArgumentException("trains and rules required");
        }
    }

    public record CoveredCycle(String code, long nominalKm) {}

    /** The selected senior cycle is indivisible; covered junior obligations remain visible. */
    public record Obligation(UUID blockId, UUID trainId, String cycleCode, long nominalKm,
                             long releaseOdometerKm, long dueOdometerKm,
                             OffsetDateTime releaseAt, OffsetDateTime dueAt,
                             boolean completionRequiredBeforeNextTrip, String ruleSource,
                             List<CoveredCycle> covers) {
        public Obligation {
            covers = List.copyOf(covers);
        }
    }

    public record Projection(ScenarioSnapshot snapshot, List<Obligation> obligations) {
        public Projection {
            obligations = List.copyOf(obligations);
        }
    }

    private record DueCycle(CycleRule rule, long nominalKm, long releaseKm, long dueKm,
                            int releaseMinute, int dueMinute) {}

    public Projection generate(Input input) {
        Objects.requireNonNull(input, "input");
        Set<String> codes = new HashSet<>();
        Set<String> resourceIds = new HashSet<>();
        input.resources().forEach(r -> resourceIds.add(r.id()));
        for (CycleRule rule : input.rules()) {
            if (!codes.add(rule.code())) {
                throw new IllegalArgumentException("cycle codes must be unique");
            }
            if (!resourceIds.contains(rule.resourceId())) {
                throw new IllegalArgumentException("unknown rule resource: " + rule.resourceId());
            }
        }

        List<ScenarioSnapshot.Train> trains = input.trainStates().stream().map(TrainState::train).toList();
        // Validate trip references, overlap and horizon before deriving any mileage windows.
        int horizonMinutes = Math.toIntExact(java.time.Duration.between(input.horizonStart(), input.horizonEnd()).toMinutes());
        if (!input.horizonStart().plusMinutes(horizonMinutes).isEqual(input.horizonEnd()) || horizonMinutes <= 0) {
            throw new IllegalArgumentException("horizon must contain whole minutes");
        }
        Set<UUID> trainIds = new HashSet<>();
        for (ScenarioSnapshot.Train train : trains) {
            if (!trainIds.add(train.id())) throw new IllegalArgumentException("duplicate train: " + train.id());
        }
        Set<UUID> tripIds = new HashSet<>();
        Map<UUID, List<ScenarioSnapshot.FixedTrip>> tripsByTrain = new HashMap<>();
        for (ScenarioSnapshot.FixedTrip trip : input.fixedTrips()) {
            if (!tripIds.add(trip.id()) || !trainIds.contains(trip.trainId()) || trip.endMinute() > horizonMinutes) {
                throw new IllegalArgumentException("invalid trip id/train/horizon: " + trip.id());
            }
            tripsByTrain.computeIfAbsent(trip.trainId(), ignored -> new ArrayList<>()).add(trip);
        }
        for (List<ScenarioSnapshot.FixedTrip> trips : tripsByTrain.values()) {
            trips.sort(Comparator.comparingInt(ScenarioSnapshot.FixedTrip::startMinute));
            for (int i = 1; i < trips.size(); i++) {
                if (trips.get(i).startMinute() < trips.get(i - 1).endMinute()) {
                    throw new IllegalArgumentException("overlapping fixed trips for train");
                }
            }
        }

        List<ScenarioSnapshot.ServiceBlock> blocks = new ArrayList<>();
        List<Obligation> obligations = new ArrayList<>();
        for (TrainState state : input.trainStates()) {
            List<ScenarioSnapshot.FixedTrip> trips = tripsByTrain.getOrDefault(state.train().id(), List.of());
            long finalOdometer = state.initialOdometerKm();
            for (ScenarioSnapshot.FixedTrip trip : trips) finalOdometer = Math.addExact(finalOdometer, trip.distanceKm());
            Map<Long, List<DueCycle>> byNominal = new TreeMap<>();
            for (CycleRule rule : input.rules()) {
                Long credited = state.lastCreditedNominalKm().get(rule.code());
                if (credited == null || credited < 0 || credited > state.initialOdometerKm()
                        || credited % rule.intervalKm() != 0) {
                    throw new IllegalArgumentException("missing or invalid credited milestone for " + rule.code());
                }
                for (long nominal = Math.addExact(credited, rule.intervalKm()); nominal <= finalOdometer;
                     nominal = Math.addExact(nominal, rule.intervalKm())) {
                    long releaseKm = ceilDiv(Math.multiplyExact(nominal, 10_000L - rule.toleranceBasisPoints()), 10_000);
                    long dueKm = Math.multiplyExact(nominal, 10_000L + rule.toleranceBasisPoints()) / 10_000;
                    if (state.initialOdometerKm() > dueKm) {
                        throw new IllegalArgumentException("already overdue at snapshot: " + state.train().externalId()
                                + " " + rule.code() + "@" + nominal);
                    }
                    int releaseMinute = firstArrivalAtOrAbove(trips, state.initialOdometerKm(), releaseKm);
                    int dueMinute = firstDepartureExceeding(trips, state.initialOdometerKm(), dueKm, horizonMinutes);
                    byNominal.computeIfAbsent(nominal, ignored -> new ArrayList<>())
                            .add(new DueCycle(rule, nominal, releaseKm, dueKm, releaseMinute, dueMinute));
                }
            }

            UUID previousBlock = null;
            for (Map.Entry<Long, List<DueCycle>> entry : byNominal.entrySet()) {
                List<DueCycle> group = entry.getValue();
                group.sort(Comparator.comparingInt((DueCycle c) -> c.rule().rank()).reversed());
                if (group.size() > 1 && group.get(0).rule().rank() == group.get(1).rule().rank()) {
                    throw new IllegalArgumentException("ambiguous senior cycle at " + entry.getKey());
                }
                DueCycle senior = group.getFirst();
                int releaseMinute = group.stream().mapToInt(DueCycle::releaseMinute).max().orElseThrow();
                int dueMinute = group.stream().mapToInt(DueCycle::dueMinute).min().orElseThrow();
                long releaseKm = group.stream().mapToLong(DueCycle::releaseKm).max().orElseThrow();
                long dueKm = group.stream().mapToLong(DueCycle::dueKm).min().orElseThrow();
                UUID blockId = UUID.nameUUIDFromBytes((input.scenarioId() + ":" + state.train().id() + ":"
                        + entry.getKey() + ":" + senior.rule().code()).getBytes(StandardCharsets.UTF_8));
                if ((long) releaseMinute + senior.rule().durationMinutes() > dueMinute) {
                    throw new IllegalArgumentException("no service window for " + senior.rule().code() + "@"
                            + entry.getKey() + " on " + state.train().externalId());
                }
                blocks.add(new ScenarioSnapshot.ServiceBlock(blockId, state.train().id(),
                        senior.rule().resourceId(), senior.rule().durationMinutes(), releaseMinute, dueMinute,
                        previousBlock == null ? List.of() : List.of(previousBlock)));
                obligations.add(new Obligation(blockId, state.train().id(), senior.rule().code(), entry.getKey(),
                        releaseKm, dueKm, input.horizonStart().plusMinutes(releaseMinute),
                        input.horizonStart().plusMinutes(dueMinute), dueMinute < horizonMinutes,
                        senior.rule().source(), group.stream().map(c -> new CoveredCycle(c.rule().code(), c.nominalKm())).toList()));
                previousBlock = blockId;
            }
        }
        if (blocks.isEmpty()) throw new IllegalArgumentException("no nominal milestone reached in E2 horizon");
        ScenarioSnapshot snapshot = new ScenarioSnapshot("1.1", input.scenarioId(), input.snapshotHash(),
                input.provenance(), input.horizonStart(), input.horizonEnd(), trains, input.resources(),
                blocks, input.fixedTrips());
        return new Projection(snapshot, obligations);
    }

    private static int firstArrivalAtOrAbove(List<ScenarioSnapshot.FixedTrip> trips, long initialKm, long thresholdKm) {
        if (initialKm >= thresholdKm) return 0;
        long odometer = initialKm;
        for (ScenarioSnapshot.FixedTrip trip : trips) {
            odometer = Math.addExact(odometer, trip.distanceKm());
            if (odometer >= thresholdKm) return trip.endMinute();
        }
        throw new IllegalArgumentException("release mileage not reached by fixed trips");
    }

    private static int firstDepartureExceeding(List<ScenarioSnapshot.FixedTrip> trips, long initialKm,
                                               long limitKm, int horizonMinutes) {
        long odometer = initialKm;
        for (ScenarioSnapshot.FixedTrip trip : trips) {
            if (Math.addExact(odometer, trip.distanceKm()) > limitKm) return trip.startMinute();
            odometer += trip.distanceKm();
        }
        return horizonMinutes;
    }

    private static long ceilDiv(long value, long divisor) {
        return value / divisor + (value % divisor == 0 ? 0 : 1);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
