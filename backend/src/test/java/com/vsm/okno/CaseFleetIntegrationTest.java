package com.vsm.okno;

import com.vsm.okno.data.CaseDatasetService;
import com.vsm.okno.data.SourceSnapshotE2Adapter;
import com.vsm.okno.data.SourceSnapshotE3Adapter;
import com.vsm.okno.data.SourceSnapshotRepository;
import com.vsm.okno.planning.CpSatPlanner;
import com.vsm.okno.planning.E3FeasibilityAudit;
import com.vsm.okno.planning.E3TripAssignmentLedger;
import com.vsm.okno.planning.E3MileageObligationRecalculator;
import com.vsm.okno.planning.PlannerRequest;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.OperationalConstraints;
import com.vsm.okno.planning.ScenarioSnapshot;
import com.vsm.okno.validation.E3SourcePlanAudit;
import com.vsm.okno.validation.E3TripAssignmentAudit;
import com.vsm.okno.validation.E3MileageObligationAudit;
import com.vsm.okno.validation.IndependentIntervalAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Dedicated test DB only: persisted model fleet, solver, raw-source cross-check and HTTP approval gate. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("database")
@WithMockUser(username = "tester", roles = "PLANNER")
@EnabledIfEnvironmentVariable(named = "D1_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class CaseFleetIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("D1_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("D1_TEST_DB_USER", "okno"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("D1_TEST_DB_PASSWORD", "okno"));
    }
    @Autowired CaseDatasetService datasets;
    @Autowired SourceSnapshotRepository snapshots;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test
    @Transactional
    void fullFleetPreservesCaseNormsPairTripsAndExplicitE3Facts() {
        var first = datasets.load(CaseDatasetService.Dataset.FULL43).response();
        UUID id = first.source().scenarioId();
        assertEquals(43, first.trainCount());
        assertEquals(1428, first.tripCount());
        assertFalse(first.planningSupported());
        assertEquals(4, count("train", id, "and status='RESERVE'"));
        assertEquals(5, count("train", id, "and status='MAINTENANCE'"));
        assertEquals(43, count("cleaning_counter", id, ""));
        assertEquals(5, count("frozen_work", id, ""));
        assertEquals(340, count("train_occupancy", id, "and kind='CLEANING'"));
        assertEquals(List.of(120,240,600,960,2160,3360,23040,34500), jdbc.queryForList(
                "select duration_minutes from vsm.cycle_rule where rule_set_id=(select rule_set_id from vsm.scenario where id=?) order by rank",
                Integer.class, id));
        assertEquals(0, jdbc.queryForObject("""
                select count(*) from (select label,departure_at,arrival_at,origin,destination,count(*) n
                from vsm.fixed_trip where scenario_id=? group by 1,2,3,4,5) paired where n<>2
                """, Integer.class, id));
        assertEquals(0, count("fixed_trip", id, "and distance_km<>670"));
        var second = datasets.load(CaseDatasetService.Dataset.FULL43).response();
        assertEquals(first.source().snapshotId(), second.source().snapshotId());
        assertEquals(first.source().snapshotHash(), second.source().snapshotHash());
        // An older source reading must not duplicate the train or replace its current starting mileage.
        jdbc.update("""
                insert into vsm.odometer_reading(scenario_id,train_id,observed_at,odometer_km,source)
                select scenario_id,train_id,observed_at-interval '1 day',greatest(0,odometer_km-670),'MODELLED history test'
                from vsm.odometer_reading where scenario_id=? and train_id=(
                    select id from vsm.train where scenario_id=? and external_id='CASE-01')
                """,id,id);
        var withHistory=datasets.load(CaseDatasetService.Dataset.FULL43);
        assertEquals(43,withHistory.response().trainCount());
        assertEquals(1000,withHistory.trains().stream().filter(t -> t.externalId().equals("CASE-01"))
                .findFirst().orElseThrow().mileageKm());
        assertThrows(IllegalArgumentException.class, () -> new SourceSnapshotE2Adapter().project(
                snapshots.findById(first.source().snapshotId()).orElseThrow()));
    }

    @Test
    @Transactional
    void fullFleetProjectionCarriesTripsPathsCleaningFrozenWorkAndReserve() throws Exception {
        var loaded = datasets.load(CaseDatasetService.Dataset.FULL43).response();
        var saved = snapshots.findById(loaded.source().snapshotId()).orElseThrow();
        var projection = new SourceSnapshotE3Adapter().project(saved);
        var source = projection.snapshot();
        assertEquals("1.4", source.schemaVersion());
        assertEquals(43, source.trains().size());
        assertEquals(1428, source.fixedTrips().size());
        assertEquals(4, source.operations().protectedReserveTrainIds().size());
        assertEquals(350, source.operations().fixedOccupancies().size());
        assertTrue(source.blocks().stream().allMatch(b -> b.allowedResourceIds().size() == 5));
        assertTrue(source.blocks().stream().anyMatch(b -> b.durationMinutes() >= 10 * 60));
        assertTrue(source.operations().serviceWindows().size() > 1000);
        // The modelled turns leave no 10+ hour maintenance gap for some trains.
        // The correct result requires trip reassignment; a source adapter alone
        // must not label FULL43 as a feasible approved plan.
        assertTrue(E3FeasibilityAudit.noContiguousWindows(source, projection.obligations()).stream()
                .anyMatch(d -> d.code().equals("E3_NO_CONTIGUOUS_SERVICE_WINDOW")));
        String assessment = mvc.perform(get("/api/v1/source-snapshots/{id}/e3-assessment",
                        saved.id())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(assessment.contains("\"snapshotHash\":\"" + saved.snapshotHash() + "\""));
        assertTrue(assessment.contains("\"fixedAssignmentStatus\":\"BLOCKED_BY_FIXED_ASSIGNMENTS\""));
        assertTrue(assessment.contains("\"d2Status\":\"NOT_PERFORMED\""));
        assertTrue(assessment.contains("\"eligibleTrainCount\":34"));
        assertTrue(assessment.contains("\"peakConcurrentTrips\":34"));
        assertTrue(assessment.contains("\"unassignedEligibleTrainCount\":0"));
        var result = new PlannerResult("1.0", source.scenarioId(), source.snapshotHash(),
                PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT, PlannerResult.SolverStatus.INFEASIBLE,
                List.of(), List.of(), 1, 0, null);
        var sourceReport = new E3SourcePlanAudit().report(saved, source, result);
        assertEquals("FAILED", sourceReport.status());
        assertTrue(sourceReport.findings().stream().noneMatch(f -> f.code().startsWith("D2_E3_")));

        var missingTrip = new ScenarioSnapshot(source.schemaVersion(), source.scenarioId(),
                source.snapshotHash(), source.provenance(), source.horizonStart(), source.horizonEnd(),
                source.trains(), source.resources(), source.blocks(), source.fixedTrips().subList(1,
                source.fixedTrips().size()), source.operations());
        assertTrue(new E3SourcePlanAudit().report(saved, missingTrip, result).findings().stream()
                .anyMatch(f -> f.code().equals("D2_E3_TRIP_SET")));

        var facts = source.operations();
        var missingOccupancy = new OperationalConstraints(facts.protectedReserveTrainIds(),
                facts.fixedOccupancies().subList(1, facts.fixedOccupancies().size()),
                facts.serviceWindows(), facts.frozenPlacements(), facts.releaseRequirements(), facts.hotReserve());
        var altered = new ScenarioSnapshot(source.schemaVersion(), source.scenarioId(),
                source.snapshotHash(), source.provenance(), source.horizonStart(), source.horizonEnd(),
                source.trains(), source.resources(), source.blocks(), source.fixedTrips(), missingOccupancy);
        assertTrue(new E3SourcePlanAudit().report(saved, altered, result).findings().stream()
                .anyMatch(f -> f.code().equals("D2_E3_OCCUPANCY_SET")));

        var e2 = datasets.load(CaseDatasetService.Dataset.E2_6).response();
        mvc.perform(get("/api/v1/source-snapshots/{id}/e3-assessment", e2.source().snapshotId()))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    @Transactional
    void fullFleetTripReassignmentPreservesTripIdsAndRejectsUnreleasedDepotTrains() {
        var loaded = datasets.load(CaseDatasetService.Dataset.FULL43).response();
        UUID scenario = loaded.source().scenarioId();
        var saved = snapshots.findById(loaded.source().snapshotId()).orElseThrow();
        UUID train3 = jdbc.queryForObject("select id from vsm.train where scenario_id=? and external_id='CASE-03'",
                UUID.class, scenario);
        UUID train4 = jdbc.queryForObject("select id from vsm.train where scenario_id=? and external_id='CASE-04'",
                UUID.class, scenario);
        UUID depotTrain = jdbc.queryForObject("select id from vsm.train where scenario_id=? and external_id='CASE-35'",
                UUID.class, scenario);
        var start = java.time.OffsetDateTime.parse("2031-07-01T00:00:00+03:00");
        var cutoff = start.plusDays(1);
        var ledger = new E3TripAssignmentLedger();
        var original = ledger.evaluate(saved, Map.of(), start, 55);
        assertEquals(1428, original.trips().size());
        assertEquals(0, original.changedTripCount());
        List<UUID> three = jdbc.queryForList("select id from vsm.fixed_trip where scenario_id=? and train_id=? "
                        + "and departure_at>=? order by departure_at", UUID.class,
                scenario, train3, java.sql.Timestamp.from(cutoff.toInstant()));
        List<UUID> four = jdbc.queryForList("select id from vsm.fixed_trip where scenario_id=? and train_id=? "
                        + "and departure_at>=? order by departure_at", UUID.class,
                scenario, train4, java.sql.Timestamp.from(cutoff.toInstant()));
        assertEquals(39, three.size());
        assertEquals(39, four.size());
        Map<UUID, UUID> exchanged = new HashMap<>();
        three.forEach(id -> exchanged.put(id, train4));
        four.forEach(id -> exchanged.put(id, train3));
        var proposed = ledger.evaluate(saved, exchanged, cutoff, 55);
        assertEquals(78, proposed.changedTripCount());
        assertEquals(1428, proposed.trips().size());
        assertEquals(original.snapshotHash(), proposed.snapshotHash());
        assertEquals(original.trains().get(train3).finalKm(), proposed.trains().get(train3).finalKm());
        assertTrue(proposed.trains().get(train4).trips().stream()
                .anyMatch(t -> t.tripId().equals(three.getFirst())));
        assertTrue(new E3TripAssignmentAudit().check(saved, proposed, cutoff, 55).isEmpty());
        var recalculated = new E3MileageObligationRecalculator().recalculate(saved, proposed, cutoff, 55);
        assertEquals(1428, recalculated.effectiveTrips().size());
        assertTrue(recalculated.obligations().size() > 0);
        assertTrue(recalculated.blocks().stream().allMatch(block ->
                block.kind() == ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE
                        && block.allowedResourceIds().size() == 5));
        assertTrue(new E3MileageObligationAudit().check(saved, proposed, recalculated, cutoff, 55).isEmpty());
        assertEquals(train4, recalculated.effectiveTrips().stream()
                .filter(t -> t.id().equals(three.getFirst())).findFirst().orElseThrow().trainId());
        assertThrows(IllegalArgumentException.class,
                () -> ledger.evaluate(saved, Map.of(three.getFirst(), depotTrain), cutoff, 55));
    }

    @Test
    @Transactional
    void sixTrainPlanMatchesIndependentRawSourceMilestonesAndIntervals() {
        var loaded = datasets.load(CaseDatasetService.Dataset.E2_6).response();
        assertEquals(252, loaded.tripCount());
        var projection = new SourceSnapshotE2Adapter().project(snapshots.findById(
                loaded.source().snapshotId()).orElseThrow());
        UUID id = loaded.source().scenarioId();
        // Independent SQL reconstruction from stored source, without generator/solver helpers.
        // This checks the E2 absolute-grid policy; it is NOT a complete release validator.
        var expected = new HashSet<>(jdbc.queryForList("""
                with credits as (
                    select train_id,cycle_code,max(credited_nominal_km) km from (
                        select train_id,cycle_code,credited_nominal_km from vsm.cycle_baseline where scenario_id=?
                        union all select e.train_id,c.covered_cycle_code,c.credited_nominal_km
                        from vsm.service_credit c join vsm.service_event e
                        on (c.scenario_id,c.service_event_id)=(e.scenario_id,e.id) where e.scenario_id=?
                    ) h group by train_id,cycle_code
                ), totals as (
                    select o.train_id,o.odometer_km+coalesce(sum(t.distance_km),0)::bigint km
                    from vsm.odometer_reading o left join vsm.fixed_trip t
                    on (o.scenario_id,o.train_id)=(t.scenario_id,t.train_id) where o.scenario_id=?
                    group by o.train_id,o.odometer_km
                ), milestones as (
                    select c.train_id,r.code,r.rank,g.km from credits c join totals t using(train_id)
                    join vsm.cycle_rule r on r.code=c.cycle_code
                    and r.rule_set_id=(select rule_set_id from vsm.scenario where id=?)
                    cross join lateral generate_series(c.km+r.interval_km,t.km,r.interval_km) g(km)
                ) select train_id::text || ':' || km || ':' || code from (
                    select *,row_number() over(partition by train_id,km order by rank desc) senior from milestones
                ) selected where senior=1
                """, String.class, id,id,id,id));
        var actual = projection.obligations().stream().map(o -> o.trainId()+":"+o.nominalKm()+":"+o.cycleCode())
                .collect(Collectors.toSet());
        assertEquals(expected, actual);
        assertTrue(actual.size() > 10);
        var source = projection.snapshot();
        var result = new CpSatPlanner().plan(source, new PlannerRequest("1.0",id,source.snapshotHash(),
                PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT,42,10));
        assertTrue(result.solverStatus()==PlannerResult.SolverStatus.OPTIMAL
                || result.solverStatus()==PlannerResult.SolverStatus.FEASIBLE, result.diagnostics().toString());
        assertTrue(IndependentIntervalAudit.check(source,result).isEmpty());
        assertEquals(expected.size(),result.blocks().size());
        System.out.println("CASE E2_6: trips=252 obligations="+actual.size()+" solver="+result.solverStatus());
    }

    @Test
    @Transactional
    void unavailableResourceProducesInfeasibleNotAValidEmptyPlan() {
        var loaded = datasets.load(CaseDatasetService.Dataset.BLOCKED6).response();
        var source = new SourceSnapshotE2Adapter().project(snapshots.findById(
                loaded.source().snapshotId()).orElseThrow()).snapshot();
        var result = new CpSatPlanner().plan(source,new PlannerRequest("1.0",source.scenarioId(),
                source.snapshotHash(),PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT,42,5));
        assertEquals(PlannerResult.SolverStatus.INFEASIBLE,result.solverStatus());
        assertTrue(result.blocks().isEmpty());
    }

    @Test
    void httpCaseFlowUsesSameSnapshotAndRejectsUnsupportedPlans() throws Exception {
        var api = new PlannerApi(mvc);
        var loaded=api.postJson("/api/v1/demo/case-source?dataset=E2_6","{}",201);
        String id=loaded.get("source").get("scenarioId").asText();
        var job=api.runJob(id,"case-e2-"+UUID.randomUUID());
        assertEquals("SUCCEEDED",job.get("status").asText());
        var plan=api.plan(job);
        assertEquals(loaded.get("source").get("snapshotHash").asText(),plan.get("snapshotHash").asText());
        assertEquals("PASS",plan.get("validationStatus").asText(),plan.toString());
        assertEquals("E2_MODEL",plan.get("validationReport").get("scope").asText());
        assertEquals(2160,plan.get("metrics").get("trainServiceMinutes").asInt());
        var calendar=api.getJson("/api/v1/plans/"+plan.get("id").asText()+"/calendar");
        long trips=0; for(var event:calendar.get("events")) if(event.get("kind").asText().equals("TRIP")) trips++;
        assertEquals(252,trips);
        api.postJson("/api/v1/plans/"+plan.get("id").asText()+"/approve","{\"expectedVersion\":0}",200);
        var full=api.postJson("/api/v1/demo/case-source?dataset=FULL43","{}",201);
        assertEquals(43,full.get("trainCount").asInt());
        api.postJson("/api/v1/planning-jobs",PlannerApi.jobBody(full.get("source").get("scenarioId").asText(),"unsupported"),422);
        api.postJson("/api/v1/demo/case-source?dataset=MADE_UP","{}",422);
    }

    private int count(String table,UUID scenario,String condition) {
        return jdbc.queryForObject("select count(*) from vsm."+table+" where scenario_id=? "+condition,Integer.class,scenario);
    }
}
