package com.vsm.okno;

import com.vsm.okno.data.CaseDatasetService;
import com.vsm.okno.data.SourceSnapshotE2Adapter;
import com.vsm.okno.data.SourceSnapshotRepository;
import com.vsm.okno.dto.Dto;
import com.vsm.okno.planning.CpSatPlanner;
import com.vsm.okno.planning.EarliestDueDatePlanner;
import com.vsm.okno.planning.MileageObligationGenerator;
import com.vsm.okno.planning.PlannerRequest;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.ScenarioSnapshot;
import com.vsm.okno.service.PlanningService;
import com.vsm.okno.store.Store;
import com.vsm.okno.validation.PlanMetrics;
import com.vsm.okno.validation.SourcePlanAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real source, solver and D2 through HTTP. Deliberate test-only corruption never adds an API backdoor. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("database")
@WithMockUser(username="tester",roles="PLANNER")
@EnabledIfEnvironmentVariable(named="D1_TEST_DB_URL",matches="jdbc:postgresql:.*")
class D2DatabaseIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("D1_TEST_DB_URL"));
        registry.add("spring.datasource.username",()->System.getenv().getOrDefault("D1_TEST_DB_USER","okno"));
        registry.add("spring.datasource.password",()->System.getenv().getOrDefault("D1_TEST_DB_PASSWORD","okno"));
    }
    @Autowired MockMvc mvc;
    @Autowired CaseDatasetService datasets;
    @Autowired SourceSnapshotRepository snapshots;
    @Autowired PlanningService service;

    @Test void actualD2AllowsOnlyTheValidatedModelPlanVersion() throws Exception {
        var api=new PlannerApi(mvc); var plan=goodPlan(api);
        assertEquals("PASS",plan.validationStatus);
        assertEquals(plan.snapshotHash,plan.validationReport.snapshotHash());
        assertNotNull(plan.validationReport.sourceSnapshotId());
        assertEquals(12,plan.metrics.requiredServiceCount());
        assertEquals(0,plan.metrics.conflictingTripCount());
        assertEquals(2160,plan.metrics.trainServiceMinutes());
        var calendar=api.getJson("/api/v1/plans/"+plan.id+"/calendar");
        assertTrue(calendar.get("independentlyValidated").asBoolean());
        var approved=api.postJson("/api/v1/plans/"+plan.id+"/approve","{\"expectedVersion\":0}",200);
        assertEquals("APPROVED",approved.get("status").asText());
        api.postJson("/api/v1/plans/"+plan.id+"/approve","{\"expectedVersion\":0}",409);
        api.postJson("/api/v1/plans/"+plan.id+"/approve","{\"expectedVersion\":1}",422);
    }

    @Test void solverSuccessWithoutAFeasiblePlanCannotBeApproved() throws Exception {
        var api=new PlannerApi(mvc);
        var loaded=api.postJson("/api/v1/demo/case-source?dataset=BLOCKED6","{}",201);
        var job=api.runJob(loaded.get("source").get("scenarioId").asText(),"d2-blocked-"+UUID.randomUUID());
        assertEquals("SUCCEEDED",job.get("status").asText());
        assertEquals("INFEASIBLE",job.get("solverStatus").asText());
        var plan=api.plan(job); assertEquals("FAILED",plan.get("validationStatus").asText());
        assertTrue(plan.get("metrics").isNull());
        api.postJson("/api/v1/plans/"+plan.get("id").asText()+"/approve","{\"expectedVersion\":0}",422);
    }

    @Test void changedIntervalsOrDeletedWorkCannotReuseTheOldPass() throws Exception {
        var api=new PlannerApi(mvc); var plan=goodPlan(api); var original=plan.events;
        var first=original.getFirst(); List<Dto.PlanEvent> altered=new ArrayList<>(original);
        altered.set(0,new Dto.PlanEvent(first.id(),first.trainId(),first.kind(),
                OffsetDateTime.parse(first.startAt()).plusMinutes(1).toString(),
                OffsetDateTime.parse(first.endAt()).plusMinutes(1).toString(),first.resourceIds()));
        plan.events=List.copyOf(altered);
        assertRefused(api,plan);
        plan.events=original.subList(1,original.size()); assertRefused(api,plan);
        plan.events=original;
        String hash=plan.snapshotHash; plan.snapshotHash="wrong-source"; assertRefused(api,plan); plan.snapshotHash=hash;
        plan.version=1; api.postJson("/api/v1/plans/"+plan.id+"/approve","{\"expectedVersion\":1}",422);
    }

    @Test void bothGeneratorAndResultOmittingTheSameTrainWorkAreRejectedByServer() throws Exception {
        var response=datasets.load(CaseDatasetService.Dataset.E2_6).response();
        var projection=new SourceSnapshotE2Adapter().project(snapshots.findById(response.source().snapshotId()).orElseThrow());
        var source=projection.snapshot(); UUID train=source.trains().getFirst().id();
        var broken=new ScenarioSnapshot(source.schemaVersion(),source.scenarioId(),source.snapshotHash(),source.provenance(),
                source.horizonStart(),source.horizonEnd(),source.trains(),source.resources(),
                source.blocks().stream().filter(b -> !b.trainId().equals(train)).toList(),source.fixedTrips(),source.operations());
        var result=new CpSatPlanner().plan(broken,new PlannerRequest("1.0",broken.scenarioId(),broken.snapshotHash(),PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT,42,10));
        assertTrue(result.solverStatus()==PlannerResult.SolverStatus.OPTIMAL||result.solverStatus()==PlannerResult.SolverStatus.FEASIBLE);
        var wrongProjection=new MileageObligationGenerator.Projection(broken,projection.obligations().stream().filter(o -> !o.trainId().equals(train)).toList());
        Store.Plan plan=ReflectionTestUtils.invokeMethod(service,"toPlan",broken.scenarioId(),broken,result,wrongProjection);
        assertNotNull(plan); assertEquals("FAILED",plan.validationStatus);
        assertTrue(plan.validations.stream().anyMatch(v -> v.code().equals("D2_PROJECTION_MISSING_WORK")));
        store().plans.put(plan.id,plan); assertRefused(new PlannerApi(mvc),plan);
    }

    @Test void exportsReproducibleComparisonWithoutClaimingUnmeasuredBusinessBenefit() throws Exception {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var dataset:List.of(CaseDatasetService.Dataset.E2_6,CaseDatasetService.Dataset.BLOCKED6)) {
            var response=datasets.load(dataset).response(); var saved=snapshots.findById(response.source().snapshotId()).orElseThrow();
            var source=new SourceSnapshotE2Adapter().project(saved).snapshot();
            for(var policy:List.of(PlannerRequest.Policy.WHOLE_CYCLE_EDD,PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT)) {
                var request=new PlannerRequest("1.0",source.scenarioId(),source.snapshotHash(),policy,42,10);
                var planner=policy==PlannerRequest.Policy.WHOLE_CYCLE_EDD?new EarliestDueDatePlanner():new CpSatPlanner();
                var result=planner.plan(source,request); var report=new SourcePlanAudit().report(saved,source,result);
                var metrics=PlanMetrics.calculate(source,result,report);
                if(dataset==CaseDatasetService.Dataset.E2_6) assertEquals("PASS",report.status(),report.findings().toString());
                else assertEquals("FAILED",report.status());
                Map<String,Object> row=new LinkedHashMap<>();
                row.put("dataset",dataset.name()); row.put("scenarioId",source.scenarioId().toString());
                row.put("sourceSnapshotId",saved.id().toString()); row.put("snapshotHash",source.snapshotHash());
                row.put("policy",policy.name()); row.put("seed",42); row.put("timeLimitSeconds",10);
                row.put("solverStatus",result.solverStatus().name()); row.put("elapsedMillis",result.elapsedMillis());
                row.put("d2Status",report.status()); row.put("validationScope",report.scope()); row.put("resultHash",report.resultHash());
                row.put("requiredServiceCount",report.requiredServiceCount()); row.put("placedServiceCount",result.blocks().size());
                row.put("metrics",metrics); row.put("pendingMilestones",report.pendingMilestones());
                row.put("criticalCodes",report.findings().stream().filter(v -> v.severity().equals("CRITICAL")).map(Dto.Validation::code).toList());
                rows.add(row);
            }
        }
        Map<String,Object> output=new LinkedHashMap<>(); output.put("schemaVersion","d2-comparison-1.0");
        output.put("generatedAt",Instant.now().toString()); output.put("dataProvenance","MODELLED; case numeric norms, not operator records");
        output.put("readinessKpi","Not calculated: business formula not confirmed");
        output.put("javaVersion",System.getProperty("java.version")); output.put("os",System.getProperty("os.name"));
        output.put("buildCommit",System.getenv().getOrDefault("D2_BUILD_COMMIT","working tree; commit unspecified")); output.put("rows",rows);
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/d2-comparison.json"),new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(output));
        System.out.println("D2 comparison saved: target/d2-comparison.json");
    }

    private Store.Plan goodPlan(PlannerApi api) throws Exception {
        var response=api.postJson("/api/v1/demo/case-source?dataset=E2_6","{}",201);
        var job=api.runJob(response.get("source").get("scenarioId").asText(),"d2-good-"+UUID.randomUUID());
        var dto=api.plan(job); assertEquals("PASS",dto.get("validationStatus").asText(),dto.toString());
        return store().plans.get(UUID.fromString(dto.get("id").asText()));
    }
    private Store store() { return (Store)ReflectionTestUtils.getField(service,"store"); }
    private void assertRefused(PlannerApi api,Store.Plan plan) throws Exception {
        var response=api.postJson("/api/v1/plans/"+plan.id+"/approve","{\"expectedVersion\":0}",422);
        assertEquals("PLAN_NOT_APPROVABLE",response.get("code").asText()); assertEquals("DRAFT",plan.status);
    }
}
