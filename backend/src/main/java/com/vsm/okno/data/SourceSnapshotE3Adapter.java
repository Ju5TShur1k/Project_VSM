package com.vsm.okno.data;

import com.vsm.okno.planning.MileageObligationGenerator;
import com.vsm.okno.planning.OperationalConstraints;
import com.vsm.okno.planning.ScenarioSnapshot;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Full-fleet D1 handoff. Every source occupancy and frozen work must constrain
 * the solver; reserve rows are availability evidence rather than busy intervals.
 * This projection does not itself grant D2 approval.
 */
public final class SourceSnapshotE3Adapter {
    private final ObjectMapper json = new ObjectMapper();

    public MileageObligationGenerator.Projection project(SourceSnapshotRepository.SourceSnapshot stored) {
        require("d1-source-1.0".equals(stored.schemaVersion()), "unsupported D1 snapshot version");
        require("pg-jsonb-text-v1".equals(stored.canonicalization()), "unsupported canonicalization");
        require(hash(stored.canonicalPayload()).equals(stored.snapshotHash()), "source snapshot SHA-256 mismatch");
        JsonNode root = json.readTree(stored.canonicalPayload());
        require("d1-source-1.0".equals(text(root, "schemaVersion")), "payload version mismatch");
        require("pg-jsonb-text-v1".equals(text(root, "canonicalization")), "payload canonicalization mismatch");
        require(stored.scenarioId().equals(uuid(root, "scenarioId")), "payload scenario mismatch");
        JsonNode urgent = root.path("urgentWorkRequirements");
        require(urgent.isMissingNode() || (urgent.isArray() && urgent.isEmpty()),
                "urgentWorkRequirements need duration, resource and release rules before E3 planning");
        JsonNode scenario = root.path("scenario");
        require(scenario.isObject(), "scenario missing");
        OffsetDateTime start = time(scenario, "horizon_start");
        OffsetDateTime end = time(scenario, "horizon_end");
        UUID ruleSetId = uuid(scenario, "rule_set_id");
        JsonNode ruleSet = null;
        for (JsonNode candidate : array(root, "ruleSets")) {
            if (ruleSetId.equals(uuid(candidate, "id"))) ruleSet = candidate;
        }
        require(ruleSet != null, "active rule set missing");
        require(Set.of("SYNTHETIC", "CONFIRMED").contains(text(ruleSet, "confirmation_status")),
                "unconfirmed cycle rule set cannot be planned");
        require("ABSOLUTE_GRID".equals(text(ruleSet, "mileage_policy"))
                && "NOMINAL_MILESTONE".equals(text(ruleSet, "tolerance_basis")),
                "E3 supports only explicitly sourced absolute-grid / nominal-milestone rules");

        List<ScenarioSnapshot.Resource> resources = new ArrayList<>();
        Map<String, String> resourceLocations = new HashMap<>();
        for (JsonNode row : array(root, "resources")) {
            String id = text(row, "id");
            resources.add(new ScenarioSnapshot.Resource(id));
            resourceLocations.put(id, text(row, "location"));
        }
        require(!resources.isEmpty(), "resources missing");
        JsonNode outages = root.path("resourceOutages");
        require(outages.isMissingNode() || outages.isArray(), "resourceOutages must be an array");
        if (outages.isArray()) for (JsonNode outage : outages) {
            String resource = text(outage, "resource_id");
            OffsetDateTime from = time(outage, "starts_at"), to = time(outage, "ends_at");
            require(resourceLocations.containsKey(resource) && from.isBefore(to)
                            && !from.isBefore(start) && !to.isAfter(end),
                    "invalid resource outage " + resource);
            for (JsonNode availability : array(root, "resourceAvailability")) {
                if (resource.equals(text(availability, "resource_id")))
                    require(!from.isBefore(time(availability, "ends_at"))
                                    || !time(availability, "starts_at").isBefore(to),
                            "resource availability overlaps outage " + resource);
            }
        }
        Map<String, List<String>> resourcesByCycle = new HashMap<>();
        for (JsonNode row : array(root, "cycleResources")) {
            if (!ruleSetId.equals(uuid(row, "rule_set_id"))) continue;
            String resource = text(row, "resource_id");
            require(resourceLocations.containsKey(resource), "unknown cycle resource " + resource);
            List<String> options = resourcesByCycle.computeIfAbsent(text(row, "cycle_code"),
                    ignored -> new ArrayList<>());
            require(!options.contains(resource), "duplicate resource mapping for " + text(row, "cycle_code"));
            options.add(resource);
        }
        List<MileageObligationGenerator.CycleRule> rules = new ArrayList<>();
        for (JsonNode row : array(root, "cycleRules")) {
            if (!ruleSetId.equals(uuid(row, "rule_set_id"))) continue;
            String code = text(row, "code");
            List<String> options = resourcesByCycle.get(code);
            require(options != null && !options.isEmpty(), "resource mapping missing for " + code);
            String resource = options.getFirst();
            rules.add(new MileageObligationGenerator.CycleRule(code, number(row, "interval_km"),
                    Math.toIntExact(number(row, "tolerance_basis_points")),
                    Math.toIntExact(number(row, "duration_minutes")),
                    Math.toIntExact(number(row, "rank")), resource, text(row, "source")));
        }
        require(!rules.isEmpty(), "active cycle rules missing");

        Map<UUID, List<JsonNode>> readings = grouped(root, "odometerReadings", "train_id");
        Map<UUID, List<JsonNode>> baselines = grouped(root, "cycleBaselines", "train_id");
        Map<UUID, List<JsonNode>> events = grouped(root, "serviceEvents", "train_id");
        Map<UUID, List<JsonNode>> credits = grouped(root, "serviceCredits", "service_event_id");
        List<MileageObligationGenerator.TrainState> trainStates = new ArrayList<>();
        for (JsonNode row : array(root, "trains")) {
            UUID trainId = uuid(row, "id");
            require(Set.of("AVAILABLE", "IN_SERVICE", "MAINTENANCE", "RESERVE", "FAILED")
                            .contains(text(row, "status")), "unsupported train state: " + trainId);
            JsonNode reading = readings.getOrDefault(trainId, List.of()).stream()
                    .filter(value -> !time(value, "observed_at").isAfter(start))
                    .max(Comparator.comparing(value -> time(value, "observed_at"))).orElse(null);
            require(reading != null, "odometer at horizon start missing for train " + trainId);
            require(readings.getOrDefault(trainId, List.of()).stream()
                            .noneMatch(value -> time(value, "observed_at").isAfter(start)),
                    "in-horizon odometer updates need a newer adapter for train " + trainId);
            Map<String, Long> lastCredit = new HashMap<>();
            for (JsonNode baseline : baselines.getOrDefault(trainId, List.of())) {
                if (!ruleSetId.equals(uuid(baseline, "rule_set_id"))) continue;
                require(!time(baseline, "recorded_at").isAfter(start), "baseline occurs after horizon start");
                String code = text(baseline, "cycle_code");
                lastCredit.merge(code, number(baseline, "credited_nominal_km"), Math::max);
            }
            for (JsonNode event : events.getOrDefault(trainId, List.of())) {
                if (time(event, "accepted_at").isAfter(start)) continue;
                for (JsonNode credit : credits.getOrDefault(uuid(event, "id"), List.of())) {
                    if (!ruleSetId.equals(uuid(credit, "rule_set_id"))) continue;
                    lastCredit.merge(text(credit, "covered_cycle_code"),
                            number(credit, "credited_nominal_km"), Math::max);
                }
            }
            for (var rule : rules) {
                require(lastCredit.containsKey(rule.code()),
                        "explicit credit history missing: " + trainId + "/" + rule.code());
            }
            trainStates.add(new MileageObligationGenerator.TrainState(
                    new ScenarioSnapshot.Train(trainId, text(row, "external_id")),
                    number(reading, "odometer_km"), lastCredit));
        }

        List<ScenarioSnapshot.FixedTrip> trips = new ArrayList<>();
        for (JsonNode row : array(root, "fixedTrips")) {
            int departure = minute(start, time(row, "departure_at"));
            int arrival = minute(start, time(row, "arrival_at"));
            trips.add(new ScenarioSnapshot.FixedTrip(uuid(row, "id"), uuid(row, "train_id"),
                    text(row, "label"), departure, arrival, number(row, "distance_km"), text(row, "source")));
        }
        var projection = new MileageObligationGenerator().generate(new MileageObligationGenerator.Input(
                stored.scenarioId(), stored.snapshotHash(), text(scenario, "provenance"), start, end,
                trainStates, resources, trips, rules));

        Map<UUID, String> cycleByBlock = new HashMap<>();
        projection.obligations().forEach(obligation -> cycleByBlock.put(obligation.blockId(), obligation.cycleCode()));
        List<ScenarioSnapshot.ServiceBlock> blocks = new ArrayList<>();
        for (var block : projection.snapshot().blocks()) {
            List<String> options = resourcesByCycle.get(cycleByBlock.get(block.id()));
            require(options != null && !options.isEmpty(), "missing resource options for block " + block.id());
            blocks.add(new ScenarioSnapshot.ServiceBlock(block.id(), block.trainId(), options.getFirst(),
                    block.durationMinutes(), block.earliestStartMinute(), block.latestEndMinute(),
                    block.predecessorIds(), ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE, options));
        }

        List<OperationalConstraints.ServiceWindow> windows = new ArrayList<>();
        for (JsonNode presence : array(root, "trainPresence")) {
            require(Set.of("SYNTHETIC", "CONFIRMED").contains(text(presence, "confirmation_status")),
                    "unconfirmed train presence cannot authorize service window");
            for (JsonNode availability : array(root, "resourceAvailability")) {
                String resource = text(availability, "resource_id");
                String location = resourceLocations.get(resource);
                require(location != null, "resource location missing for " + resource);
                if (!location.equals(text(presence, "location"))) continue;
                OffsetDateTime from = max(start, time(presence, "starts_at"), time(availability, "starts_at"));
                OffsetDateTime to = min(end, time(presence, "ends_at"), time(availability, "ends_at"));
                if (from.isBefore(to)) windows.add(new OperationalConstraints.ServiceWindow(
                        uuid(presence, "train_id"), resource, minute(start, from), minute(start, to),
                        text(presence, "source") + "; " + text(availability, "source")));
            }
        }
        require(!windows.isEmpty(), "checked train/resource presence windows missing");
        var prepared = projection.snapshot();
        List<OperationalConstraints.FixedOccupancy> occupied = new ArrayList<>();
        Set<UUID> protectedReserve = new HashSet<>();
        for (JsonNode train : array(root, "trains")) {
            if ("RESERVE".equals(text(train, "status"))) protectedReserve.add(uuid(train, "id"));
        }
        for (JsonNode row : array(root, "cleaningCounters")) {
            require(Set.of("SYNTHETIC", "CONFIRMED").contains(text(row, "confirmation_status")),
                    "unconfirmed cleaning counter for " + uuid(row, "train_id"));
            require(number(row, "completed_trips_since_cleaning") >= 0,
                    "invalid cleaning counter for " + uuid(row, "train_id"));
        }
        List<JsonNode> unavailable = new ArrayList<>();
        Map<UUID, List<JsonNode>> reserveOccupancies = new HashMap<>();
        for (JsonNode row : array(root, "trainOccupancy")) {
            require(Set.of("SYNTHETIC", "CONFIRMED").contains(text(row, "confirmation_status")),
                    "unconfirmed occupancy " + uuid(row, "id"));
            String kind = text(row, "kind");
            if ("RESERVE".equals(kind)) {
                require(protectedReserve.contains(uuid(row, "train_id")),
                        "reserve occupancy of non-reserve train");
                reserveOccupancies.computeIfAbsent(uuid(row, "train_id"), ignored -> new ArrayList<>()).add(row);
                continue;
            }
            require(Set.of("CLEANING", "UNAVAILABLE", "OTHER").contains(kind),
                    "unsupported occupancy kind " + kind);
            if ("UNAVAILABLE".equals(kind)) unavailable.add(row);
            occupied.add(new OperationalConstraints.FixedOccupancy(uuid(row, "id"),
                    uuid(row, "train_id"), null, minute(start, time(row, "starts_at")),
                    minute(start, time(row, "ends_at")), OperationalConstraints.Kind.OTHER_COMMITMENT,
                    text(row, "source") + "; " + kind));
        }
        for (JsonNode row : array(root, "frozenWork")) {
            require(Set.of("SYNTHETIC", "CONFIRMED").contains(text(row, "confirmation_status")),
                    "unconfirmed frozen work " + uuid(row, "id"));
            UUID train = uuid(row, "train_id");
            int from = minute(start, time(row, "starts_at"));
            int to = minute(start, time(row, "ends_at"));
            // A frozen work may be inside a broader UNAVAILABLE period. Keep the
            // resource occupied separately without double-booking the same train.
            boolean covered = unavailable.stream().anyMatch(o -> train.equals(uuid(o, "train_id"))
                    && minute(start, time(o, "starts_at")) <= from
                    && to <= minute(start, time(o, "ends_at")));
            occupied.add(new OperationalConstraints.FixedOccupancy(uuid(row, "id"),
                    covered ? null : train, text(row, "resource_id"), from, to,
                    OperationalConstraints.Kind.OTHER_COMMITMENT, text(row, "source") + "; frozen work"));
        }
        require(protectedReserve.size() >= OperationalConstraints.HotReserve.REQUIRED_TRAINS,
                "full fleet has fewer than four explicitly reserved trains");
        for (UUID reserveTrain : protectedReserve) {
            List<JsonNode> intervals = reserveOccupancies.getOrDefault(reserveTrain, List.of()).stream()
                    .sorted(Comparator.comparing(row -> time(row, "starts_at"))).toList();
            OffsetDateTime coveredUntil = start;
            for (JsonNode interval : intervals) {
                OffsetDateTime from = time(interval, "starts_at"), to = time(interval, "ends_at");
                require(!from.isAfter(coveredUntil), "reserve occupancy has a gap for " + reserveTrain);
                if (to.isAfter(coveredUntil)) coveredUntil = to;
            }
            require(!coveredUntil.isBefore(end), "reserve occupancy does not cover horizon for " + reserveTrain);
            String city = null;
            for (JsonNode train : array(root, "trains")) {
                if (reserveTrain.equals(uuid(train, "id"))) city = text(train, "location");
            }
            require(city != null, "reserve train is missing from source");
            boolean present = false;
            for (JsonNode presence : array(root, "trainPresence")) {
                if (reserveTrain.equals(uuid(presence, "train_id"))
                        && city.equals(text(presence, "location"))
                        && !time(presence, "starts_at").isAfter(start)
                        && !time(presence, "ends_at").isBefore(end)) present = true;
            }
            require(present, "reserve train has no full-horizon presence: " + reserveTrain);
        }
        var operations = new OperationalConstraints(protectedReserve, occupied, windows, List.of(), List.of(),
                new OperationalConstraints.HotReserve(protectedReserve,
                        "MODELLED protected reserve from source train status"));
        return new MileageObligationGenerator.Projection(new ScenarioSnapshot("1.4", prepared.scenarioId(),
                prepared.snapshotHash(), prepared.provenance(), prepared.horizonStart(), prepared.horizonEnd(),
                prepared.trains(), prepared.resources(), blocks, prepared.fixedTrips(), operations),
                projection.obligations());
    }

