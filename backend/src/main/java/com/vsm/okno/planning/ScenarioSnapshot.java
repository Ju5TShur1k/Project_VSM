package com.vsm.okno.planning;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable, already prepared planning input. All minute offsets are relative to horizonStart. */
public record ScenarioSnapshot(
        String schemaVersion,
        UUID scenarioId,
        String snapshotHash,
        String provenance,
        OffsetDateTime horizonStart,
        OffsetDateTime horizonEnd,
        List<Train> trains,
        List<Resource> resources,
        List<ServiceBlock> blocks
) {
    public ScenarioSnapshot {
        if (!"1.0".equals(schemaVersion)) throw new IllegalArgumentException("unsupported snapshot schemaVersion");
        Objects.requireNonNull(scenarioId, "scenarioId");
        requireText(snapshotHash, "snapshotHash");
        requireText(provenance, "provenance");
        Objects.requireNonNull(horizonStart, "horizonStart");
        Objects.requireNonNull(horizonEnd, "horizonEnd");
        trains = List.copyOf(Objects.requireNonNull(trains, "trains"));
        resources = List.copyOf(Objects.requireNonNull(resources, "resources"));
        blocks = List.copyOf(Objects.requireNonNull(blocks, "blocks"));

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
