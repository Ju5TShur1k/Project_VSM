package com.vsm.okno;

import com.vsm.okno.data.CaseDatasetService;
import com.vsm.okno.data.SourceSnapshotRepository;
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
import tools.jackson.databind.JsonNode;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("database") @WithMockUser(username="planner",roles={"PLANNER","DISPATCHER"})
@EnabledIfEnvironmentVariable(named="D1_TEST_DB_URL",matches="jdbc:postgresql:.*")
class RecoveryIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",()->System.getenv("D1_TEST_DB_URL"));
        r.add("spring.datasource.username",()->System.getenv().getOrDefault("D1_TEST_DB_USER","okno"));
        r.add("spring.datasource.password",()->System.getenv().getOrDefault("D1_TEST_DB_PASSWORD","okno"));
    }
    @Autowired MockMvc mvc; @Autowired CaseDatasetService datasets; @Autowired SourceSnapshotRepository snapshots; @Autowired JdbcTemplate jdbc;
    JsonNode begin(PlannerApi api) throws Exception {
        var data=datasets.load(CaseDatasetService.Dataset.FULL43).response();
        return api.postJson("/api/v1/scenarios/"+data.source().scenarioId()+"/operations","{}",201);
    }
    JsonNode trip(JsonNode board,String train) {
        for (var t:board.get("trips")) if (t.get("plannedTrain").asText().equals(train)) return t;
        throw new AssertionError(train);
    }
    JsonNode fail(PlannerApi api,JsonNode board,String train) throws Exception {
        return api.postJson("/api/v1/operations/"+board.get("id").asText()+"/failures",
                "{\"expectedVersion\":"+board.get("version")+",\"tripId\":\""+trip(board,train).get("id").asText()+
                "\",\"occurredAt\":\"2031-07-01T05:00:00+03:00\",\"expectedRepairAt\":\"2031-07-01T09:00:00+03:00\",\"description\":\"MODELLED blocking failure\"}",200);
    }
    JsonNode substitute(PlannerApi api,JsonNode board) throws Exception {
        var fault=board.get("faults").get(board.get("faults").size()-1);
        JsonNode chosen=null; for (var c:fault.get("candidates")) if (c.get("eligible").asBoolean()) { chosen=c; break; }
        assertNotNull(chosen,fault.toString());
        return api.postJson("/api/v1/operations/"+board.get("id").asText()+"/replacements",
                "{\"expectedVersion\":"+board.get("version")+",\"faultId\":\""+fault.get("id").asText()+
                "\",\"trainId\":\""+chosen.get("trainId").asText()+"\"}",200);
    }
    @Test void real43TrainSourceSubstitutesThreeLegsAndKeepsSnapshotImmutable() throws Exception {
        var api=new PlannerApi(mvc); var board=begin(api); String originalHash=board.get("snapshotHash").asText();
        assertEquals(43,board.get("trainCount").asInt()); assertEquals(102,board.get("trips").size());
        assertEquals(38,board.get("availableTrainCount").asInt());
        board=fail(api,board,"CASE-01"); assertEquals(3,board.get("uncoveredTripCount").asInt());
        board=substitute(api,board); assertEquals(0,board.get("uncoveredTripCount").asInt());
        assertEquals(0,board.get("incompletePairCount").asInt());
        assertEquals(1,board.get("reserve").get(1).get("available").asInt());
        assertEquals(originalHash,board.get("snapshotHash").asText());
        var saved=snapshots.findById(java.util.UUID.fromString(board.get("sourceSnapshotId").asText())).orElseThrow();
        assertEquals(originalHash,saved.snapshotHash());
        assertEquals(1428,jdbc.queryForObject("select count(*) from vsm.fixed_trip where scenario_id=?",Integer.class,saved.scenarioId()));
        var reread=api.getJson("/api/v1/operations/"+board.get("id").asText()); assertEquals(board,reread);
    }
    @Test void thirdSameCityFailureReportsNoReplacementAndOtherCityReserveStaysUnused() throws Exception {
        var api=new PlannerApi(mvc); var board=begin(api);
        for (String train:new String[]{"CASE-01","CASE-05"}) board=substitute(api,fail(api,board,train));
        board=fail(api,board,"CASE-09");
        assertEquals(0,board.get("reserve").get(1).get("available").asInt());
        assertEquals(2,board.get("reserve").get(0).get("available").asInt());
        assertEquals(3,board.get("uncoveredTripCount").asInt()); assertEquals(3,board.get("incompletePairCount").asInt());
        var fault=board.get("faults").get(2); for (var c:fault.get("candidates")) assertFalse(c.get("eligible").asBoolean());
        assertTrue(board.get("messages").toString().contains("НЕТ ДОПУСТИМОЙ ЗАМЕНЫ"));
        JsonNode moscow=null; for (var c:fault.get("candidates")) if(c.get("location").asText().equals("MOSCOW")) { moscow=c; break; }
        assertNotNull(moscow);
        api.postJson("/api/v1/operations/"+board.get("id").asText()+"/replacements",
                "{\"expectedVersion\":"+board.get("version")+",\"faultId\":\""+fault.get("id").asText()+"\",\"trainId\":\""+moscow.get("trainId").asText()+"\"}",422);
    }
    @Test void repairForecastIsNotReleaseAndExplicitAcceptanceRestoresReserve() throws Exception {
        var api=new PlannerApi(mvc); var board=substitute(api,fail(api,begin(api),"CASE-01")); String id=board.get("id").asText();
        board=api.postJson("/api/v1/operations/"+id+"/clock","{\"expectedVersion\":2,\"asOf\":\"2031-07-01T10:00:00+03:00\"}",200);
        assertEquals(1,board.get("reserve").get(1).get("available").asInt());
        String body="{\"expectedVersion\":3,\"faultId\":\""+board.get("faults").get(0).get("id").asText()+"\",\"repairCompletedAt\":\"2031-07-01T09:30:00+03:00\",\"acceptedAt\":\"2031-07-01T10:00:00+03:00\",\"location\":\"SPB_DEPOT\",\"acceptanceReference\":\"MODELLED ACT-1\"}";
        board=api.postJson("/api/v1/operations/"+id+"/releases",body,200);
        assertEquals(2,board.get("reserve").get(1).get("available").asInt());
        assertEquals("RELEASED_TO_RESERVE",board.get("faults").get(0).get("status").asText());
        assertEquals(0,board.get("uncoveredTripCount").asInt());
        api.postJson("/api/v1/operations/"+id+"/releases",body,409);
    }
    @Test void staleCommandCannotReuseReserveOrOverwriteAnotherDecision() throws Exception {
        var api=new PlannerApi(mvc); var board=fail(api,begin(api),"CASE-01");
        var before=board.get("stateHash").asText(); board=substitute(api,board); assertNotEquals(before,board.get("stateHash").asText());
        api.postJson("/api/v1/operations/"+board.get("id").asText()+"/clock","{\"expectedVersion\":1,\"asOf\":\"2031-07-01T10:00:00+03:00\"}",409);
        assertEquals(3,jdbc.queryForObject("select count(*) from vsm.operational_revision where session_id=?",Integer.class,java.util.UUID.fromString(board.get("id").asText())));
    }
    @Test @Transactional void operationalRevisionCannotBeChangedOrDeleted() throws Exception {
        var api=new PlannerApi(mvc); var board=begin(api);
        assertThrows(org.springframework.dao.DataAccessException.class,()->jdbc.update("delete from vsm.operational_revision where session_id=?",java.util.UUID.fromString(board.get("id").asText())));
    }
    @Test void e2SnapshotCannotPretendToHaveReserveReleaseAndCleaningEvidence() throws Exception {
        var api=new PlannerApi(mvc); var data=datasets.load(CaseDatasetService.Dataset.E2_6).response();
        api.postJson("/api/v1/scenarios/"+data.source().scenarioId()+"/operations","{}",422);
    }
    @Test void missingVersionCannotSilentlyMutateTheInitialState() throws Exception {
        var api=new PlannerApi(mvc); var board=begin(api);
        api.postJson("/api/v1/operations/"+board.get("id").asText()+"/clock","{\"asOf\":\"2031-07-01T05:00:00+03:00\"}",422);
        assertEquals(0,api.getJson("/api/v1/operations/"+board.get("id").asText()).get("version").asInt());
    }
}
