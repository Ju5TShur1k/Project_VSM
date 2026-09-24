package com.vsm.okno.planning;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** F2 event-sweep explanation of the case's four-train hot-reserve requirement. */
public final class HotReserveCoverage {
    private HotReserveCoverage() {}

    public record Assignment(UUID trainId, int startMinute, int endMinute) {
        public Assignment {
            Objects.requireNonNull(trainId, "trainId");
            if (startMinute < 0 || endMinute <= startMinute) {
                throw new IllegalArgumentException("invalid planned train interval");
            }
        }
    }

    public record Period(int startMinute, int endMinute, List<UUID> readyTrainIds,
                         List<UUID> selectedReserveTrainIds) {
        public Period {
            readyTrainIds = List.copyOf(readyTrainIds);
            selectedReserveTrainIds = List.copyOf(selectedReserveTrainIds);
        }
    }

    public record Assessment(int minimumReady, List<Period> periods) {
        public Assessment {
            periods = List.copyOf(periods);
        }

        public boolean satisfiesRequirement() {
            return minimumReady >= OperationalConstraints.HotReserve.REQUIRED_TRAINS;
        }

        public Period firstShortfall() {
            return periods.stream()
                    .filter(period -> period.readyTrainIds().size()
                            < OperationalConstraints.HotReserve.REQUIRED_TRAINS)
                    .findFirst().orElse(null);
        }
    }

    private record Change(UUID trainId, int delta) {}

    public static Assessment assess(ScenarioSnapshot snapshot, Collection<Assignment> plannedBlocks) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(plannedBlocks, "plannedBlocks");
        OperationalConstraints.HotReserve policy = Objects.requireNonNull(snapshot.operations().hotReserve(),
                "hotReserve");
        int horizon = snapshot.horizonMinutes();
        TreeMap<Integer, List<Change>> changes = new TreeMap<>();
        changes.put(0, new ArrayList<>());
        changes.put(horizon, new ArrayList<>());
        for (ScenarioSnapshot.FixedTrip trip : snapshot.fixedTrips()) {
            add(changes, policy, trip.trainId(), trip.startMinute(), trip.endMinute(), horizon);
        }
        for (OperationalConstraints.FixedOccupancy occupancy : snapshot.operations().fixedOccupancies()) {
            if (occupancy.trainId() != null) {
                add(changes, policy, occupancy.trainId(), occupancy.startMinute(),
                        occupancy.endMinute(), horizon);
            }
        }
        for (Assignment assignment : plannedBlocks) {
            add(changes, policy, assignment.trainId(), assignment.startMinute(),
                    assignment.endMinute(), horizon);
        }

        List<UUID> eligible = policy.eligibleTrainIds().stream()
                .sorted(Comparator.comparing(UUID::toString)).toList();
        Map<UUID, Integer> busyCount = new HashMap<>();
        List<Period> periods = new ArrayList<>();
        int minimumReady = eligible.size();
        for (Map.Entry<Integer, List<Change>> entry : changes.entrySet()) {
            for (Change change : entry.getValue()) {
                busyCount.merge(change.trainId(), change.delta(), Integer::sum);
            }
            Integer next = changes.higherKey(entry.getKey());
            if (next == null) break;
            List<UUID> ready = eligible.stream()
                    .filter(trainId -> busyCount.getOrDefault(trainId, 0) == 0).toList();
            minimumReady = Math.min(minimumReady, ready.size());
            periods.add(new Period(entry.getKey(), next, ready,
                    ready.subList(0, Math.min(OperationalConstraints.HotReserve.REQUIRED_TRAINS,
                            ready.size()))));
        }
        return new Assessment(minimumReady, periods);
    }

    private static void add(TreeMap<Integer, List<Change>> changes, OperationalConstraints.HotReserve policy,
                            UUID trainId, int start, int end, int horizon) {
        if (start < 0 || end <= start || end > horizon) {
            throw new IllegalArgumentException("train occupancy exceeds planning horizon");
        }
        if (!policy.eligibleTrainIds().contains(trainId)) return;
        changes.computeIfAbsent(start, ignored -> new ArrayList<>()).add(new Change(trainId, 1));
        changes.computeIfAbsent(end, ignored -> new ArrayList<>()).add(new Change(trainId, -1));
    }
}
