package com.vsm.okno.planning;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.data.SourceSnapshotE2Adapter;
import com.vsm.okno.data.SourceSnapshotE3Adapter;
import com.vsm.okno.validation.PlanFingerprint;
import com.vsm.okno.validation.E3TripAssignmentAudit;
import com.vsm.okno.validation.E3MileageObligationAudit;
import com.vsm.okno.validation.E3ReserveCoverageAssessment;
import com.vsm.okno.validation.E3CleaningCoverageAssessment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class E3TripAssignmentLedgerTest {
    private static final UUID SCENARIO = id("scenario");
    private static final UUID A = id("train-a");
    private static final UUID B = id("train-b");
    private static final UUID TRIP = id("trip");
    private static final UUID RULE_SET = id("rule-set");
    private static final OffsetDateTime START = OffsetDateTime.parse("2031-07-01T00:00:00+03:00");

    @Test
    void reassignedTripCreditsMileageToTheEffectiveTrain() {
        var source = source("SPB_DEPOT", "[]");
        var ledger = new E3TripAssignmentLedger();
        var original = ledger.evaluate(source, Map.of(), START, 55);
        var moved = ledger.evaluate(source, Map.of(TRIP, B), START, 55);
        assertEquals(1670, original.trains().get(A).finalKm());
        assertEquals(2000, original.trains().get(B).finalKm());
        assertEquals(1000, moved.trains().get(A).finalKm());
        assertEquals(2670, moved.trains().get(B).finalKm());
        assertEquals(2000, moved.trains().get(B).trips().getFirst().beforeKm());
        assertEquals(TRIP, moved.trips().getFirst().id());
        assertEquals(1, moved.changedTripCount());
        assertEquals(source.snapshotHash(), moved.snapshotHash());
        assertTrue(new E3TripAssignmentAudit().check(source, moved, START, 55).isEmpty());

        var originalTrace = moved.trains().get(B);
        Map<UUID, E3TripAssignmentLedger.TrainTrace> traces = new HashMap<>(moved.trains());
        traces.put(B, new E3TripAssignmentLedger.TrainTrace(B, originalTrace.externalId(),
                originalTrace.initialLocation(), originalTrace.finalLocation(), originalTrace.initialKm(),
                originalTrace.finalKm() + 1, originalTrace.trips()));
        var falsified = new E3TripAssignmentLedger.Assignment(moved.scenarioId(), moved.sourceSnapshotId(),
                moved.snapshotHash(), moved.frozenUntil(), moved.preparationMinutes(),
                moved.trips(), traces, moved.changedTripCount());
        assertTrue(new E3TripAssignmentAudit().check(source, falsified, START, 55).stream()
                .anyMatch(f -> f.code().equals("D2_E3_MILEAGE_TRACE")));

        var recalculator = new E3MileageObligationRecalculator();
        var before = recalculator.recalculate(source, original, START, 55);
        var after = recalculator.recalculate(source, moved, START, 55);
        assertEquals(1, before.obligations().size());
        assertEquals(A, before.obligations().getFirst().trainId());
        assertEquals(1500, before.obligations().getFirst().nominalKm());
        assertEquals(1, after.obligations().size());
        assertEquals(B, after.obligations().getFirst().trainId());
        assertEquals(2500, after.obligations().getFirst().nominalKm());
        assertEquals(B, after.effectiveTrips().getFirst().trainId());
        var milestoneAudit = new E3MileageObligationAudit();
        assertTrue(milestoneAudit.check(source, moved, after, START, 55).isEmpty());
        var omitted = new E3MileageObligationRecalculator.Recalculated(after.scenarioId(),
                after.snapshotHash(), after.effectiveTrips(), after.blocks(), java.util.List.of());
        assertTrue(milestoneAudit.check(source, moved, omitted, START, 55).stream()
                .anyMatch(f -> f.code().equals("D2_E3_REQUIRED_WORK_MISSING")));
        var block = after.blocks().getFirst();
        var unauthorized = new ScenarioSnapshot.ServiceBlock(block.id(), block.trainId(),
                block.resourceId(), block.durationMinutes(), block.earliestStartMinute(),
                block.latestEndMinute(), block.predecessorIds(), block.kind(),
                java.util.List.of("PATH-1", "PATH-NOT-ALLOWED"));
        var enlargedChoice = new E3MileageObligationRecalculator.Recalculated(after.scenarioId(),
                after.snapshotHash(), after.effectiveTrips(), java.util.List.of(unauthorized),
                after.obligations());
        assertTrue(milestoneAudit.check(source, moved, enlargedChoice, START, 55).stream()
                .anyMatch(f -> f.code().equals("D2_E3_BLOCK_CHANGED")));
    }

    @Test
    void wrongCityOccupationAndFreezeEachRejectTheCandidate() {
        var ledger = new E3TripAssignmentLedger();
        assertThrows(IllegalArgumentException.class,
                () -> ledger.evaluate(source("MOSCOW", "[]"), Map.of(TRIP, B), START, 55));
        String busy = "[{\"train_id\":\"" + B + "\",\"kind\":\"UNAVAILABLE\","
                + "\"starts_at\":\"2031-07-01T05:30:00+03:00\","
                + "\"ends_at\":\"2031-07-01T07:00:00+03:00\"}]";
        assertThrows(IllegalArgumentException.class,
                () -> ledger.evaluate(source("SPB_DEPOT", busy), Map.of(TRIP, B), START, 55));
        assertThrows(IllegalArgumentException.class,
                () -> ledger.evaluate(source("SPB_DEPOT", "[]"), Map.of(TRIP, B),
                        START.plusHours(7), 55));
    }

    @Test
    void newRequestFactsCannotDisappearFromAnE3Candidate() {
        var base = source("SPB_DEPOT", "[]");
        var proposed = new E3TripAssignmentLedger().evaluate(base, Map.of(), START, 55);
        var urgent = addFact(base, "\"urgentWorkRequirements\":[{}]");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new SourceSnapshotE3Adapter().project(urgent)).getMessage()
                .contains("urgentWorkRequirements"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new SourceSnapshotE2Adapter().project(urgent)).getMessage()
                .contains("urgentWorkRequirements"));
        assertThrows(IllegalArgumentException.class,
                () -> new E3TripAssignmentLedger().evaluate(urgent, Map.of(), START, 55));
        var falselyRetained = new E3TripAssignmentLedger.Assignment(proposed.scenarioId(),
                urgent.id(), urgent.snapshotHash(), proposed.frozenUntil(), proposed.preparationMinutes(),
                proposed.trips(), proposed.trains(), proposed.changedTripCount());
        assertTrue(new E3TripAssignmentAudit().check(urgent, falselyRetained, START, 55).stream()
                .anyMatch(f -> f.code().equals("D2_E3_URGENT_WORK_UNSUPPORTED")));

        var outage = addFact(base, "\"resourceOutages\":[{\"resource_id\":\"PATH-1\","
                + "\"starts_at\":\"2031-07-01T09:00:00+03:00\","
                + "\"ends_at\":\"2031-07-01T10:00:00+03:00\"}]");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new SourceSnapshotE2Adapter().project(outage)).getMessage()
                .contains("resourceOutages"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new SourceSnapshotE3Adapter().project(outage)).getMessage()
                .contains("overlaps outage"));
    }

    @Test
    void mobilizingDocumentedReserveIsAllowedAndCityDeficitIsVisible() {
        var base = source("SPB_DEPOT", "[]");
        String status = base.canonicalPayload().replace(
                "\"external_id\":\"B\",\"location\":\"SPB_DEPOT\",\"status\":\"AVAILABLE\"",
                "\"external_id\":\"B\",\"location\":\"SPB_DEPOT\",\"status\":\"RESERVE\"");
        var unsupported = withPayload(base, status);
        var ledger = new E3TripAssignmentLedger();
        assertThrows(IllegalArgumentException.class,
                () -> ledger.evaluate(unsupported, Map.of(TRIP, B), START, 55));
        String occupied = "\"trainOccupancy\":[{\"train_id\":\"" + B + "\","
                + "\"kind\":\"RESERVE\",\"confirmation_status\":\"SYNTHETIC\","
                + "\"starts_at\":\"2031-07-01T00:00:00+03:00\","
                + "\"ends_at\":\"2031-07-02T00:00:00+03:00\"}]";
        String present = "\"trainPresence\":[{\"train_id\":\"" + B + "\","
                + "\"location\":\"SPB_DEPOT\",\"confirmation_status\":\"SYNTHETIC\","
                + "\"starts_at\":\"2031-07-01T00:00:00+03:00\","
                + "\"ends_at\":\"2031-07-02T00:00:00+03:00\"}]";
        var documented = withPayload(base, status.replace("\"trainOccupancy\":[]", occupied)
                .replace("\"frozenWork\":[]", "\"frozenWork\":[]," + present));
        var unchanged = ledger.evaluate(documented, Map.of(), START, 55);
        var moved = ledger.evaluate(documented, Map.of(TRIP, B), START, 55);
        assertTrue(new E3TripAssignmentAudit().check(documented, moved, START, 55).isEmpty());
        var assessment = new E3ReserveCoverageAssessment();
        assertEquals(0, assessment.assess(documented, unchanged, START, 55).mobilizedTrainCount());
        var report = assessment.assess(documented, moved, START, 55);
        assertEquals(1, report.mobilizedTrainCount());
        assertEquals(new E3ReserveCoverageAssessment.City("SPB_DEPOT", 1, 0, 1),
                report.cities().getFirst());
    }

    @Test
    void reassignmentMakesOldTrainCleaningSlotInsufficientForTheNewTrain() {
        var base = source("SPB_DEPOT", "[]");
        UUID second = id("trip-two");
        String trip = "{\"id\":\"" + second + "\",\"train_id\":\"" + A + "\","
                + "\"label\":\"R2\",\"origin\":\"MOSCOW\",\"destination\":\"SPB_DEPOT\","
                + "\"departure_at\":\"2031-07-01T12:00:00+03:00\","
                + "\"arrival_at\":\"2031-07-01T14:00:00+03:00\",\"distance_km\":670}";
        String counters = "\"cleaningCounters\":["
                + "{\"train_id\":\"" + A + "\",\"observed_at\":\"2031-07-01T00:00:00+03:00\","
                + "\"completed_trips_since_cleaning\":3,\"confirmation_status\":\"SYNTHETIC\"},"
                + "{\"train_id\":\"" + B + "\",\"observed_at\":\"2031-07-01T00:00:00+03:00\","
                + "\"completed_trips_since_cleaning\":3,\"confirmation_status\":\"SYNTHETIC\"}]";
        var turns = addFact(withPayload(base, base.canonicalPayload().replace(
                "\"distance_km\":670}]", "\"distance_km\":670}," + trip + "]")), counters);
        var ledger = new E3TripAssignmentLedger();
        var original = ledger.evaluate(turns, Map.of(), START, 55);
        var audit = new E3CleaningCoverageAssessment();
        assertEquals(1, audit.assess(turns, original, START, 55).missing().size());
        String cleaning = "\"trainOccupancy\":[{\"train_id\":\"" + A + "\","
                + "\"kind\":\"CLEANING\",\"confirmation_status\":\"SYNTHETIC\","
                + "\"starts_at\":\"2031-07-01T08:30:00+03:00\","
                + "\"ends_at\":\"2031-07-01T10:30:00+03:00\"}]";
        var fixedCleaning = withPayload(turns, turns.canonicalPayload().replace(
                "\"trainOccupancy\":[]", cleaning));
        assertEquals(1, audit.assess(fixedCleaning,
                ledger.evaluate(fixedCleaning, Map.of(), START, 55), START, 55).coveredCount());
        var moved = ledger.evaluate(fixedCleaning, Map.of(TRIP, B, second, B), START, 55);
        assertTrue(new E3TripAssignmentAudit().check(fixedCleaning, moved, START, 55).isEmpty());
        var after = audit.assess(fixedCleaning, moved, START, 55);
        assertEquals(0, after.coveredCount());
        assertEquals(B, after.missing().getFirst().trainId());
    }

    private static SourceSnapshot addFact(SourceSnapshot original, String property) {
        String payload = original.canonicalPayload().stripTrailing();
        payload = payload.substring(0, payload.length() - 1) + "," + property + "}";
        return withPayload(original, payload);
    }

    private static SourceSnapshot withPayload(SourceSnapshot original, String payload) {
        return new SourceSnapshot(original.id(), original.scenarioId(), original.schemaVersion(),
                original.canonicalization(), PlanFingerprint.sha256(payload), payload,
                original.capturedAt());
    }

    private static SourceSnapshot source(String cityB, String occupancy) {
        String payload = """
                {"schemaVersion":"d1-source-1.0","canonicalization":"pg-jsonb-text-v1",
                "scenarioId":"%s","scenario":{"id":"%s","rule_set_id":"%s",
                "provenance":"synthetic reassignment test","horizon_start":"2031-07-01T00:00:00+03:00",
                "horizon_end":"2031-07-02T00:00:00+03:00"},"trains":[
                {"id":"%s","external_id":"A","location":"SPB_DEPOT","status":"AVAILABLE"},
                {"id":"%s","external_id":"B","location":"%s","status":"AVAILABLE"}],
                "odometerReadings":[
                {"train_id":"%s","observed_at":"2031-07-01T00:00:00+03:00","odometer_km":1000},
                {"train_id":"%s","observed_at":"2031-07-01T00:00:00+03:00","odometer_km":2000}],
                "fixedTrips":[{"id":"%s","train_id":"%s","label":"R1","origin":"SPB_DEPOT",
                "destination":"MOSCOW","departure_at":"2031-07-01T06:00:00+03:00",
                "arrival_at":"2031-07-01T08:00:00+03:00","distance_km":670}],
                "trainOccupancy":%s,"frozenWork":[],
                "ruleSets":[{"id":"%s","confirmation_status":"SYNTHETIC",
                "mileage_policy":"ABSOLUTE_GRID","tolerance_basis":"NOMINAL_MILESTONE"}],
                "resources":[{"id":"PATH-1","location":"SPB_DEPOT"}],
                "resourceAvailability":[{"resource_id":"PATH-1",
                "starts_at":"2031-07-01T00:00:00+03:00",
                "ends_at":"2031-07-02T00:00:00+03:00"}],
                "cycleResources":[{"rule_set_id":"%s","cycle_code":"IS100","resource_id":"PATH-1"}],
                "cycleRules":[{"rule_set_id":"%s","code":"IS100","interval_km":500,
                "tolerance_basis_points":2000,"duration_minutes":120,"rank":1,"source":"synthetic rule"}],
                "cycleBaselines":[
                {"rule_set_id":"%s","train_id":"%s","cycle_code":"IS100",
                "credited_nominal_km":1000,"recorded_at":"2031-06-30T00:00:00+03:00"},
                {"rule_set_id":"%s","train_id":"%s","cycle_code":"IS100",
                "credited_nominal_km":2000,"recorded_at":"2031-06-30T00:00:00+03:00"}],
                "serviceEvents":[],"serviceCredits":[]}
                """.formatted(SCENARIO, SCENARIO, RULE_SET, A, B, cityB, A, B, TRIP, A,
                occupancy, RULE_SET, RULE_SET, RULE_SET, RULE_SET, A, RULE_SET, B);
        return new SourceSnapshot(id("snapshot"), SCENARIO, "d1-source-1.0", "pg-jsonb-text-v1",
                PlanFingerprint.sha256(payload), payload, Instant.parse("2031-06-30T21:00:00Z"));
    }

    private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes()); }
}
