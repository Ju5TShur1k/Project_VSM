package com.vsm.okno;

import com.vsm.okno.requests.RequestDto;
import com.vsm.okno.requests.SourceVersionService;
import com.vsm.okno.service.PlanningService;
import com.vsm.okno.store.DatabasePlanningRepository;
import com.vsm.okno.store.Store;
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
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Actual PostgreSQL transaction/HTTP/worker boundaries, independent of the CP-SAT implementation. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("database")
@WithMockUser(username="planner",roles="PLANNER")
@EnabledIfEnvironmentVariable(named="D1_TEST_DB_URL",matches="jdbc:postgresql:.*")
class ChangeRequestIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",()->System.getenv("D1_TEST_DB_URL"));
        registry.add("spring.datasource.username",()->System.getenv().getOrDefault("D1_TEST_DB_USER","okno"));
        registry.add("spring.datasource.password",()->System.getenv().getOrDefault("D1_TEST_DB_PASSWORD","okno"));
    }
    @Autowired MockMvc mvc;
    @Autowired PlanningService service;
    @Autowired SourceVersionService versions;
    @Autowired DatabasePlanningRepository persisted;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.vsm.okno.requests.UiRequestService ui;
    private final ObjectMapper json=new ObjectMapper();

    private UUID fixture() { return service.importDemoSource().scenarioId(); }
    private JsonNode r1(UUID id) {
        for (var trip:versions.source(id).path("fixedTrips")) if (trip.path("label").asText().equals("R1")) return trip;
        return fail("R1 missing");
    }
    private JsonNode shifted(UUID id,int minutes) {
        var trip=r1(id);
        return json.createObjectNode().put("kind","TRIP_CHANGE").put("tripId",trip.path("id").asText())
                .put("trainId",trip.path("train_id").asText())
                .put("departureAt",OffsetDateTime.parse(trip.path("departure_at").asText()).plusMinutes(minutes).toString())
                .put("arrivalAt",OffsetDateTime.parse(trip.path("arrival_at").asText()).plusMinutes(minutes).toString());
    }
    private RequestDto.Command command(UUID id,String key,JsonNode change) {
        return new RequestDto.Command(id,versions.version(id).version(),key,"Проверка сдвига рейса","Сообщение диспетчера",change);
    }
    private JsonNode post(RequestDto.Command command,String role,int status) throws Exception {
        return json.readTree(mvc.perform(PlannerApi.post("/api/v1/change-requests").with(user(role.toLowerCase()).roles(role))
                .contentType("application/json").content(json.writeValueAsString(command)))
                .andExpect(status().is(status)).andReturn().getResponse().getContentAsString());
    }

    private JsonNode uiPost(UUID base,JsonNode command,String role,int expected) throws Exception {
        var response=mvc.perform(PlannerApi.post("/api/v1/scenarios/"+base+"/requests").with(user(role.toLowerCase()).roles(role))
                .contentType("application/json").content(json.writeValueAsString(command)))
                .andReturn();
        assertEquals(expected,response.getResponse().getStatus(),response.getResponse().getContentAsString()+" "+response.getResolvedException());
        return json.readTree(response.getResponse().getContentAsString());
    }
    private tools.jackson.databind.node.ObjectNode uiTrip(UUID base) {
        var p=shifted(base,5);
        return json.createObjectNode().put("clientRequestId",UUID.randomUUID().toString())
                .put("expectedSnapshotHash",versions.version(base).snapshotHash()).put("comment","Звонок в 00:25, сдвиг на пять минут")
                .set("payload",json.createObjectNode().put("kind","TRIP_CHANGE").put("trainId",p.path("trainId").asText())
                        .put("tripId",p.path("tripId").asText()).put("newDepartureAt",p.path("departureAt").asText())
                        .put("newArrivalAt",p.path("arrivalAt").asText()).put("reason","Задержка отправления").put("source","Звонок с линии"));
    }

    @Test void frontendContractUsesRealRequestsAutomaticJobAndApprovedPlanWithoutReplacingItWithDraft() throws Exception {
        UUID base=fixture(); var api=new PlannerApi(mvc);
        mvc.perform(get("/api/v1/plans/active?scenarioId="+base).with(user("dispatcher").roles("DISPATCHER"))).andExpect(status().isNoContent());
        var old=api.plan(api.runJob(base.toString(),"ui-base-"+UUID.randomUUID()));
        api.postJson("/api/v1/plans/"+old.path("id").asText()+"/approve","{\"expectedVersion\":0}",200);
        var body=uiTrip(base); var response=uiPost(base,body,"DISPATCHER",201);
        UUID id=UUID.fromString(response.path("id").asText());
        assertTrue(response.path("number").asLong()>0); assertEquals(base.toString(),response.path("scenarioId").asText());
        assertEquals(body.path("payload"),response.path("payload")); assertEquals(body.path("comment"),response.path("comment"));
        assertEquals("dispatcher",response.path("createdBy").asText()); assertFalse(response.path("jobId").isNull());
        assertNotEquals(body.path("expectedSnapshotHash").asText(),response.path("newSnapshotHash").asText());
        assertEquals(id.toString(),uiPost(base,body,"DISPATCHER",200).path("id").asText());
        var changedComment=body.deepCopy().put("comment","Другой комментарий");
        assertEquals("IDEMPOTENCY_KEY_REUSED",uiPost(base,changedComment,"DISPATCHER",409).path("code").asText());
        var stale=body.deepCopy().put("clientRequestId",UUID.randomUUID().toString()); uiPost(base,stale,"DISPATCHER",409);
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (List.of("QUEUED","RUNNING").contains(persisted.job(UUID.fromString(response.path("jobId").asText())).status) && System.nanoTime()<deadline) Thread.sleep(100);
        var ready=ui.get(id); assertEquals("VALIDATED",ready.status(),ready.toString()); assertEquals("PASS",ready.validationStatus());
        assertEquals(old.path("id").asText(),persisted.currentPlan(base).toString());
        // Calendar IDs remain comparable across versions; plans retain exact source scenario IDs.
        assertEquals(base,service.getCalendar(ready.planId()).scenarioId());
        assertEquals(ready.newScenarioId(),service.getPlan(ready.planId()).scenarioId());
        var active=json.readTree(mvc.perform(get("/api/v1/plans/active").with(user("dispatcher").roles("DISPATCHER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(old.path("id").asText(),active.path("id").asText());
        api.postJson("/api/v1/plans/"+ready.planId()+"/approve","{\"expectedVersion\":0}",200);
        ready=ui.get(id); assertEquals("APPROVED",ready.status()); assertEquals("planner",ready.decidedBy()); assertNotNull(ready.decidedAt());
        assertEquals(ready.planId(),persisted.currentPlan(base));
        var list=json.readTree(mvc.perform(get("/api/v1/requests?scenarioId="+base).with(user("technologist").roles("TECHNOLOGIST")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertEquals(1,list.size()); assertEquals(id.toString(),list.get(0).path("id").asText());
        assertEquals(2,versions.versions(base).size());
        assertEquals(1,jdbc.queryForObject("select count(*) from vsm.planning_job where idempotency_key=?",Integer.class,"request:"+id));
        assertEquals("APPROVED",uiPost(base,body,"DISPATCHER",200).path("status").asText());
    }

    @Test void frontendUrgentPayloadPreservesPolicyAndReportsFailureUntilTaskTwoHandlesTheRequirement() throws Exception {
        UUID base=fixture();
        var payload=json.createObjectNode().put("kind","URGENT_MAINTENANCE").put("trainId",r1(base).path("train_id").asText())
                .put("problem","Замечание машиниста").put("detectedAt","2028-07-01T00:35:00+03:00")
                .put("notBeforeTripEnd",true).put("urgency","WITHIN_24H").putNull("dueBy").put("workType","Внеплановый осмотр");
        var body=json.createObjectNode().put("clientRequestId",UUID.randomUUID().toString()).put("expectedSnapshotHash",versions.version(base).snapshotHash())
                .put("comment","Стук в тележке").set("payload",payload);
        var result=uiPost(base,body,"DISPATCHER",201); UUID id=UUID.fromString(result.path("id").asText());
        var requirement=versions.source(UUID.fromString(result.path("newScenarioId").asText())).path("urgentWorkRequirements").get(0);
        assertEquals("WITHIN_24H",requirement.path("urgency").asText());
        assertFalse(requirement.has("resource_id")); assertEquals(payload,ui.get(id).payload());
        assertEquals(OffsetDateTime.parse(r1(base).path("arrival_at").asText()).toInstant(),OffsetDateTime.parse(requirement.path("earliest_start_at").asText()).toInstant());
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (ui.get(id).status().equals("APPLIED") && System.nanoTime()<deadline) Thread.sleep(100);
        assertEquals("FAILED",ui.get(id).status()); assertEquals("UNSUPPORTED_SOURCE_FACTS",ui.get(id).error().code());
        assertEquals(id.toString(),uiPost(base,body,"DISPATCHER",200).path("id").asText());
    }

    @Test void frontendRoleAndHashChecksDoNotCreateUntrackedVersions() throws Exception {
        UUID base=fixture(); var body=uiTrip(base);
        uiPost(base,body,"TECHNOLOGIST",403); uiPost(base,body,"OTHER",403);
        var badHash=body.deepCopy().put("expectedSnapshotHash","a".repeat(64)); uiPost(base,badHash,"DISPATCHER",409);
        var badTrip=body.deepCopy(); ((tools.jackson.databind.node.ObjectNode)badTrip.path("payload")).put("tripId",UUID.randomUUID().toString());
        uiPost(base,badTrip,"DISPATCHER",422);
        assertEquals(1,versions.versions(base).size()); assertTrue(ui.list(base,100).isEmpty());
        mvc.perform(get("/api/v1/requests").with(user("dispatcher").roles("DISPATCHER"))).andExpect(status().isOk());
    }

    @Test void concurrentUiRetriesCreateExactlyOneRequestSourceMetadataAndJob() throws Exception {
        UUID base=fixture(); var body=uiTrip(base);
        var command=json.treeToValue(body,com.vsm.okno.requests.UiRequestService.NewRequest.class);
        UUID request;
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->ui.submit(base,command,"dispatcher"));
            var b=pool.submit(()->ui.submit(base,command,"dispatcher"));
            var first=a.get(); var second=b.get(); request=first.request().id();
            assertEquals(request,second.request().id()); assertNotEquals(first.replay(),second.replay());
        }
        assertEquals(2,versions.versions(base).size()); assertEquals(1,ui.list(base,100).size());
        assertEquals(1,jdbc.queryForObject("select count(*) from vsm.request_client_metadata where request_id=?",Integer.class,request));
        assertEquals(1,jdbc.queryForObject("select count(*) from vsm.planning_job where idempotency_key=?",Integer.class,"request:"+request));
    }

    @Test void legacyIncidentJournalPersistsAuthenticatedAuthorAndTimestamp() throws Exception {
        var recorded=json.readTree(mvc.perform(PlannerApi.post("/api/v1/incidents").with(user("dispatcher").roles("DISPATCHER"))
                .contentType("application/json").content("{\"train\":\"EVS-SYN-1\",\"kind\":\"TRIP_CHANGE\",\"description\":\"Совместимый журнал\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID id=UUID.fromString(recorded.path("id").asText());
        var reopened=new DatabasePlanningRepository(jdbc,versions);
        var incident=reopened.incidents().stream().filter(i->i.id().equals(id)).findFirst().orElseThrow();
        assertEquals("dispatcher",incident.reportedBy()); assertNotNull(incident.reportedAt());
        assertEquals("Совместимый журнал",incident.description());
    }

    @Test void fiveMinuteShiftKeepsOldFactsSnapshotAuthorAndExactRetry() throws Exception {
        UUID base=fixture(); var before=versions.source(base); var trip=r1(base); var old=versions.version(base);
        var command=command(base,"shift-"+UUID.randomUUID(),shifted(base,5));
        var saved=post(command,"DISPATCHER",201); UUID request=UUID.fromString(saved.path("id").asText());
        UUID next=UUID.fromString(saved.path("version").path("scenarioId").asText());
        assertNotEquals(base,next); assertEquals("RECEIVED",saved.path("status").asText());
        assertEquals("dispatcher",saved.path("reportedBy").asText());
        assertEquals(trip.path("id").asText(),saved.path("tripId").asText());
        assertEquals(1,versions.version(next).version()); assertEquals(base,versions.version(next).parentScenarioId());
        assertNotEquals(old.snapshotHash(),versions.version(next).snapshotHash());
        assertEquals(before,versions.source(base));
        assertEquals(OffsetDateTime.parse(trip.path("arrival_at").asText()).plusMinutes(5).toInstant(),
                OffsetDateTime.parse(r1(next).path("arrival_at").asText()).toInstant());
        assertEquals(request.toString(),post(command,"DISPATCHER",201).path("id").asText());
        assertEquals(2,versions.versions(base).size());
        assertEquals("VERSION_CONFLICT",post(command(base,"stale-"+UUID.randomUUID(),shifted(base,6)),"DISPATCHER",409).path("code").asText());
        assertEquals("IDEMPOTENCY_KEY_REUSED",post(command(base,command.idempotencyKey(),shifted(base,6)),"DISPATCHER",409).path("code").asText());
        assertEquals(2,versions.versions(base).size());
        // New repository/service objects have no shared Store cache.
        var reopenedVersions=new SourceVersionService(jdbc,new com.vsm.okno.data.SourceSnapshotRepository(jdbc));
        var reopened=new DatabasePlanningRepository(jdbc,reopenedVersions);
        assertEquals(next,reopened.scenario(next).id);
        assertEquals(versions.receipt(request),reopenedVersions.receipt(request));
        mvc.perform(get("/api/v1/scenarios/"+base+"/source").with(user("dispatcher").roles("DISPATCHER"))).andExpect(status().isOk());
    }

    @Test void invalidOverlappingOrMismatchedTripRollsBackWholeVersion() throws Exception {
        UUID base=fixture(); var change=(tools.jackson.databind.node.ObjectNode)shifted(base,5);
        change.put("trainId",UUID.randomUUID().toString()); post(command(base,"wrong-"+UUID.randomUUID(),change),"DISPATCHER",422);
        change=(tools.jackson.databind.node.ObjectNode)shifted(base,5); change.put("arrivalAt","2028-07-01T01:40:00+03:00");
        post(command(base,"overlap-"+UUID.randomUUID(),change),"DISPATCHER",422);
        change=(tools.jackson.databind.node.ObjectNode)shifted(base,5); change.put("departureAt","2028-07-01T00:25:00");
        post(command(base,"offset-"+UUID.randomUUID(),change),"DISPATCHER",422);
        assertEquals(1,versions.versions(base).size()); assertEquals(base,versions.head(base).scenarioId());
        assertTrue(versions.requests(base,100).isEmpty());
    }

    @Test void concurrentExactRetriesCreateOneVersionAndDifferentRequestsCannotBothAdvanceSameHead() throws Exception {
        UUID base=fixture(); var c=command(base,"parallel-"+UUID.randomUUID(),shifted(base,5));
        try (var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->versions.submit(c,"dispatcher",false));
            var b=pool.submit(()->versions.submit(c,"dispatcher",false));
            assertEquals(a.get().id(),b.get().id());
        }
        assertEquals(2,versions.versions(base).size());
        assertThrows(PlanningService.VersionConflictException.class,()->versions.submit(command(base,"second-"+UUID.randomUUID(),shifted(base,6)),"dispatcher",false));
    }

    @Test void urgentRequirementStartsAfterCurrentTripButDoesNotAssignASlotOrGetIgnored() throws Exception {
        UUID base=fixture(); var trip=r1(base);
        var change=json.createObjectNode().put("kind","URGENT_MAINTENANCE").put("trainId",trip.path("train_id").asText())
                .put("problemType","BRAKE_INSPECTION").put("detectedAt","2028-07-01T00:35:00+03:00")
                .put("notBeforeCurrentTripEnd",true).put("deadlineAt","2028-07-01T03:00:00+03:00").put("workKind","UNSCHEDULED_INSPECTION");
        var saved=post(command(base,"urgent-"+UUID.randomUUID(),change),"DISPATCHER",201);
        UUID next=UUID.fromString(saved.path("version").path("scenarioId").asText());
        var requirement=versions.source(next).path("urgentWorkRequirements").get(0);
        assertEquals(OffsetDateTime.parse(trip.path("arrival_at").asText()).toInstant(),OffsetDateTime.parse(requirement.path("earliest_start_at").asText()).toInstant());
        assertFalse(requirement.has("resource_id")); assertFalse(requirement.has("starts_at"));
        var api=new PlannerApi(mvc); var job=api.runJob(next.toString(),"urgent-job-"+UUID.randomUUID());
        assertEquals("FAILED",job.path("status").asText()); assertTrue(job.path("error").asText().startsWith("UNSUPPORTED_SOURCE_FACTS"));
        var receipt=versions.receipt(UUID.fromString(saved.path("id").asText()));
        assertEquals("ERROR",receipt.status()); assertEquals("UNSUPPORTED_SOURCE_FACTS",receipt.errorCode());
    }

    @Test void outageCancelAndAddAreIndependentVersionedFacts() {
        UUID base=fixture(); var trip=r1(base);
        var outage=json.createObjectNode().put("kind","RESOURCE_OUTAGE").put("resourceId","PATH")
                .put("startsAt","2028-07-01T01:00:00+03:00").put("endsAt","2028-07-01T01:30:00+03:00");
        var first=versions.submit(command(base,"outage-"+UUID.randomUUID(),outage),"dispatcher",false).version().scenarioId();
        assertEquals(1,versions.source(base).path("resourceAvailability").size());
        assertEquals(2,versions.source(first).path("resourceAvailability").size()); assertEquals(1,versions.source(first).path("resourceOutages").size());
        var cancel=json.createObjectNode().put("kind","TRIP_CANCEL").put("trainId",trip.path("train_id").asText()).put("tripId",trip.path("id").asText());
        UUID second=versions.submit(command(first,"cancel-"+UUID.randomUUID(),cancel),"dispatcher",false).version().scenarioId();
        assertEquals(3,versions.source(first).path("fixedTrips").size()); assertEquals(2,versions.source(second).path("fixedTrips").size());
        var add=json.createObjectNode().put("kind","TRIP_ADD").put("trainId",trip.path("train_id").asText()).put("tripId",UUID.randomUUID().toString())
                .put("departureAt","2028-07-01T00:20:00+03:00").put("arrivalAt","2028-07-01T00:50:00+03:00")
                .put("distanceKm",670).put("label","R1-REPLACED").put("origin","TEST_DEPOT").put("destination","TEST_DEPOT");
        UUID third=versions.submit(command(second,"add-"+UUID.randomUUID(),add),"dispatcher",false).version().scenarioId();
        assertEquals(3,versions.source(third).path("fixedTrips").size()); assertEquals(4,versions.versions(base).size());
    }

    @Test void newDraftNeverReplacesEffectiveApprovedPlanAndStaleSourceCannotBeApproved() throws Exception {
        UUID base=fixture(); var api=new PlannerApi(mvc); var originalJob=api.runJob(base.toString(),"approved-"+UUID.randomUUID());
        var original=api.plan(originalJob); assertEquals("PASS",original.path("validationStatus").asText(),original.toString());
        api.postJson("/api/v1/plans/"+original.path("id").asText()+"/approve","{\"expectedVersion\":0}",200);
        UUID approved=UUID.fromString(original.path("id").asText()); assertEquals(approved,persisted.currentPlan(base));
        var stale=api.plan(api.runJob(base.toString(),"old-draft-"+UUID.randomUUID()));
        var changed=versions.submit(command(base,"plan-change-"+UUID.randomUUID(),shifted(base,5)),"dispatcher",false);
        UUID next=changed.version().scenarioId();
        api.postJson("/api/v1/plans/"+stale.path("id").asText()+"/approve","{\"expectedVersion\":0}",422);
        var result=api.plan(api.runJob(next.toString(),"new-draft-"+UUID.randomUUID()));
        assertEquals(changed.version().snapshotHash(),result.path("snapshotHash").asText()); assertEquals("PASS",result.path("validationStatus").asText(),result.toString());
        assertEquals(approved,persisted.currentPlan(next)); assertFalse(persisted.selection(next).effectivePlanUsesCurrentSource());
        var id=UUID.fromString(result.path("id").asText()); assertEquals(id,persisted.selection(next).latestDraftId());
        var receipt=versions.receipt(changed.id()); assertEquals("VALIDATED",receipt.status()); assertEquals(id,receipt.planId());
        api.postJson("/api/v1/plans/"+id+"/approve","{\"expectedVersion\":0}",200);
        assertEquals(id,persisted.currentPlan(base)); assertTrue(persisted.selection(base).effectivePlanUsesCurrentSource());
        assertEquals("APPROVED",versions.receipt(changed.id()).status());
        assertEquals(List.of("RECEIVED","IN_CALCULATION","CALCULATED","VALIDATED","APPROVED"),versions.history(changed.id()).stream().map(RequestDto.Update::status).toList());
        var reopened=new DatabasePlanningRepository(jdbc,new SourceVersionService(jdbc,new com.vsm.okno.data.SourceSnapshotRepository(jdbc)));
        assertEquals(id,reopened.currentPlan(base)); assertEquals("APPROVED",reopened.plan(id).status);
        assertEquals("SUCCEEDED",reopened.job(UUID.fromString(originalJob.path("jobId").asText())).status);
    }

    @Test void rulesRequireTechnologistAndPreserveHistoricalRulesAndCredits() throws Exception {
        UUID base=fixture(); var oldSource=versions.source(base); var oldRules=oldSource.path("scenario").path("rule_set_id").asText();
        var rules=json.createArrayNode();
        rules.add(json.createObjectNode().put("code","IS100").put("intervalKm",12500).put("toleranceBasisPoints",1000).put("durationMinutes",25).put("rank",1).set("resourceIds",json.createArrayNode().add("PATH")));
        rules.add(json.createObjectNode().put("code","IS200").put("intervalKm",25000).put("toleranceBasisPoints",2000).put("durationMinutes",35).put("rank",2).set("resourceIds",json.createArrayNode().add("PATH")));
        var change=json.createObjectNode().put("kind","RULE_CHANGE").put("version","test-rule-"+UUID.randomUUID())
                .put("confirmationStatus","SYNTHETIC").put("mileagePolicy","ABSOLUTE_GRID").put("toleranceBasis","NOMINAL_MILESTONE").set("rules",rules);
        var command=command(base,"rules-"+UUID.randomUUID(),change); String body=json.writeValueAsString(command);
        for (String role:List.of("PLANNER","DISPATCHER")) mvc.perform(PlannerApi.post("/api/v1/scenarios/"+base+"/rule-versions")
                .with(user(role.toLowerCase()).roles(role)).contentType("application/json").content(body)).andExpect(status().isForbidden());
        var saved=json.readTree(mvc.perform(PlannerApi.post("/api/v1/scenarios/"+base+"/rule-versions").with(user("technologist").roles("TECHNOLOGIST"))
                .contentType("application/json").content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        UUID next=UUID.fromString(saved.path("version").path("scenarioId").asText());
        assertEquals(oldSource,versions.source(base)); assertNotEquals(oldRules,versions.source(next).path("scenario").path("rule_set_id").asText());
        assertEquals(12500L,jdbc.queryForObject("select credited_nominal_km from vsm.cycle_baseline where scenario_id=? and cycle_code='IS100' and rule_set_id=(select rule_set_id from vsm.scenario where id=?)",Long.class,next,next));
        assertEquals("technologist",saved.path("reportedBy").asText());
    }

    @Test @Transactional void expiredLeaseCanBeReclaimedAndOldWorkerCannotPublishOrFailTheNewOwner() {
        UUID base=fixture(); var v=versions.version(base); UUID owner1=UUID.randomUUID(),owner2=UUID.randomUUID();
        var job=persisted.enqueue(new DatabasePlanningRepository.Parameters(base,v.snapshotId(),v.snapshotHash(),"BLOCKS_CP_SAT",1,30,0),"planner","lease-"+UUID.randomUUID());
        var first=persisted.claim(owner1).orElseThrow(); assertEquals(job.id,first.job().id);
        jdbc.update("update vsm.planning_job set lease_until=now()-interval '1 second' where id=?",job.id);
        var second=persisted.claim(owner2).orElseThrow(); assertEquals(job.id,second.job().id);
        assertFalse(persisted.heartbeat(job.id,owner1)); assertTrue(persisted.heartbeat(job.id,owner2));
        persisted.fail(first,"OLD_WORKER","must not publish"); assertEquals("RUNNING",persisted.job(job.id).status);
        var plan=new Store.Plan(); plan.id=UUID.randomUUID(); assertFalse(persisted.complete(first,plan));
        persisted.fail(second,"TEST_FAILURE","new owner alone can finish"); assertEquals("FAILED",persisted.job(job.id).status);
    }

    @Test void notificationCursorReplaysAndIdentifiersCannotBeSuppliedWithoutRights() throws Exception {
        UUID base=fixture(); long cursor=jdbc.queryForObject("select coalesce(max(sequence),0) from vsm.request_event",Long.class);
        var saved=post(command(base,"notification-"+UUID.randomUUID(),shifted(base,5)),"DISPATCHER",201);
        var update=versions.updates(cursor); assertTrue(update.events().stream().anyMatch(e -> e.requestId().toString().equals(saved.path("id").asText())));
        assertEquals(update,versions.updates(cursor)); assertTrue(versions.updates(update.nextCursor()).events().isEmpty());
        mvc.perform(PlannerApi.post("/api/v1/change-requests").with(user("other").roles("OTHER")).contentType("application/json")
                .content(json.writeValueAsString(command(base,"forbidden-"+UUID.randomUUID(),shifted(base,6))))).andExpect(status().isForbidden());
        mvc.perform(PlannerApi.post("/api/v1/planning-jobs").with(user("dispatcher").roles("DISPATCHER")).contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
    }
}
