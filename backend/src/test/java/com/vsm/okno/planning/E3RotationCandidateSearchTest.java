package com.vsm.okno.planning;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.data.SourceSnapshotE3Adapter;
import com.vsm.okno.validation.PlanFingerprint;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class E3RotationCandidateSearchTest {
    private static final OffsetDateTime START = OffsetDateTime.parse("2031-07-01T00:00:00+03:00");
    private static final OffsetDateTime END = OffsetDateTime.parse("2031-07-02T08:15:00+03:00");
    private static final UUID SCENARIO = id("scenario"), RULE = id("rule"), LINE = id("line");

    @Test
    void findsDocumentedReserveReturnRotationWithoutClaimingAFullPlan() {
        SourceSnapshot saved = source();
        var ledger = new E3TripAssignmentLedger();
        var base = ledger.evaluate(saved, Map.of(), START, 55);
        var baseline = new E3CandidateProjection().project(saved, base, START, 55).snapshot();
        assertEquals(Set.copyOf(new SourceSnapshotE3Adapter().project(saved).snapshot()
                        .operations().serviceWindows()),
                Set.copyOf(baseline.operations().serviceWindows()));
        assertEquals(1, E3FeasibilityAudit.blockedBlockIds(baseline).size());

        var found = new E3RotationCandidateSearch().search(saved, START, 55, 8);
        assertEquals("IMPROVING_ROTATION_FOUND", found.searchStatus());
        assertEquals(saved.snapshotHash(), found.snapshotHash());
        assertEquals(1, found.initialBlockedWorks());
        assertEquals(0, found.remainingBlockedWorks());
        assertEquals(2, found.effectiveTrainByTrip().size());
        UUID selectedReserve = found.effectiveTrainByTrip().get(id("trip-0"));
        assertTrue(List.of(id("reserve-0"), id("reserve-2"), id("reserve-3"))
                .contains(selectedReserve));
        assertEquals(selectedReserve, found.effectiveTrainByTrip().get(id("trip-1")));
        assertEquals(1, found.reserve().mobilizedTrainCount());
        assertTrue(found.cleaning().missing().isEmpty());
        assertEquals("NOT_RUN", found.solverStatus());
        assertEquals("NOT_PERFORMED", found.d2Status());
        var complete = new E3RotationCandidateSearch().search(saved,
                found.effectiveTrainByTrip(), START, 55, 8);
        assertEquals("NO_FIXED_WINDOW_BLOCKER", complete.searchStatus());
        assertEquals(found.effectiveTrainByTrip(), complete.effectiveTrainByTrip());
    }

    @Test
    void boundsTheExploratorySearch() {
        assertThrows(IllegalArgumentException.class,
                () -> new E3RotationCandidateSearch().search(source(), START, 55, 33));
    }

    @Test
    void passesAuditedReassignmentToMaintenanceSolverButKeepsFullPlanPending() {
        SourceSnapshot saved = source();
        var selected = new E3RotationCandidateSearch().search(saved, START, 55, 8);
        Planner witness = (snapshot, request) -> {
            assertEquals(saved.snapshotHash(), snapshot.snapshotHash());
            assertEquals(selected.effectiveTrainByTrip().get(id("trip-0")),
                    snapshot.fixedTrips().stream().filter(trip -> trip.id().equals(id("trip-0")))
                            .findFirst().orElseThrow().trainId());
            assertTrue(E3FeasibilityAudit.blockedBlockIds(snapshot).isEmpty());
            assertTrue(snapshot.operations().protectedReserveTrainIds().isEmpty());
            var block = snapshot.blocks().getFirst();
            return new PlannerResult("1.0", snapshot.scenarioId(), snapshot.snapshotHash(),
                    request.policy(), PlannerResult.SolverStatus.FEASIBLE,
                    List.of(new PlannerResult.PlannedBlock(block.id(), block.trainId(),
                            block.resourceId(), START.plusHours(1), START.plusHours(11))),
                    List.of(), request.seed(), 1, 660.0);
        };
        var result = new E3MaintenanceCandidateSolver(witness).solve(saved,
                new E3MaintenanceCandidateSolver.Input(START, 55,
                        selected.effectiveTrainByTrip(), 1, 5));
        assertEquals("FEASIBLE", result.maintenanceSolverStatus());
        assertEquals("NOT_RUN", result.fullSolverStatus());
        assertEquals("NOT_PERFORMED", result.d2Status());
        assertEquals(1, result.maintenanceBlocks().size());

        Planner unexpected = (snapshot, request) -> {
            throw new AssertionError("blocked candidate must not start CP-SAT");
        };
        var blocked = new E3MaintenanceCandidateSolver(unexpected).solve(saved,
                new E3MaintenanceCandidateSolver.Input(START, 55, Map.of(), 1, 5));
        assertEquals("NOT_RUN", blocked.maintenanceSolverStatus());
        assertEquals(1, blocked.diagnostics().size());
    }

    @Test
    void jointCandidateAutomaticallySearchesThenRunsMaintenanceOnly() {
        SourceSnapshot saved = source();
        Planner witness = (snapshot, request) -> {
            assertTrue(E3FeasibilityAudit.blockedBlockIds(snapshot).isEmpty());
            var work = snapshot.blocks().getFirst();
            return new PlannerResult("1.0", snapshot.scenarioId(), snapshot.snapshotHash(),
                    request.policy(), PlannerResult.SolverStatus.FEASIBLE,
                    List.of(new PlannerResult.PlannedBlock(work.id(), work.trainId(),
                            work.resourceId(), START.plusHours(1), START.plusHours(11))),
                    List.of(), request.seed(), 1, 660.0);
        };
        var result = new E3JointCandidatePlanner(witness).plan(saved,
                new E3JointCandidatePlanner.Input(START, 55, 2, 8, 1, 5));
        assertEquals("MAINTENANCE_CANDIDATE_FOUND", result.searchStatus());
        assertEquals(1, result.moves());
        assertEquals(2, result.effectiveTrainByTrip().size());
        assertEquals("FEASIBLE", result.maintenance().maintenanceSolverStatus());
        assertEquals("NOT_RUN", result.maintenance().fullSolverStatus());
        assertEquals("NOT_PERFORMED", result.maintenance().d2Status());

        Planner unexpected = (snapshot, request) -> {
            throw new AssertionError("zero moves must keep the original blocker");
        };
        var bounded = new E3JointCandidatePlanner(unexpected).plan(saved,
                new E3JointCandidatePlanner.Input(START, 55, 0, 8, 1, 5));
        assertEquals("MOVE_LIMIT_REACHED", bounded.searchStatus());
        assertEquals("NOT_RUN", bounded.maintenance().maintenanceSolverStatus());
    }

    private static SourceSnapshot source() {
        List<Object> trains = new ArrayList<>(), odometers = new ArrayList<>(), baselines = new ArrayList<>();
        List<Object> presence = new ArrayList<>(), occupancy = new ArrayList<>(), counters = new ArrayList<>();
        for (int index = -1; index < 4; index++) {
            UUID train = index < 0 ? LINE : id("reserve-" + index);
            String city = index == 1 ? "MOSCOW" : "SPB_DEPOT";
            String status = index < 0 ? "AVAILABLE" : "RESERVE";
            trains.add(Map.of("id", train, "external_id", index < 0 ? "LINE" : "RES-" + index,
                    "location", city, "status", status));
            odometers.add(Map.of("train_id", train, "observed_at", START, "odometer_km",
                    index < 0 ? 4000 : 0));
            baselines.add(Map.of("train_id", train, "rule_set_id", RULE, "cycle_code", "IS5000",
                    "credited_nominal_km", 0, "recorded_at", START.minusDays(1)));
            counters.add(Map.of("train_id", train, "observed_at", START,
                    "completed_trips_since_cleaning", 0, "confirmation_status", "SYNTHETIC"));
            if (index >= 0) {
                presence.add(Map.of("train_id", train, "location", city, "starts_at", START,
                        "ends_at", END, "confirmation_status", "SYNTHETIC", "source", "test reserve"));
                occupancy.add(Map.of("id", id("occupancy-" + index), "train_id", train,
                        "kind", "RESERVE", "starts_at", START, "ends_at", END,
                        "confirmation_status", "SYNTHETIC", "source", "test reserve"));
            }
        }
        // Original presence offers no continuous 10-hour service window.
        presence.add(presence(LINE, "SPB_DEPOT", START, START.plusHours(5)));
        presence.add(presence(LINE, "MOSCOW", START.plusHours(8).plusMinutes(30),
                START.plusHours(11)));
        presence.add(presence(LINE, "SPB_DEPOT", START.plusHours(14).plusMinutes(30),
                START.plusHours(17)));
        presence.add(presence(LINE, "MOSCOW", START.plusHours(20).plusMinutes(30),
                START.plusDays(1).plusHours(5)));
        List<Object> trips = List.of(
                trip(0, "SPB_DEPOT", "MOSCOW", START.plusHours(6), START.plusHours(8)),
                trip(1, "MOSCOW", "SPB_DEPOT", START.plusHours(12), START.plusHours(14)),
                trip(2, "SPB_DEPOT", "MOSCOW", START.plusHours(18), START.plusHours(20)),
                trip(3, "MOSCOW", "SPB_DEPOT", START.plusDays(1).plusHours(6),
                        START.plusDays(1).plusHours(8)));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("schemaVersion", "d1-source-1.0");
        root.put("canonicalization", "pg-jsonb-text-v1");
        root.put("scenarioId", SCENARIO);
        root.put("scenario", Map.of("id", SCENARIO, "rule_set_id", RULE,
                "provenance", "synthetic rotation test", "horizon_start", START, "horizon_end", END));
        root.put("trains", trains);
        root.put("odometerReadings", odometers);
        root.put("cycleBaselines", baselines);
        root.put("cleaningCounters", counters);
        root.put("fixedTrips", trips);
        root.put("trainPresence", presence);
        root.put("trainOccupancy", occupancy);
        root.put("frozenWork", List.of());
        root.put("ruleSets", List.of(Map.of("id", RULE, "confirmation_status", "SYNTHETIC",
                "mileage_policy", "ABSOLUTE_GRID", "tolerance_basis", "NOMINAL_MILESTONE")));
        root.put("resources", List.of(Map.of("id", "SPB-PATH", "location", "SPB_DEPOT"),
                Map.of("id", "MOS-PATH", "location", "MOSCOW")));
        root.put("resourceAvailability", List.of(Map.of("resource_id", "SPB-PATH",
                "starts_at", START, "ends_at", END, "source", "test"),
                Map.of("resource_id", "MOS-PATH", "starts_at", START, "ends_at", END,
                        "source", "test")));
        root.put("cycleResources", List.of(Map.of("rule_set_id", RULE, "cycle_code", "IS5000",
                "resource_id", "SPB-PATH")));
        root.put("cycleRules", List.of(Map.of("rule_set_id", RULE, "code", "IS5000",
                "interval_km", 5000, "tolerance_basis_points", 2000,
                "duration_minutes", 600, "rank", 1, "source", "test")));
        root.put("serviceEvents", List.of());
        root.put("serviceCredits", List.of());
        String payload = new ObjectMapper().writeValueAsString(root);
        return new SourceSnapshot(id("snapshot"), SCENARIO, "d1-source-1.0",
                "pg-jsonb-text-v1", PlanFingerprint.sha256(payload), payload, Instant.now());
    }

    private static Map<String, Object> presence(UUID train, String city,
                                                 OffsetDateTime from, OffsetDateTime to) {
        return Map.of("train_id", train, "location", city, "starts_at", from,
                "ends_at", to, "confirmation_status", "SYNTHETIC", "source", "test line");
    }

    private static Map<String, Object> trip(int index, String from, String to,
                                             OffsetDateTime departure, OffsetDateTime arrival) {
        return Map.of("id", id("trip-" + index), "train_id", LINE,
                "label", "R" + index, "origin", from, "destination", to,
                "departure_at", departure, "arrival_at", arrival, "distance_km", 670,
                "source", "test trip");
    }

    private static UUID id(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }
}
