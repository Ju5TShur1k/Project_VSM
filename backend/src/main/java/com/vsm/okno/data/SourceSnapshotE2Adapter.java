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
import java.util.Set;
import java.util.UUID;

/**
 * Narrow, explicit D1 handoff for E2: source snapshot -> fixed trips, credited
 * mileage obligations and checked service windows. E3-only facts are rejected
 * until the 1.4 adapter can preserve them. This is not an approval rule.
 */
public final class SourceSnapshotE2Adapter {
    private final ObjectMapper json = new ObjectMapper();

    public MileageObligationGenerator.Projection project(SourceSnapshotRepository.SourceSnapshot stored) {
        require("d1-source-1.0".equals(stored.schemaVersion()), "unsupported D1 snapshot version");
        require("pg-jsonb-text-v1".equals(stored.canonicalization()), "unsupported canonicalization");
        require(hash(stored.canonicalPayload()).equals(stored.snapshotHash()), "source snapshot SHA-256 mismatch");
        JsonNode root = json.readTree(stored.canonicalPayload());
        require("d1-source-1.0".equals(text(root, "schemaVersion")), "payload version mismatch");
        require("pg-jsonb-text-v1".equals(text(root, "canonicalization")), "payload canonicalization mismatch");
        require(stored.scenarioId().equals(uuid(root, "scenarioId")), "payload scenario mismatch");
        for (String unsupported : List.of("urgentWorkRequirements", "resourceOutages", "trainReleases", "urgentWorkRules")) {
            JsonNode facts = root.path(unsupported);
            require(facts.isMissingNode() || (facts.isArray() && facts.isEmpty()),
                    unsupported + " requires a capable planning adapter; refusing to omit it");
        }
        for (String unsupported : List.of("trainOccupancy", "cleaningCounters", "frozenWork")) {
            require(root.path(unsupported).isArray() && root.path(unsupported).isEmpty(),
                    unsupported + " requires the E3 adapter; refusing to omit it");
        }
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
        require(!"UNCONFIRMED".equals(text(ruleSet, "confirmation_status")),
                "unconfirmed cycle rule set cannot be planned");
        require("ABSOLUTE_GRID".equals(text(ruleSet, "mileage_policy"))
                && "NOMINAL_MILESTONE".equals(text(ruleSet, "tolerance_basis")),
                "E2 supports only explicitly sourced absolute-grid / nominal-milestone rules");

        List<ScenarioSnapshot.Resource> resources = new ArrayList<>();
        Map<String, String> resourceLocations = new HashMap<>();
        for (JsonNode row : array(root, "resources")) {
            String id = text(row, "id");
            resources.add(new ScenarioSnapshot.Resource(id));
            resourceLocations.put(id, text(row, "location"));
        }
        require(!resources.isEmpty(), "resources missing");
        Map<String, String> resourceByCycle = new HashMap<>();
        for (JsonNode row : array(root, "cycleResources")) {
            if (!ruleSetId.equals(uuid(row, "rule_set_id"))) continue;
            String previous = resourceByCycle.putIfAbsent(text(row, "cycle_code"), text(row, "resource_id"));
            require(previous == null, "E2 cannot choose among alternative resources for a cycle");
        }
        List<MileageObligationGenerator.CycleRule> rules = new ArrayList<>();
        for (JsonNode row : array(root, "cycleRules")) {
            if (!ruleSetId.equals(uuid(row, "rule_set_id"))) continue;
            String code = text(row, "code");
            String resource = resourceByCycle.get(code);
            require(resource != null, "resource mapping missing for " + code);
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
            require("AVAILABLE".equals(text(row, "status")),
                    "E2 adapter supports only AVAILABLE trains: " + trainId);
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

        List<OperationalConstraints.ServiceWindow> windows = new ArrayList<>();
        for (JsonNode presence : array(root, "trainPresence")) {
            require(!"UNCONFIRMED".equals(text(presence, "confirmation_status")),
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
        var operations = new OperationalConstraints(Set.of(), List.of(), windows, List.of());
        return new MileageObligationGenerator.Projection(new ScenarioSnapshot("1.2", prepared.scenarioId(),
                prepared.snapshotHash(), prepared.provenance(), prepared.horizonStart(), prepared.horizonEnd(),
                prepared.trains(), prepared.resources(), prepared.blocks(), prepared.fixedTrips(), operations),
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
