package com.vsm.okno.service;

import com.vsm.okno.dto.Dto;
import com.vsm.okno.planning.OperationalConstraints;
import com.vsm.okno.planning.ScenarioSnapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Builds the planner's input projection (schema 1.4) from an imported scenario.
 *
 * ponytail: everything here is SYNTHETIC. The imported scenario only carries a
 * train list, so there are no real trips, mileage or depot calendars to plan
 * against. Replace with the projection of D1's canonical snapshot (real cycle
 * history, trips, resource calendars, sha256 of the source facts) once it exists.
 * Never present the resulting plan as an EVS360 schedule.
 */
final class SyntheticSnapshot {
    static final OffsetDateTime HORIZON_START = OffsetDateTime.parse("2028-07-01T00:00:00+03:00");
    static final int HORIZON_MINUTES = 14 * 24 * 60;
    static final List<String> RESOURCES = List.of("TRACK_1", "TRACK_2"); // number of depot tracks is unknown
    static final Set<String> FAILURE_KINDS = Set.of("MACHINE_DOWN", "UNPLANNED_INSPECTION");

    private static final int IS100_MINUTES = 2 * 60;         // CASE: IS100 downtime is 2 h (6_ru.pdf, p.3)
    private static final int MACHINE_DOWN_MINUTES = 12 * 60; // the demo failure from the brief
    private static final int INSPECTION_MINUTES = 4 * 60;    // synthetic
    private static final String SOURCE = "synthetic: F1 projection of the imported train list";

    private SyntheticSnapshot() {}

    static ScenarioSnapshot of(UUID scenarioId, List<Dto.Train> trains, List<String> failures) {
        Set<UUID> reserve = new HashSet<>();
        List<Dto.Train> working = new ArrayList<>();
        for (Dto.Train t : trains) {
            if ("HOT_RESERVE".equals(t.status())) reserve.add(t.id());
            else working.add(t);
        }

        List<ScenarioSnapshot.ServiceBlock> blocks = new ArrayList<>();
        for (int i = 0; i < working.size(); i++) {
            blocks.add(block(scenarioId, working.get(i), RESOURCES.get(i % RESOURCES.size()), IS100_MINUTES, "IS100", 0));
        }

        List<OperationalConstraints.FixedOccupancy> outages = new ArrayList<>();
        int machineDowns = 0, inspections = 0;
        for (String kind : failures) {
            switch (kind) {
                // Consecutive outages of the first track, each 12 h after the previous one.
                case "MACHINE_DOWN" -> {
                    int start = machineDowns * MACHINE_DOWN_MINUTES;
                    outages.add(new OperationalConstraints.FixedOccupancy(
                            uuid(scenarioId, kind + machineDowns), null, RESOURCES.get(0), start,
                            start + MACHINE_DOWN_MINUTES, OperationalConstraints.Kind.RESOURCE_OUTAGE, SOURCE));
                    machineDowns++;
                }
                // An extra mandatory inspection, spread over the working trains.
                case "UNPLANNED_INSPECTION" -> {
                    if (working.isEmpty()) throw new IllegalArgumentException("no working trains to inspect");
                    blocks.add(block(scenarioId, working.get(inspections % working.size()), RESOURCES.get(0),
                            INSPECTION_MINUTES, "INSPECTION", inspections));
                    inspections++;
                }
                default -> throw new IllegalArgumentException("unknown failure kind: " + kind);
            }
        }

        // Schema 1.2+ has no implicit depot access: every train/resource pair needs a window.
        List<OperationalConstraints.ServiceWindow> windows = blocks.stream()
                .map(b -> new OperationalConstraints.ServiceWindow(b.trainId(), b.resourceId(), 0, HORIZON_MINUTES, SOURCE))
                .distinct().toList();

        Set<UUID> allTrains = new HashSet<>();
        trains.forEach(t -> allTrains.add(t.id()));
        var operations = new OperationalConstraints(reserve, outages, windows, List.of(), List.of(),
                new OperationalConstraints.HotReserve(allTrains, SOURCE));

        return new ScenarioSnapshot("1.4", scenarioId, hash(scenarioId, trains, failures), "synthetic",
                HORIZON_START, HORIZON_START.plusMinutes(HORIZON_MINUTES),
                trains.stream().map(t -> new ScenarioSnapshot.Train(t.id(), t.externalId())).toList(),
                RESOURCES.stream().map(ScenarioSnapshot.Resource::new).toList(),
                blocks, List.of(), operations);
    }

    private static ScenarioSnapshot.ServiceBlock block(UUID scenarioId, Dto.Train train, String resource,
                                                       int minutes, String label, int n) {
        return new ScenarioSnapshot.ServiceBlock(uuid(scenarioId, label + train.id() + n), train.id(), resource,
                minutes, 0, HORIZON_MINUTES, List.of(), ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE);
    }

    // Deterministic ids: the same scenario always yields the same snapshot and hash.
    private static UUID uuid(UUID scenarioId, String key) {
        return UUID.nameUUIDFromBytes((scenarioId + "/" + key).getBytes(StandardCharsets.UTF_8));
    }

    private static String hash(UUID scenarioId, List<Dto.Train> trains, List<String> failures) {
        StringBuilder canonical = new StringBuilder("1.4|").append(scenarioId)
                .append("|").append(IS100_MINUTES).append(",").append(MACHINE_DOWN_MINUTES).append(",").append(INSPECTION_MINUTES)
                .append("|").append(String.join(",", RESOURCES)).append("|").append(String.join(",", failures));
        trains.forEach(t -> canonical.append("|").append(t.id()).append(":").append(t.externalId()).append(":").append(t.status()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // SHA-256 is mandatory on every JVM
        }
    }
}
