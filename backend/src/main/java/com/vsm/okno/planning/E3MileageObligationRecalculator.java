package com.vsm.okno.planning;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.validation.E3TripAssignmentAudit;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Recomputes mileage obligations after a proposed trip reassignment. The
 * returned blocks have no E3 operational windows and are not an approvable plan.
 */
public final class E3MileageObligationRecalculator {
    public record Recalculated(UUID scenarioId, String snapshotHash,
                               List<ScenarioSnapshot.FixedTrip> effectiveTrips,
                               List<ScenarioSnapshot.ServiceBlock> blocks,
                               List<MileageObligationGenerator.Obligation> obligations) {
        public Recalculated {
            effectiveTrips = List.copyOf(effectiveTrips);
            blocks = List.copyOf(blocks);
            obligations = List.copyOf(obligations);
        }
    }

    private final ObjectMapper json = new ObjectMapper();

    public Recalculated recalculate(SourceSnapshot saved, E3TripAssignmentLedger.Assignment assignment,
                                    OffsetDateTime expectedFrozenUntil, int expectedPreparationMinutes) {
        var structural = new E3TripAssignmentAudit().check(saved, assignment,
                expectedFrozenUntil, expectedPreparationMinutes);
        if (!structural.isEmpty())
            throw new IllegalArgumentException("trip assignment failed independent checks: "
                    + structural.getFirst().code());
        JsonNode root = json.readTree(saved.canonicalPayload());
        JsonNode scenario = root.path("scenario");
        OffsetDateTime start = time(scenario, "horizon_start"), end = time(scenario, "horizon_end");
        UUID ruleSetId = uuid(scenario, "rule_set_id");
        JsonNode ruleSet = null;
        for (JsonNode row : rows(root, "ruleSets")) {
            if (ruleSetId.equals(uuid(row, "id"))) ruleSet = row;
        }
        if (ruleSet == null || !List.of("SYNTHETIC", "CONFIRMED").contains(text(ruleSet, "confirmation_status"))
                || !"ABSOLUTE_GRID".equals(text(ruleSet, "mileage_policy"))
                || !"NOMINAL_MILESTONE".equals(text(ruleSet, "tolerance_basis")))
            throw new IllegalArgumentException("unsupported or unconfirmed mileage rule set");

        List<ScenarioSnapshot.Resource> resources = new ArrayList<>();
        for (JsonNode row : rows(root, "resources")) resources.add(new ScenarioSnapshot.Resource(text(row, "id")));
        Map<String, List<String>> resourcesByCycle = new LinkedHashMap<>();
        for (JsonNode row : rows(root, "cycleResources")) {
            if (ruleSetId.equals(uuid(row, "rule_set_id")))
                resourcesByCycle.computeIfAbsent(text(row, "cycle_code"), ignored -> new ArrayList<>())
                        .add(text(row, "resource_id"));
        }
        List<MileageObligationGenerator.CycleRule> rules = new ArrayList<>();
        for (JsonNode row : rows(root, "cycleRules")) {
            if (!ruleSetId.equals(uuid(row, "rule_set_id"))) continue;
            String code = text(row, "code");
            List<String> options = resourcesByCycle.get(code);
            if (options == null || options.isEmpty())
                throw new IllegalArgumentException("missing resource for " + code);
            rules.add(new MileageObligationGenerator.CycleRule(code, number(row, "interval_km"),
                    Math.toIntExact(number(row, "tolerance_basis_points")),
                    Math.toIntExact(number(row, "duration_minutes")),
                    Math.toIntExact(number(row, "rank")), options.getFirst(), text(row, "source")));
        }

        Map<UUID, Map<String, Long>> credits = new HashMap<>();
        for (JsonNode row : rows(root, "cycleBaselines")) {
            if (ruleSetId.equals(uuid(row, "rule_set_id")) && !time(row, "recorded_at").isAfter(start))
                credit(credits, uuid(row, "train_id"), text(row, "cycle_code"),
                        number(row, "credited_nominal_km"));
        }
        Map<UUID, JsonNode> events = new HashMap<>();
        for (JsonNode row : rows(root, "serviceEvents")) events.put(uuid(row, "id"), row);
        for (JsonNode row : rows(root, "serviceCredits")) {
            JsonNode event = events.get(uuid(row, "service_event_id"));
            if (event != null && ruleSetId.equals(uuid(row, "rule_set_id"))
                    && !time(event, "accepted_at").isAfter(start))
                credit(credits, uuid(event, "train_id"), text(row, "covered_cycle_code"),
                        number(row, "credited_nominal_km"));
        }

        List<MileageObligationGenerator.TrainState> trainStates = new ArrayList<>();
        for (JsonNode row : rows(root, "trains")) {
            UUID id = uuid(row, "id");
            var trace = assignment.trains().get(id);
            if (trace == null) throw new IllegalArgumentException("missing train trace " + id);
            trainStates.add(new MileageObligationGenerator.TrainState(
                    new ScenarioSnapshot.Train(id, text(row, "external_id")),
                    trace.initialKm(), credits.getOrDefault(id, Map.of())));
        }
        List<ScenarioSnapshot.FixedTrip> effective = new ArrayList<>();
        for (var trip : assignment.trips()) {
            effective.add(new ScenarioSnapshot.FixedTrip(trip.id(), trip.effectiveTrainId(), trip.label(),
                    minute(start, trip.departureAt()), minute(start, trip.arrivalAt()), trip.distanceKm(),
                    "E3 proposed assignment from saved trip " + trip.id()));
        }
        var projection = new MileageObligationGenerator().generate(new MileageObligationGenerator.Input(
                saved.scenarioId(), saved.snapshotHash(), text(scenario, "provenance"), start, end,
                trainStates, resources, effective, rules));
        Map<UUID, String> cycleByBlock = new HashMap<>();
        projection.obligations().forEach(item -> cycleByBlock.put(item.blockId(), item.cycleCode()));
        List<ScenarioSnapshot.ServiceBlock> blocks = new ArrayList<>();
        for (var block : projection.snapshot().blocks()) {
            List<String> options = resourcesByCycle.get(cycleByBlock.get(block.id()));
            if (options == null || options.isEmpty())
                throw new IllegalArgumentException("missing resource options for block " + block.id());
            blocks.add(new ScenarioSnapshot.ServiceBlock(block.id(), block.trainId(), options.getFirst(),
                    block.durationMinutes(), block.earliestStartMinute(), block.latestEndMinute(),
                    block.predecessorIds(), ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE, options));
        }
        return new Recalculated(saved.scenarioId(), saved.snapshotHash(), effective,
                blocks, projection.obligations());
    }

