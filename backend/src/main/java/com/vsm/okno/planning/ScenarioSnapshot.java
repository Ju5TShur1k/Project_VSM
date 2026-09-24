package com.vsm.okno.planning;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable prepared planning input. Schema 1.1 adds fixed trips; minutes are relative to horizonStart. */
public record ScenarioSnapshot(
        String schemaVersion,
        UUID scenarioId,
        String snapshotHash,
        String provenance,
        OffsetDateTime horizonStart,
        OffsetDateTime horizonEnd,
        List<Train> trains,
        List<Resource> resources,
        List<ServiceBlock> blocks,
        List<FixedTrip> fixedTrips
) {
    public ScenarioSnapshot(String schemaVersion, UUID scenarioId, String snapshotHash, String provenance,
                            OffsetDateTime horizonStart, OffsetDateTime horizonEnd, List<Train> trains,
                            List<Resource> resources, List<ServiceBlock> blocks) {
        this(schemaVersion, scenarioId, snapshotHash, provenance, horizonStart, horizonEnd,
                trains, resources, blocks, List.of());
    }

    public ScenarioSnapshot {
        if (!"1.0".equals(schemaVersion) && !"1.1".equals(schemaVersion)) {
            throw new IllegalArgumentException("unsupported snapshot schemaVersion");
        }
        Objects.requireNonNull(scenarioId, "scenarioId");
        requireText(snapshotHash, "snapshotHash");
        requireText(provenance, "provenance");
        Objects.requireNonNull(horizonStart, "horizonStart");
        Objects.requireNonNull(horizonEnd, "horizonEnd");
        trains = List.copyOf(Objects.requireNonNull(trains, "trains"));
        resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));
        fixedTrips = List.copyOf(Objects.requireNonNull(fixedTrips, "fixedTrips"));
        if ("1.0".equals(schemaVersion) && !fixedTrips.isEmpty()) {
            throw new IllegalArgumentException("fixed trips require snapshot schemaVersion 1.1");
        }

        Duration horizon = Duration.between(horizonStart, horizonEnd);
        long seconds = horizon.getSeconds();
        if (seconds <= 0 || horizon.getNano() != 0 || seconds % 60 != 0
                || seconds / 60 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("horizon must be a positive whole number of minutes");
        }
        int horizonMinutes = (int) (seconds / 60);

        Set<UUID> trainIds = new HashSet<>();
        for (Train train : trains) {
            if (!trainIds.add(train.id())) throw new IllegalArgumentException("duplicate train id: " + train.id());
        }
        Set<String> resourceIds = new HashSet<>();
        for (Resource resource : resources) {
            if (!resourceIds.add(resource.id())) throw new IllegalArgumentException("duplicate resource id: " + resource.id());
        }
        Set<UUID> blockIds = new HashSet<>();
        for (ServiceBlock block : blocks) {
            if (!blockIds.add(block.id())) throw new IllegalArgumentException("duplicate block id: " + block.id());
            if (!trainIds.contains(block.trainId())) throw new IllegalArgumentException("unknown train: " + block.trainId());
            if (!resourceIds.contains(block.resourceId())) throw new IllegalArgumentException("unknown resource: " + block.resourceId());
            if (block.latestEndMinute() > horizonMinutes) {
                throw new IllegalArgumentException("block window exceeds horizon: " + block.id());
            }
        }
        Set<UUID> tripIds = new HashSet<>();
        for (FixedTrip trip : fixedTrips) {
            if (!tripIds.add(trip.id())) throw new IllegalArgumentException("duplicate fixed trip id: " + trip.id());
            if (!trainIds.contains(trip.trainId())) throw new IllegalArgumentException("unknown trip train: " + trip.trainId());
            if (trip.endMinute() > horizonMinutes) throw new IllegalArgumentException("trip exceeds horizon: " + trip.id());
        }
        for (int i = 0; i < fixedTrips.size(); i++) {
            FixedTrip a = fixedTrips.get(i);
            for (int j = i + 1; j < fixedTrips.size(); j++) {
                FixedTrip b = fixedTrips.get(j);
                if (a.trainId().equals(b.trainId()) && a.startMinute() < b.endMinute()
                        && b.startMinute() < a.endMinute()) {
                    throw new IllegalArgumentException("overlapping fixed trips for train: " + a.trainId());
                }
            }
        }
        if (blocks.isEmpty()) throw new IllegalArgumentException("E1 requires at least one block");
        for (ServiceBlock block : blocks) {
            for (UUID predecessorId : block.predecessorIds()) {
                if (!blockIds.contains(predecessorId)) {
                    throw new IllegalArgumentException("unknown predecessor: " + predecessorId);
                }
                if (predecessorId.equals(block.id())) {
                    throw new IllegalArgumentException("block cannot precede itself: " + block.id());
                }
            }
        }
    }

    public int horizonMinutes() {
        return Math.toIntExact(Duration.between(horizonStart, horizonEnd).toMinutes());
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    public record Train(UUID id, String externalId) {
        public Train {
            Objects.requireNonNull(id, "train id");
            requireText(externalId, "externalId");
        }
    }

    /** E1 supports only exclusive resources. Pools and calendars are added with the domain snapshot. */
    public record Resource(String id) {
        public Resource {
            requireText(id, "resource id");
        }
    }

    /** Fixed, already checked turnover. Mileage is credited only at arrival in E2. */
    public record FixedTrip(UUID id, UUID trainId, String label, int startMinute, int endMinute,
                            long distanceKm, String source) {
        public FixedTrip {
            Objects.requireNonNull(id, "trip id");
            Objects.requireNonNull(trainId, "trip trainId");
            requireText(label, "trip label");
            requireText(source, "trip source");
            if (startMinute < 0 || endMinute <= startMinute || distanceKm <= 0) {
                throw new IllegalArgumentException("invalid fixed trip: " + id);
            }
        }
    }

    /** A required, indivisible block with one train, one resource and a finish deadline. */
    public record ServiceBlock(
            UUID id,
            UUID trainId,
            String resourceId,
            int durationMinutes,
            int earliestStartMinute,
            int latestEndMinute,
            List<UUID> predecessorIds
    ) {
        public ServiceBlock {
            Objects.requireNonNull(id, "block id");
            Objects.requireNonNull(trainId, "trainId");
            requireText(resourceId, "resourceId");
            predecessorIds = List.copyOf(Objects.requireNonNull(predecessorIds, "predecessorIds"));
            if (durationMinutes <= 0 || earliestStartMinute < 0 || latestEndMinute < 0
                    || (long) earliestStartMinute + durationMinutes > latestEndMinute) {
                throw new IllegalArgumentException("invalid block duration/window: " + id);
            }
        }
    }
}