    private static Map<UUID, List<JsonNode>> grouped(JsonNode root, String collection, String key) {
        Map<UUID, List<JsonNode>> result = new HashMap<>();
        for (JsonNode row : array(root, collection)) {
            result.computeIfAbsent(uuid(row, key), ignored -> new ArrayList<>()).add(row);
        }
        return result;
    }

    private static JsonNode array(JsonNode root, String name) {
        JsonNode value = root.path(name);
        require(value.isArray(), name + " missing or not an array");
        return value;
    }

    private static String text(JsonNode row, String key) {
        JsonNode value = row.path(key);
        require(value.isTextual() && !value.asText().isBlank(), key + " missing");
        return value.asText();
    }

    private static long number(JsonNode row, String key) {
        JsonNode value = row.path(key);
        require(value.isIntegralNumber() && value.canConvertToLong(), key + " must be integral");
        return value.asLong();
    }

    private static UUID uuid(JsonNode row, String key) {
        return UUID.fromString(text(row, key));
    }

    private static OffsetDateTime time(JsonNode row, String key) {
        return OffsetDateTime.parse(text(row, key));
    }

    private static int minute(OffsetDateTime origin, OffsetDateTime value) {
        Duration elapsed = Duration.between(origin, value);
        require(elapsed.getNano() == 0 && elapsed.getSeconds() % 60 == 0,
                "time must lie on a whole-minute boundary: " + value);
        return Math.toIntExact(elapsed.toMinutes());
    }

    private static OffsetDateTime max(OffsetDateTime a, OffsetDateTime b, OffsetDateTime c) {
        OffsetDateTime ab = a.isAfter(b) ? a : b;
        return ab.isAfter(c) ? ab : c;
    }

    private static OffsetDateTime min(OffsetDateTime a, OffsetDateTime b, OffsetDateTime c) {
        OffsetDateTime ab = a.isBefore(b) ? a : b;
        return ab.isBefore(c) ? ab : c;
    }

    private static String hash(String input) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
