package com.vsm.okno;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Self-check for the mock planning flow: import -> job -> plan -> approve,
// plus the two rules the acceptance tests (T11/T12) care about: idempotent
// job creation and optimistic-locked approval. Runs as a logged-in user.
// DirtiesContext: csrf() patches the CSRF repository inside the cached context.
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser("tester")
@DirtiesContext
class FlowSmokeTest {

    @Autowired
    MockMvc mvc;

    // Every POST needs the CSRF token now that the API is behind a session.
    static MockHttpServletRequestBuilder post(String url) {
        return MockMvcRequestBuilders.post(url).with(csrf());
    }

    final ObjectMapper json = new ObjectMapper();

    @Test
    void importJobPlanApproveFlow() throws Exception {
        String importBody = mvc.perform(post("/api/v1/scenarios/import")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String scenarioId = json.readTree(importBody).get("scenarioId").asText();

        mvc.perform(get("/api/v1/scenarios/" + scenarioId + "/trains"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(43));

        String jobBody = String.format(
                "{\"scenarioId\":\"%s\",\"policy\":\"BLOCKS_CP_SAT\",\"seed\":1,\"timeLimitSec\":5,\"idempotencyKey\":\"key-1\"}",
                scenarioId);
        String jobResp = mvc.perform(post("/api/v1/planning-jobs")
                        .contentType(MediaType.APPLICATION_JSON).content(jobBody))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        JsonNode job = json.readTree(jobResp);
        String jobId = job.get("jobId").asText();
        String planId = job.get("planId").asText();
        assertEquals("SUCCEEDED", job.get("status").asText());

        String jobResp2 = mvc.perform(post("/api/v1/planning-jobs")
                        .contentType(MediaType.APPLICATION_JSON).content(jobBody))
                .andExpect(status().isAccepted())
                .andReturn().getResponse().getContentAsString();
        assertEquals(jobId, json.readTree(jobResp2).get("jobId").asText());

        mvc.perform(get("/api/v1/plans/" + planId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT"));

        mvc.perform(post("/api/v1/plans/" + planId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"actorId\":\"tester\",\"comment\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                // approver comes from the session, not from the request's actorId
                .andExpect(jsonPath("$.approvedBy").value("tester"));

        mvc.perform(post("/api/v1/plans/" + planId + "/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"actorId\":\"tester\",\"comment\":\"stale\"}"))
                .andExpect(status().isConflict());
    }
}
