package com.vsm.okno;

import com.vsm.okno.data.SourceSnapshotRepository;
import com.vsm.okno.data.SourceSnapshotE2Adapter;
import com.vsm.okno.data.SourceSnapshotE3Adapter;
import com.vsm.okno.requests.RequestDto;
import com.vsm.okno.requests.SourceVersionService;
import com.vsm.okno.service.PlanningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named="D1_TEST_DB_URL",matches="jdbc:postgresql:.*")
class E3SourceFactsIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("D1_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv().getOrDefault("D1_TEST_DB_USER","okno"));
        r.add("spring.datasource.password",()->System.getenv().getOrDefault("D1_TEST_DB_PASSWORD","okno"));
    }
    @Autowired PlanningService planning;
    @Autowired SourceVersionService versions;
    @Autowired SourceSnapshotRepository snapshots;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    private final ObjectMapper json=new ObjectMapper();
    private UUID fixture() { return planning.importDemoSource().scenarioId(); }
    private UUID full() { return versions.head(planning.importCaseDataset("FULL43").source().scenarioId()).scenarioId(); }
    private RequestDto.Command command(UUID id,JsonNode change) {
        return new RequestDto.Command(id,versions.version(id).version(),UUID.randomUUID().toString(),"E3 test","explicit test source",change);
    }
    private ObjectNode rules(UUID scenario,int duration,String status) {
        String resource=versions.source(scenario).path("resources").get(0).path("id").asText();
        return json.createObjectNode().put("kind","URGENT_RULE_CHANGE").put("version","demo-rule-"+duration)
                .set("rules",json.createArrayNode().add(json.createObjectNode().put("workKind","INSPECTION")
                        .put("durationMinutes",duration).put("confirmationStatus",status)
                        .set("resourceIds",json.createArrayNode().add(resource))));
    }
    private ObjectNode release(UUID base,String basis) {
        var source=versions.source(base); var work=source.path("frozenWork").get(0);
        return json.createObjectNode().put("kind","TRAIN_RELEASE").put("trainId",work.path("train_id").asText())
                .put("frozenWorkId",work.path("id").asText()).put("location","SPB_DEPOT").put("basis",basis)
                .put("availableFrom",OffsetDateTime.parse(source.path("scenario").path("horizon_start").asText()).plusDays(1).toString());
    }
    private JsonNode post(UUID base,RequestDto.Command body,String role,int code) throws Exception {
        return json.readTree(mvc.perform(PlannerApi.post("/api/v1/scenarios/"+base+"/source-fact-versions")
                .with(user(role.toLowerCase()).roles(role)).contentType("application/json").content(json.writeValueAsString(body)))
                .andExpect(status().is(code)).andReturn().getResponse().getContentAsString());
    }

    @Test void releaseIsVersionedRetriedCopiedAndCanonicalWithoutMakingItActual() throws Exception {
        UUID base=full(); var before=versions.source(base); var cmd=command(base,release(base,"DEMO_ASSUMPTION"));
        var receipt=post(base,cmd,"TECHNOLOGIST",201); UUID next=UUID.fromString(receipt.path("version").path("scenarioId").asText());
        var source=versions.source(next); var row=source.path("trainReleases").get(0);
        assertEquals("DEMO_ASSUMPTION",row.path("basis").asText()); assertEquals("SYNTHETIC",row.path("confirmation_status").asText());
        assertEquals("technologist",row.path("recorded_by").asText()); assertTrue(row.path("accepted_at").isNull());
        assertEquals("e3-source-facts-1.0",source.path("e3SourceFactsVersion").asText());
        assertEquals(before,versions.source(base)); assertNotEquals(versions.version(base).snapshotHash(),versions.version(next).snapshotHash());
        assertEquals(receipt.path("id"),post(base,cmd,"TECHNOLOGIST",201).path("id"));
        var stale=command(base,release(base,"FORECAST")); assertEquals("VERSION_CONFLICT",post(base,stale,"TECHNOLOGIST",409).path("code").asText());
        var changedKey=new RequestDto.Command(cmd.scenarioId(),cmd.expectedVersion(),cmd.idempotencyKey(),cmd.reason(),cmd.source(),release(base,"FORECAST"));
        assertEquals("IDEMPOTENCY_KEY_REUSED",post(base,changedKey,"TECHNOLOGIST",409).path("code").asText());
        var catalog=versions.submitE3Facts(command(next,rules(next,90,"SYNTHETIC")),"technologist");
        var copied=versions.source(catalog.version().scenarioId()).path("trainReleases").get(0);
        assertEquals(row.path("id"),copied.path("id")); assertEquals(row.path("available_from"),copied.path("available_from"));
        var saved=snapshots.findById(catalog.version().snapshotId()).orElseThrow();
        assertEquals(saved.snapshotHash(),HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(saved.canonicalPayload().getBytes(StandardCharsets.UTF_8))));
        var reopened=new SourceVersionService(jdbc,new SourceSnapshotRepository(jdbc));
        assertEquals(versions.source(catalog.version().scenarioId()),reopened.source(catalog.version().scenarioId()));
        assertThrows(RuntimeException.class,()->jdbc.update("update vsm.train_release set available_from=available_from+interval '1 minute' where scenario_id=?",next));
    }

    @Test void catalogResolvesUrgentDemandAndNewNormDoesNotRewriteOldSnapshot() {
        UUID base=fixture(); var norm=versions.submitE3Facts(command(base,rules(base,90,"SYNTHETIC")),"technologist");
        UUID id=norm.version().scenarioId(); var trip=versions.source(id).path("fixedTrips").get(0);
        var urgent=json.createObjectNode().put("kind","URGENT_MAINTENANCE").put("trainId",trip.path("train_id").asText())
                .put("problemType","diagnosis").put("detectedAt",trip.path("departure_at").asText())
                .put("notBeforeCurrentTripEnd",true).put("urgency","WITHIN_24H").put("workKind","INSPECTION");
        var receipt=versions.submit(command(id,urgent),"dispatcher",false);
        UUID next=receipt.version().scenarioId(); var old=versions.source(next); var work=old.path("urgentWorkRequirements").get(0);
        assertEquals(90,work.path("duration_minutes").asInt()); assertEquals("READY",work.path("rule_status").asText());
        assertEquals(1,work.path("resource_ids").size()); assertEquals("SYNTHETIC",work.path("rule_confirmation_status").asText());
        assertEquals(trip.path("arrival_at"),work.path("earliest_start_at")); assertFalse(work.path("blocks_next_departure").asBoolean());
        assertTrue(OffsetDateTime.parse(work.path("latest_end_at").asText()).isAfter(OffsetDateTime.parse(work.path("earliest_start_at").asText())));
        var updated=versions.submitE3Facts(command(next,rules(next,120,"SYNTHETIC")),"technologist");
        assertEquals(old,versions.source(next));
        var newWork=versions.source(updated.version().scenarioId()).path("urgentWorkRequirements").get(0);
        assertEquals(120,newWork.path("duration_minutes").asInt()); assertNotEquals(work.path("rule_id"),newWork.path("rule_id"));
        assertNotEquals(receipt.version().snapshotHash(),updated.version().snapshotHash());
    }

    @Test void unknownAndUnconfirmedRulesStayExplicitAndImmediateBlocksNextDeparture() {
        UUID base=fixture(); var norm=versions.submitE3Facts(command(base,rules(base,30,"UNCONFIRMED")),"technologist");
        UUID id=norm.version().scenarioId(); var train=versions.source(id).path("trains").get(0).path("id").asText();
        var urgent=json.createObjectNode().put("kind","URGENT_MAINTENANCE").put("trainId",train).put("problemType","test")
                .put("detectedAt","2028-07-01T00:00:00+03:00").put("notBeforeCurrentTripEnd",true).put("urgency","IMMEDIATE").put("workKind","INSPECTION");
        var receipt=versions.submit(command(id,urgent),"dispatcher",false);
        var demand=versions.source(receipt.version().scenarioId()).path("urgentWorkRequirements").get(0);
        assertEquals("UNCONFIRMED_RULE",demand.path("rule_status").asText()); assertTrue(demand.path("blocks_next_departure").asBoolean());
        urgent.put("workKind","UNKNOWN"); var missing=versions.submit(command(receipt.version().scenarioId(),urgent),"dispatcher",false);
        for (var row:versions.source(missing.version().scenarioId()).path("urgentWorkRequirements")) if (row.path("work_kind").asText().equals("UNKNOWN")) {
            assertEquals("MISSING_RULE",row.path("rule_status").asText()); assertTrue(row.path("duration_minutes").isNull()); assertTrue(row.path("resource_ids").isEmpty());
        }
    }

    @Test void invalidRulesRollbackWholeVersionAndOnlyTechnologistCanWriteFacts() throws Exception {
        UUID base=fixture(); var valid=command(base,rules(base,90,"SYNTHETIC")); int count=versions.versions(base).size();
        post(base,valid,"DISPATCHER",403); post(base,valid,"PLANNER",403);
        post(base,command(base,rules(base,0,"SYNTHETIC")),"TECHNOLOGIST",422);
        var bad=rules(base,90,"SYNTHETIC"); ((ObjectNode)bad.path("rules").get(0)).set("resourceIds",json.createArrayNode().add("MISSING"));
        post(base,command(base,bad),"TECHNOLOGIST",422);
        var duplicate=rules(base,90,"SYNTHETIC"); var ids=(tools.jackson.databind.node.ArrayNode)duplicate.path("rules").get(0).path("resourceIds"); ids.add(ids.get(0).asText());
        post(base,command(base,duplicate),"TECHNOLOGIST",422);
        assertEquals(count,versions.versions(base).size()); assertTrue(versions.requests(base,100).isEmpty());
        mvc.perform(PlannerApi.post("/api/v1/change-requests").with(user("dispatcher").roles("DISPATCHER"))
                .contentType("application/json").content(json.writeValueAsString(valid))).andExpect(status().isUnprocessableEntity());
        var accepted=post(base,valid,"TECHNOLOGIST",201); UUID next=UUID.fromString(accepted.path("version").path("scenarioId").asText());
        var catalog=json.readTree(mvc.perform(get("/api/v1/scenarios/"+next+"/urgent-work-rules").with(user("dispatcher").roles("DISPATCHER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()); assertEquals(90,catalog.get(0).path("duration_minutes").asInt());
    }

    @Test void releaseMustFollowItsOwnWorkAndForecastCannotMasqueradeAsAcceptance() throws Exception {
        UUID base=full(); var good=release(base,"DEMO_ASSUMPTION");
        post(base,command(base,good.deepCopy().put("availableFrom","2031-07-01T01:00:00+03:00")),"TECHNOLOGIST",422);
        post(base,command(base,good.deepCopy().put("trainId",UUID.randomUUID().toString())),"TECHNOLOGIST",422);
        post(base,command(base,good.deepCopy().put("location","MOSCOW")),"TECHNOLOGIST",422);
        post(base,command(base,good.deepCopy().put("basis","ACCEPTED")),"TECHNOLOGIST",422);
        post(base,command(base,good.deepCopy().put("basis","ACCEPTED").put("acceptedAt","2031-07-01T03:00:00+03:00").put("acceptanceDocument","future fake")),"TECHNOLOGIST",422);
        post(base,command(base,good.deepCopy().put("basis","FORECAST").put("acceptedAt","2031-07-01T03:00:00+03:00")),"TECHNOLOGIST",422);
        var receipt=post(base,command(base,release(base,"FORECAST")),"TECHNOLOGIST",201);
        UUID next=UUID.fromString(receipt.path("version").path("scenarioId").asText()); var row=versions.source(next).path("trainReleases").get(0);
        assertEquals("UNCONFIRMED",row.path("confirmation_status").asText()); assertTrue(row.path("accepted_at").isNull());
    }

    @Test void existingSourcesKeepTheirCanonicalOutputAndUnsupportedAdaptersRejectExtension() {
        UUID base=fixture(); var v=versions.version(base);
        assertEquals(v.snapshotHash(),jdbc.queryForObject("select vsm.canonical_sha256(vsm.source_payload(?))",String.class,base));
        assertEquals(versions.source(base),json.readTree(jdbc.queryForObject("select vsm.source_payload_before_e3_facts(?)::text",String.class,base)));
        var next=versions.submitE3Facts(command(base,rules(base,90,"SYNTHETIC")),"technologist");
        var saved=snapshots.findById(next.version().snapshotId()).orElseThrow();
        assertTrue(assertThrows(IllegalArgumentException.class,()->new SourceSnapshotE2Adapter().project(saved)).getMessage().contains("urgentWorkRules"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->new SourceSnapshotE3Adapter().project(saved)).getMessage().contains("urgentWorkRules"));
    }

    @Test void actualReleaseRequiresDocumentAndDemoFactsAreRejectedForAnOperatorSource() throws Exception {
        UUID rules=UUID.randomUUID(),scenario=UUID.randomUUID(),train=UUID.randomUUID();
        jdbc.update("insert into vsm.rule_set values (?,?,'operator','CONFIRMED','ABSOLUTE_GRID','NOMINAL_MILESTONE')",rules,"actual-"+rules);
        jdbc.update("insert into vsm.scenario values (?,'past actual source',?,'2025-07-01T00:00:00Z','2025-07-03T00:00:00Z','OPERATOR')",scenario,rules);
        jdbc.update("insert into vsm.train values (?,?,'ACTUAL-TRAIN','MAINTENANCE','DEPOT','operator')",scenario,train);
        versions.register(snapshots.capture(scenario).id(),"technologist");
        var fact=json.createObjectNode().put("kind","TRAIN_RELEASE").put("trainId",train.toString()).put("availableFrom","2025-07-01T03:00:00Z")
                .put("location","DEPOT").put("basis","ACCEPTED").put("acceptedAt","2025-07-01T02:00:00Z").put("acceptanceDocument","ACT-001");
        var demo=fact.deepCopy().put("basis","DEMO_ASSUMPTION"); demo.remove("acceptedAt"); demo.remove("acceptanceDocument");
        post(scenario,command(scenario,demo),"TECHNOLOGIST",422);
        var receipt=post(scenario,command(scenario,fact),"TECHNOLOGIST",201);
        var source=versions.source(UUID.fromString(receipt.path("version").path("scenarioId").asText()));
        assertEquals("CONFIRMED",source.path("trainReleases").get(0).path("confirmation_status").asText());
        assertEquals("ACT-001",source.path("trainReleases").get(0).path("acceptance_document").asText());
    }

    @Test void uncalculatedDescendantsAndOldCachedMetadataKeepTheFullFleetGuard() {
        UUID base=full();
        var a=versions.submitE3Facts(command(base,release(base,"DEMO_ASSUMPTION")),"technologist");
        var b=versions.submitE3Facts(command(a.version().scenarioId(),rules(a.version().scenarioId(),90,"SYNTHETIC")),"technologist");
        var c=versions.submitE3Facts(command(b.version().scenarioId(),rules(b.version().scenarioId(),120,"SYNTHETIC")),"technologist");
        UUID next=c.version().scenarioId();
        planning.getScenario(next); // Materialize, then simulate an old API cache lacking the guard.
        jdbc.update("update vsm.api_scenario set payload=payload-'planningUnsupportedReason' where id=?",next);
        var request=new com.vsm.okno.dto.Dto.JobRequest(next,"BLOCKS_CP_SAT",1,30,null,UUID.randomUUID().toString());
        var error=assertThrows(PlanningService.InvalidRequestException.class,()->planning.createJob(request));
        assertTrue(error.getMessage().contains("FULL43"),error.getMessage());
    }

    @Test void legacyD2RejectsTheExtensionEvenWhenAPreparedInputClaimsTheNewHash() {
        UUID base=full(); UUID root=versions.version(base).rootId();
        var original=snapshots.findById(versions.version(root).snapshotId()).orElseThrow();
        var old=new SourceSnapshotE3Adapter().project(original).snapshot();
        var changed=versions.submitE3Facts(command(base,rules(base,90,"SYNTHETIC")),"technologist");
        var saved=snapshots.findById(changed.version().snapshotId()).orElseThrow();
        // An adapter that simply copies the old projection must not gain D2 approval.
        var prepared=new com.vsm.okno.planning.ScenarioSnapshot("1.4",saved.scenarioId(),saved.snapshotHash(),old.provenance(),
                old.horizonStart(),old.horizonEnd(),old.trains(),old.resources(),old.blocks(),old.fixedTrips(),old.operations());
        var result=new com.vsm.okno.planning.PlannerResult("1.0",saved.scenarioId(),saved.snapshotHash(),
                com.vsm.okno.planning.PlannerRequest.Policy.BLOCKS_CP_SAT,com.vsm.okno.planning.PlannerResult.SolverStatus.UNKNOWN,
                java.util.List.of(),java.util.List.of(),1,0,null);
        var report=new com.vsm.okno.validation.E3SourcePlanAudit().report(saved,prepared,result);
        assertEquals("FAILED",report.status());
        assertTrue(report.findings().stream().anyMatch(f->f.code().equals("D2_UNSUPPORTED_SOURCE_FIELD")));
    }
}
