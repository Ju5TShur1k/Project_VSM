package com.vsm.okno.validation;

import com.vsm.okno.data.SourceSnapshotRepository.SourceSnapshot;
import com.vsm.okno.planning.OperationalConstraints;
import com.vsm.okno.planning.PlannerRequest;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.ScenarioSnapshot;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class SourcePlanAuditTest {
    private static final UUID SCENARIO = new UUID(0, 1), TRAIN = new UUID(0, 2), RULES = new UUID(0, 3), TRIP = new UUID(0, 4);
    private static final OffsetDateTime START = OffsetDateTime.parse("2031-07-01T00:00:00Z");
    private static final UUID BLOCK = UUID.nameUUIDFromBytes((SCENARIO + ":" + TRAIN + ":25000:IS200").getBytes(StandardCharsets.UTF_8));
    private static final ObjectMapper JSON = new ObjectMapper();
    private record Fixture(SourceSnapshot saved, ScenarioSnapshot prepared, PlannerResult result) {}

    @Test void acceptsHandPlacedCaseDurationAndPublishesHonestMetricsAndPendingMilestones() {
        var fixture = fixture(root -> {});
        var report = audit(fixture);
        assertEquals("PASS", report.status(), report.findings().toString());
        assertEquals("E2_MODEL", report.scope());
        assertEquals(1, report.requiredServiceCount());
        assertEquals(2, report.pendingMilestones().size());
        assertEquals(PlanFingerprint.result(fixture.result()), report.resultHash());
        var metrics = PlanMetrics.calculate(fixture.prepared(), fixture.result(), report);
        assertEquals(240, metrics.trainServiceMinutes());
        assertEquals(1, metrics.scheduledTripCount());
        assertEquals(0, metrics.conflictingTripCount());
        assertEquals(20.0, metrics.resourceLoads().getFirst().horizonSharePercent());
    }

    @Test void detectsCommonModeOmissionEvenWhenPreparedProjectionAndResultBothContainNoWork() {
        var f = fixture(root -> {});
        var p = copy(f.prepared(), List.of(), f.prepared().fixedTrips());
        var report = new SourcePlanAudit().report(f.saved(), p, result(p, List.of()));
        assertCode(report, "D2_PROJECTION_MISSING_WORK");
        assertCode(report, "D2_REQUIRED_WORK_MISSING");
    }

    @Test void detectsShortenedWorkWithObjectAndInterval() {
        var f = fixture(root -> {});
        var bad = new PlannerResult.PlannedBlock(BLOCK, TRAIN, "SPB-PATH", START, START.plusMinutes(239));
        var report = new SourcePlanAudit().report(f.saved(), f.prepared(), result(f.prepared(), List.of(bad)));
        assertCode(report, "D2_WORK_DURATION");
        var issue = report.findings().stream().filter(v -> v.code().equals("D2_WORK_DURATION")).findFirst().orElseThrow();
        assertEquals(BLOCK.toString(), issue.objectId()); assertNotNull(issue.startAt()); assertNotNull(issue.endAt());
        assertNull(PlanMetrics.calculate(f.prepared(), result(f.prepared(), List.of(bad)), report));
    }

    @Test void rejectsChangedSourceBytesAndAnotherSourceHash() {
        var f = fixture(root -> {});
        var corrupt = new SourceSnapshot(f.saved().id(), SCENARIO, "d1-source-1.0", "pg-jsonb-text-v1",
                f.saved().snapshotHash(), f.saved().canonicalPayload() + " ", Instant.now());
        assertCode(new SourcePlanAudit().report(corrupt, f.prepared(), f.result()), "D2_SOURCE_HASH");
        var foreign = new SourceSnapshot(f.saved().id(), SCENARIO, "d1-source-1.0", "pg-jsonb-text-v1",
                "bad", f.saved().canonicalPayload(), Instant.now());
        assertCode(new SourcePlanAudit().report(foreign, f.prepared(), f.result()), "D2_SOURCE_MISMATCH");
    }

    @Test void missingHistoryCannotBecomeAnImplicitZeroCredit() {
        assertCode(audit(fixture(root -> ((ArrayNode)root.get("cycleBaselines")).remove(0))), "D2_CREDIT_MISSING_OR_INVALID");
    }

    @Test void unconfirmedRulesAndUnknownSourceFieldsAreBlocking() {
        assertCode(audit(fixture(root -> ((ObjectNode)root.get("ruleSets").get(0)).put("confirmation_status", "UNCONFIRMED"))), "D2_UNCONFIRMED_RULES");
        assertCode(audit(fixture(root -> root.putArray("calendarObligations"))), "D2_UNSUPPORTED_SOURCE_FIELD");
        assertCode(audit(fixture(root -> root.remove("cleaningCounters"))), "D2_SOURCE_INCOMPLETE");
    }

    @Test void rawResourceCalendarAndCityOverrideForgedPreparedWindows() {
        assertCode(audit(fixture(root -> ((ArrayNode)root.get("resourceAvailability")).removeAll())), "D2_SOURCE_RESOURCE_WINDOW");
        assertCode(audit(fixture(root -> ((ObjectNode)root.get("resources").get(0)).put("location", "MSK"))), "D2_LOCATION");
        assertCode(audit(fixture(root -> ((ArrayNode)root.get("trainPresence")).removeAll())), "D2_SOURCE_PRESENCE");
    }

    @Test void anUnavailableOrReserveTrainCannotBeApprovedByTheE2Scope() {
        assertCode(audit(fixture(root -> ((ObjectNode)root.get("trains").get(0)).put("status", "RESERVE"))), "D2_TRAIN_STATE_UNSUPPORTED");
    }

    @Test void rawCleaningAndFrozenFactsCannotBeSilentlyDropped() {
        assertCode(audit(fixture(root -> ((ArrayNode)root.get("cleaningCounters")).addObject().put("source", "synthetic"))), "D2_E3_SOURCE_UNSUPPORTED");
        assertCode(audit(fixture(root -> ((ArrayNode)root.get("frozenWork")).addObject().put("source", "synthetic"))), "D2_E3_SOURCE_UNSUPPORTED");
    }

    @Test void checksReleaseMileageDirectlyWithoutTheGeneratorWindow() {
        var f = fixture(root -> {
            ((ObjectNode)root.get("odometerReadings").get(0)).put("odometer_km", 19000);
            var trip = (ObjectNode)root.get("fixedTrips").get(0);
            trip.put("distance_km", 6000); trip.put("destination", "SPB");
        });
        assertCode(audit(f), "D2_MILEAGE_TOO_EARLY");
    }

    @Test void cannotMoveATripInThePreparedProjection() {
        var f = fixture(root -> {}); var trip = f.prepared().fixedTrips().getFirst();
        var changed = new ScenarioSnapshot.FixedTrip(trip.id(), trip.trainId(), trip.label(), trip.startMinute()+1,
                trip.endMinute()+1, trip.distanceKm(), trip.source());
        var p = copy(f.prepared(), f.prepared().blocks(), List.of(changed));
        assertCode(new SourcePlanAudit().report(f.saved(), p, result(p, f.result().blocks())), "D2_FIXED_TRIP_CHANGED");
    }

    @Test void sourceDetectsTwoOverlappingTripsEvenWhenProjectionDropsOneOfThem() {
        var f = fixture(root -> {
            var extra = (ObjectNode) root.get("fixedTrips").get(0).deepCopy();
            extra.put("id", new UUID(0, 11).toString()).put("label", "T2")
                    .put("departure_at", "2031-07-01T06:00:00Z").put("arrival_at", "2031-07-01T08:00:00Z")
                    .put("origin", "MSK").put("destination", "SPB");
            ((ArrayNode) root.get("fixedTrips")).add(extra);
        });
        assertCode(audit(f), "D2_SOURCE_TRIP_OVERLAP");
    }

    @Test void workMustFinishBeforeTheTripWhichWouldExceedItsMileageLimit() {
        var f = fixture(root -> {
            ((ObjectNode) root.get("fixedTrips").get(0)).put("distance_km", 4000);
        });
        var late = new PlannerResult.PlannedBlock(BLOCK, TRAIN, "SPB-PATH", START.plusMinutes(100), START.plusMinutes(340));
        assertCode(new SourcePlanAudit().report(f.saved(), f.prepared(), result(f.prepared(), List.of(late))), "D2_MILEAGE_DEADLINE");
    }

    @Test void juniorServiceCannotForgeCreditForASeniorCycle() {
        var f = fixture(root -> {
            ((ArrayNode)root.get("serviceEvents")).addObject().put("id", new UUID(0,5).toString()).put("train_id", TRAIN.toString())
                    .put("rule_set_id", RULES.toString()).put("performed_cycle_code", "IS100")
                    .put("completed_at", "2031-06-30T00:00:00Z").put("accepted_at", "2031-06-30T00:00:00Z")
                    .put("actual_odometer_km", 24000).put("source", "synthetic");
            ((ArrayNode)root.get("serviceCredits")).addObject().put("service_event_id", new UUID(0,5).toString())
                    .put("rule_set_id", RULES.toString()).put("covered_cycle_code", "IS200")
                    .put("credited_nominal_km", 25000).put("source", "synthetic");
        });
        assertCode(audit(f), "D2_HISTORY_CREDIT_INVALID");
    }

    @Test void infeasibleAndUnknownNeverYieldPassOrMetrics() {
        var f = fixture(root -> {});
        for (var status : List.of(PlannerResult.SolverStatus.INFEASIBLE, PlannerResult.SolverStatus.UNKNOWN)) {
            var result = new PlannerResult("1.0", SCENARIO, f.saved().snapshotHash(), PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT,
                    status, List.of(), List.of(), 42, 1, null);
            var report = new SourcePlanAudit().report(f.saved(), f.prepared(), result);
            assertCode(report, "NO_FEASIBLE_RESULT");
            assertNull(PlanMetrics.calculate(f.prepared(), result, report));
        }
    }

    @Test void reviewFingerprintIsStableAcrossOffsetsAndEventOrderButDetectsChangedContent() {
        var f = fixture(root -> {}); var b = f.result().blocks().getFirst();
        var shifted = new PlannerResult.PlannedBlock(b.blockId(), b.trainId(), b.resourceId(),
                b.startAt().withOffsetSameInstant(java.time.ZoneOffset.ofHours(3)), b.endAt().withOffsetSameInstant(java.time.ZoneOffset.ofHours(3)));
        assertEquals(PlanFingerprint.result(f.result()), PlanFingerprint.result(result(f.prepared(), List.of(shifted))));
        var moved = new PlannerResult.PlannedBlock(b.blockId(), b.trainId(), b.resourceId(), b.startAt().plusMinutes(1), b.endAt().plusMinutes(1));
        assertNotEquals(PlanFingerprint.result(f.result()), PlanFingerprint.result(result(f.prepared(), List.of(moved))));
    }

    private static Fixture fixture(Consumer<ObjectNode> change) {
        ObjectNode root = (ObjectNode) JSON.readTree("""
                {"schemaVersion":"d1-source-1.0","canonicalization":"pg-jsonb-text-v1","scenarioId":"00000000-0000-0000-0000-000000000001",
                 "scenario":{"id":"00000000-0000-0000-0000-000000000001","rule_set_id":"00000000-0000-0000-0000-000000000003","horizon_start":"2031-07-01T00:00:00Z","horizon_end":"2031-07-01T20:00:00Z"},
                 "ruleSets":[{"id":"00000000-0000-0000-0000-000000000003","version":"audit-model","source":"synthetic","confirmation_status":"SYNTHETIC","mileage_policy":"ABSOLUTE_GRID","tolerance_basis":"NOMINAL_MILESTONE"}],
                 "cycleRules":[
                   {"rule_set_id":"00000000-0000-0000-0000-000000000003","code":"IS100","interval_km":12500,"tolerance_basis_points":1000,"duration_minutes":120,"rank":1,"source":"case"},
                   {"rule_set_id":"00000000-0000-0000-0000-000000000003","code":"IS200","interval_km":25000,"tolerance_basis_points":2000,"duration_minutes":240,"rank":2,"source":"case"}],
                 "trains":[{"id":"00000000-0000-0000-0000-000000000002","external_id":"TEST-01","status":"AVAILABLE","location":"SPB","source":"synthetic"}],
                 "odometerReadings":[{"train_id":"00000000-0000-0000-0000-000000000002","observed_at":"2031-07-01T00:00:00Z","odometer_km":24000,"source":"synthetic"}],
                 "resources":[{"id":"SPB-PATH","location":"SPB","source":"synthetic"}],
                 "resourceAvailability":[{"id":"00000000-0000-0000-0000-000000000006","resource_id":"SPB-PATH","starts_at":"2031-07-01T00:00:00Z","ends_at":"2031-07-01T20:00:00Z","source":"synthetic"}],
                 "cycleResources":[{"rule_set_id":"00000000-0000-0000-0000-000000000003","cycle_code":"IS100","resource_id":"SPB-PATH","source":"synthetic"},{"rule_set_id":"00000000-0000-0000-0000-000000000003","cycle_code":"IS200","resource_id":"SPB-PATH","source":"synthetic"}],
                 "cycleBaselines":[{"train_id":"00000000-0000-0000-0000-000000000002","rule_set_id":"00000000-0000-0000-0000-000000000003","cycle_code":"IS100","credited_nominal_km":12500,"recorded_at":"2031-06-01T00:00:00Z","source":"synthetic"},{"train_id":"00000000-0000-0000-0000-000000000002","rule_set_id":"00000000-0000-0000-0000-000000000003","cycle_code":"IS200","credited_nominal_km":0,"recorded_at":"2031-06-01T00:00:00Z","source":"synthetic"}],
                 "fixedTrips":[{"id":"00000000-0000-0000-0000-000000000004","train_id":"00000000-0000-0000-0000-000000000002","label":"T1","departure_at":"2031-07-01T05:00:00Z","arrival_at":"2031-07-01T07:00:00Z","distance_km":1000,"origin":"SPB","destination":"MSK","source":"synthetic"}],
                 "serviceEvents":[],"serviceCredits":[],"trainOccupancy":[],"cleaningCounters":[],"frozenWork":[],
                 "trainPresence":[{"id":"00000000-0000-0000-0000-000000000007","train_id":"00000000-0000-0000-0000-000000000002","location":"SPB","starts_at":"2031-07-01T00:00:00Z","ends_at":"2031-07-01T05:00:00Z","confirmation_status":"SYNTHETIC","source":"synthetic"}]}
                """);
        change.accept(root); String payload = JSON.writeValueAsString(root); String hash = PlanFingerprint.sha256(payload);
        var saved = new SourceSnapshot(new UUID(0,10),SCENARIO,"d1-source-1.0","pg-jsonb-text-v1",hash,payload,Instant.now());
        long distance = root.get("fixedTrips").get(0).get("distance_km").asLong();
        var trips = List.of(new ScenarioSnapshot.FixedTrip(TRIP,TRAIN,"T1",300,420,distance,"synthetic"));
        var blocks = List.of(new ScenarioSnapshot.ServiceBlock(BLOCK,TRAIN,"SPB-PATH",240,0,1200,List.of()));
        var operations = new OperationalConstraints(Set.of(),List.of(),List.of(new OperationalConstraints.ServiceWindow(TRAIN,"SPB-PATH",0,300,"synthetic")),List.of());
        var prepared = new ScenarioSnapshot("1.2",SCENARIO,hash,"synthetic",START,START.plusMinutes(1200),
                List.of(new ScenarioSnapshot.Train(TRAIN,"TEST-01")),List.of(new ScenarioSnapshot.Resource("SPB-PATH")),blocks,trips,operations);
        return new Fixture(saved,prepared,result(prepared,List.of(new PlannerResult.PlannedBlock(BLOCK,TRAIN,"SPB-PATH",START,START.plusMinutes(240)))));
    }
    private static ScenarioSnapshot copy(ScenarioSnapshot p,List<ScenarioSnapshot.ServiceBlock> blocks,List<ScenarioSnapshot.FixedTrip> trips) {
        return new ScenarioSnapshot(p.schemaVersion(),p.scenarioId(),p.snapshotHash(),p.provenance(),p.horizonStart(),p.horizonEnd(),p.trains(),p.resources(),blocks,trips,p.operations());
    }
    private static PlannerResult result(ScenarioSnapshot p,List<PlannerResult.PlannedBlock> blocks) {
        return new PlannerResult("1.0",p.scenarioId(),p.snapshotHash(),PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT,PlannerResult.SolverStatus.FEASIBLE,blocks,List.of(),42,1,240.0);
    }
    private static ValidationReport audit(Fixture f) { return new SourcePlanAudit().report(f.saved(),f.prepared(),f.result()); }
    private static void assertCode(ValidationReport report,String code) {
        assertEquals("FAILED",report.status(),report.findings().toString());
        assertTrue(report.findings().stream().anyMatch(v -> v.code().equals(code)),report.findings().toString());
    }
}
