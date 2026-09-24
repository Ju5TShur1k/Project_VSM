package com.vsm.okno.planning;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Prepared E3 operational facts. Location windows must come from checked turns and transfers. */
public record OperationalConstraints(
        Set<UUID> protectedReserveTrainIds,
        List<FixedOccupancy> fixedOccupancies,
        List<ServiceWindow> serviceWindows,
        List<FrozenPlacement> frozenPlacements
) {
    public OperationalConstraints {
        protectedReserveTrainIds = Set.copyOf(Objects.requireNonNull(protectedReserveTrainIds, "protectedReserveTrainIds"));
        fixedOccupancies = List.copyOf(Objects.requireNonNull(fixedOccupancies, "fixedOccupancies"));
        serviceWindows = List.copyOf(Objects.requireNonNull(serviceWindows, "serviceWindows"));
        frozenPlacements = List.copyOf(Objects.requireNonNull(frozenPlacements, "frozenPlacements"));
    }

    public static OperationalConstraints none() {
        return new OperationalConstraints(Set.of(), List.of(), List.of(), List.of());
    }

    public boolean isEmpty() {
        return protectedReserveTrainIds.isEmpty() && fixedOccupancies.isEmpty()
                && serviceWindows.isEmpty() && frozenPlacements.isEmpty();
    }

    /** A confirmed occupied interval for a train, an exclusive resource, or both. */
    public record FixedOccupancy(UUID id, UUID trainId, String resourceId, int startMinute, int endMinute,
                                 Kind kind, String source) {
        public FixedOccupancy {
            Objects.requireNonNull(id, "occupancy id");
            if (trainId == null && resourceId == null) throw new IllegalArgumentException("occupancy needs train or resource");
            if (resourceId != null && resourceId.isBlank()) throw new IllegalArgumentException("blank resourceId");
            Objects.requireNonNull(kind, "kind");
            requireText(source, "occupancy source");
            requireInterval(startMinute, endMinute);
            if ((kind == Kind.TRANSFER || kind == Kind.AWAITING_RELEASE) && trainId == null) {
                throw new IllegalArgumentException(kind + " requires a train");
            }
            if (kind == Kind.RESOURCE_OUTAGE && resourceId == null) {
                throw new IllegalArgumentException("RESOURCE_OUTAGE requires a resource");
            }
        }
    }

    public enum Kind { TRANSFER, RESOURCE_OUTAGE, AWAITING_RELEASE, OTHER_COMMITMENT }

    /** A full service block may lie inside this checked train/resource location window. */
    public record ServiceWindow(UUID trainId, String resourceId, int startMinute, int endMinute, String source) {
        public ServiceWindow {
            Objects.requireNonNull(trainId, "window trainId");
            requireText(resourceId, "window resourceId");
            requireText(source, "window source");
            requireInterval(startMinute, endMinute);
        }
    }

    /** Start of a previously approved or already started service block, fixed across replans. */
    public record FrozenPlacement(UUID blockId, int startMinute) {
        public FrozenPlacement {
            Objects.requireNonNull(blockId, "frozen blockId");
            if (startMinute < 0) throw new IllegalArgumentException("negative frozen start");
        }
    }

    private static void requireInterval(int startMinute, int endMinute) {
        if (startMinute < 0 || endMinute <= startMinute) throw new IllegalArgumentException("invalid operational interval");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }
}
