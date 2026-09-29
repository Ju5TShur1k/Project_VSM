package com.vsm.okno.planning;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.validation.E3CleaningCoverageAssessment;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Adds every missing fourth-to-fifth-trip cleaning to the candidate CP-SAT model. */
public final class E3CleaningBlockProjector {
    public static final String LOGICAL_RESOURCE_PREFIX = "E3-TRAIN-ONLY-CLEANING:";
    public record Projection(ScenarioSnapshot snapshot, List<UUID> cleaningBlockIds,
                             int sourceDurationMinutes) {
        public Projection { cleaningBlockIds = List.copyOf(cleaningBlockIds); }
    }
    public static final class Blocked extends IllegalArgumentException {
        private final String code;
        public Blocked(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }

    private final ObjectMapper json = new ObjectMapper();

    public Projection add(SourceSnapshot saved, ScenarioSnapshot candidate,
                          E3CleaningCoverageAssessment.Report cleaning) {
        if (!saved.scenarioId().equals(candidate.scenarioId())
                || !saved.snapshotHash().equals(candidate.snapshotHash())
                || !saved.snapshotHash().equals(cleaning.snapshotHash()))
            throw new IllegalArgumentException("cleaning/source candidate mismatch");
        if (cleaning.missing().isEmpty()) return new Projection(candidate, List.of(), 0);
        JsonNode root = json.readTree(saved.canonicalPayload());
        Integer duration = null;
        for (JsonNode row : root.path("trainOccupancy")) {
            if (!"CLEANING".equals(row.path("kind").asText())) continue;
            if (!Set.of("SYNTHETIC", "CONFIRMED").contains(row.path("confirmation_status").asText()))
                throw new Blocked("E3_CLEANING_RULE_UNCONFIRMED", "Исходная длительность уборки не подтверждена");
            OffsetDateTime from = OffsetDateTime.parse(row.path("starts_at").asText());
            OffsetDateTime to = OffsetDateTime.parse(row.path("ends_at").asText());
            Duration elapsed = Duration.between(from, to);
            long minutes = elapsed.toMinutes();
            if (minutes <= 0 || elapsed.getNano() != 0 || elapsed.getSeconds() % 60 != 0
                    || minutes > Integer.MAX_VALUE)
                throw new Blocked("E3_CLEANING_RULE_INVALID", "Исходный интервал уборки некорректен");
            if (duration != null && duration != minutes)
                throw new Blocked("E3_CLEANING_RULE_AMBIGUOUS", "В snapshot разные длительности уборки");
            duration = Math.toIntExact(minutes);
        }
        if (duration == null)
            throw new Blocked("E3_CLEANING_RULE_MISSING", "Нет исходного интервала, задающего длительность уборки");

        List<ScenarioSnapshot.Resource> resources = new ArrayList<>(candidate.resources());
        List<ScenarioSnapshot.ServiceBlock> blocks = new ArrayList<>(candidate.blocks());
        List<OperationalConstraints.ServiceWindow> windows = new ArrayList<>(
                candidate.operations().serviceWindows());
        Map<UUID, String> logicalResources = new HashMap<>();
        List<UUID> ids = new ArrayList<>();
        for (var missing : cleaning.missing()) {
            int from = minute(candidate.horizonStart(), missing.earliestStart());
            int to = minute(candidate.horizonStart(), missing.latestEnd());
            if (to - from < duration)
                throw new Blocked("E3_CLEANING_NO_WINDOW", "После четвёртого рейса состава "
                        + missing.trainId() + " нет " + duration + " минут до следующего отправления");
            String resource = logicalResources.computeIfAbsent(missing.trainId(), train -> {
                String id = LOGICAL_RESOURCE_PREFIX + train;
                if (candidate.resources().stream().anyMatch(existing -> id.equals(existing.id())))
                    throw new IllegalArgumentException("logical cleaning resource conflicts with D1 resource");
                resources.add(new ScenarioSnapshot.Resource(id));
                return id;
            });
            UUID id = UUID.nameUUIDFromBytes(("E3:CLEANING:" + missing.trainId() + ":"
                    + missing.fourthTripId() + ":" + missing.nextTripId())
                    .getBytes(StandardCharsets.UTF_8));
            ids.add(id);
            blocks.add(new ScenarioSnapshot.ServiceBlock(id, missing.trainId(), resource,
                    duration, from, to, List.of(), ScenarioSnapshot.ServiceBlock.Kind.CLEANING));
            windows.add(new OperationalConstraints.ServiceWindow(missing.trainId(), resource,
                    from, to, "Modelled train-only cleaning; D1 has no cleaner capacity"));
        }
        var old = candidate.operations();
        var operations = new OperationalConstraints(old.protectedReserveTrainIds(),
                old.fixedOccupancies(), windows, old.frozenPlacements(),
                old.releaseRequirements(), old.hotReserve());
        return new Projection(new ScenarioSnapshot(candidate.schemaVersion(), candidate.scenarioId(),
                candidate.snapshotHash(), candidate.provenance() + "; cleaning duration from D1 synthetic slots; "
                + "cleaner capacity unspecified", candidate.horizonStart(), candidate.horizonEnd(),
                candidate.trains(), resources, blocks, candidate.fixedTrips(), operations), ids, duration);
    }

    private static int minute(OffsetDateTime start, OffsetDateTime at) {
        Duration elapsed = Duration.between(start, at);
        if (elapsed.isNegative() || elapsed.getNano() != 0 || elapsed.getSeconds() % 60 != 0)
            throw new Blocked("E3_CLEANING_TIME_INVALID", "Время уборки вне минутного горизонта");
        return Math.toIntExact(elapsed.toMinutes());
    }
}
