package com.vsm.okno.planning;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.validation.PlanFingerprint;
import com.vsm.okno.validation.E3TripAssignmentAudit;
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

    private static SourceSnapshot source(String cityB, String occupancy) {
        String payload = """
                {"schemaVersion":"d1-source-1.0","canonicalization":"pg-jsonb-text-v1",
                "scenarioId":"%s","scenario":{"id":"%s","horizon_start":"2031-07-01T00:00:00+03:00",
                "horizon_end":"2031-07-02T00:00:00+03:00"},"trains":[
                {"id":"%s","external_id":"A","location":"SPB_DEPOT","status":"AVAILABLE"},
                {"id":"%s","external_id":"B","location":"%s","status":"AVAILABLE"}],
                "odometerReadings":[
                {"train_id":"%s","observed_at":"2031-07-01T00:00:00+03:00","odometer_km":1000},
                {"train_id":"%s","observed_at":"2031-07-01T00:00:00+03:00","odometer_km":2000}],
                "fixedTrips":[{"id":"%s","train_id":"%s","label":"R1","origin":"SPB_DEPOT",
                "destination":"MOSCOW","departure_at":"2031-07-01T06:00:00+03:00",
                "arrival_at":"2031-07-01T08:00:00+03:00","distance_km":670}],
                "trainOccupancy":%s,"frozenWork":[]}
                """.formatted(SCENARIO, SCENARIO, A, B, cityB, A, B, TRIP, A, occupancy);
        return new SourceSnapshot(id("snapshot"), SCENARIO, "d1-source-1.0", "pg-jsonb-text-v1",
                PlanFingerprint.sha256(payload), payload, Instant.parse("2031-06-30T21:00:00Z"));
    }

    private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes()); }
}