    private static void credit(Map<UUID, Map<String, Long>> credits, UUID train, String code, long nominal) {
        credits.computeIfAbsent(train, ignored -> new HashMap<>()).merge(code, nominal, Math::max);
    }
    private static JsonNode rows(JsonNode root, String field) {
        JsonNode value = root.path(field);
        if (!value.isArray()) throw new IllegalArgumentException("missing source collection " + field);
        return value;
    }
    private static String text(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (!value.isTextual() || value.asText().isBlank())
            throw new IllegalArgumentException("missing source field " + field);
        return value.asText();
    }
    private static long number(JsonNode row, String field) {
        JsonNode value = row.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong())
            throw new IllegalArgumentException("invalid source number " + field);
        return value.asLong();
    }
    private static UUID uuid(JsonNode row, String field) { return UUID.fromString(text(row, field)); }
    private static OffsetDateTime time(JsonNode row, String field) { return OffsetDateTime.parse(text(row, field)); }
    private static int minute(OffsetDateTime start, OffsetDateTime at) {
        Duration elapsed = Duration.between(start, at);
        if (elapsed.getNano() != 0 || elapsed.getSeconds() % 60 != 0)
            throw new IllegalArgumentException("trip time must lie on a whole-minute boundary");
        return Math.toIntExact(elapsed.toMinutes());
    }
}
