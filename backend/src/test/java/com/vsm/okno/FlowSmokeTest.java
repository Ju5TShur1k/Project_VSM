package com.vsm.okno;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// End-to-end through the real CP-SAT planner: import -> async job -> plan -> approve,
// plus T11/T12 (idempotent jobs, optimistic-locked approval). A stand-in validator
// that reports no violations plays D2, so the approval path is reachable.
// Needs the OR-Tools natives: see the JDK note in README.
// DirtiesContext: csrf() patches the CSRF repository inside the cached context.
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser("tester")
@DirtiesContext
@Import(FlowSmokeTest.CleanValidator.class)
class FlowSmokeTest {

    @TestConfiguration
    static class CleanValidator {
        @Bean
        com.vsm.okno.service.PlanValidator planValidator() {
            return (snapshot, result) -> List.of();
        }
    }

    @Autowired
    MockMvc mvc;

    @Test
    void importJobPlanApproveFlow() throws Exception {
        PlannerApi api = new PlannerApi(mvc);
        String scenarioId = api.importScenario();

        mvc.perform(get("/api/v1/scenarios/" + scenarioId + "/trains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(43));

        JsonNode job = api.runJob(scenarioId, "key-1");
        assertEquals("SUCCEEDED", job.get("status").asText());
        assertTrue(List.of("OPTIMAL", "FEASIBLE").contains(job.get("solverStatus").asText()), job.toString());
        String planId = job.get("planId").asText();

        // Retrying the same idempotency key returns the same job, not a second run.
        JsonNode replay = api.postJson("/api/v1/planning-jobs", PlannerApi.jobBody(scenarioId, "key-1"), 202);
        assertEquals(job.get("jobId").asText(), replay.get("jobId").asText());

        // 43 trains - 4 in hot reserve = 39 working trains, one IS100 block each.
        JsonNode plan = api.plan(job);
        assertEquals("DRAFT", plan.get("status").asText());
        assertEquals(39, plan.get("events").size());

        api.postJson("/api/v1/plans/" + planId + "/approve",
                "{\"expectedVersion\":0,\"actorId\":\"someone-else\",\"comment\":\"ok\"}", 200);
        // approver comes from the session, not from the request's actorId
        assertEquals("tester", api.getJson("/api/v1/plans/" + planId).get("approvedBy").asText());

        api.postJson("/api/v1/plans/" + planId + "/approve",
                "{\"expectedVersion\":0,\"comment\":\"stale\"}", 409);
    }
}
