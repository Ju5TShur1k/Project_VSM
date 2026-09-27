package com.vsm.okno;

import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.fail;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Test-side client for the planning API; runs as whatever user the calling test has. */
class PlannerApi {
    final MockMvc mvc;
    final ObjectMapper json = new ObjectMapper();

    PlannerApi(MockMvc mvc) {
        this.mvc = mvc;
    }

    // Every POST needs the CSRF token now that the API is behind a session.
    static MockHttpServletRequestBuilder post(String url) {
        return MockMvcRequestBuilders.post(url).with(csrf());
    }

    JsonNode postJson(String url, String body, int expectedStatus) throws Exception {
        String text = mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
        return text.isEmpty() ? null : json.readTree(text);
    }

    JsonNode getJson(String url) throws Exception {
        return json.readTree(mvc.perform(get(url)).andReturn().getResponse().getContentAsString());
    }

    String importScenario() throws Exception {
        return postJson("/api/v1/scenarios/import", "{}", 201).get("scenarioId").asText();
    }

    /** Injects a failure and returns the id of the new scenario version. */
    String addFailure(String scenarioId, String kind) throws Exception {
        return postJson("/api/v1/scenarios/" + scenarioId + "/events",
                "{\"kind\":\"" + kind + "\",\"description\":\"test\"}", 201).get("newScenarioId").asText();
    }

    static String jobBody(String scenarioId, String key) {
        return "{\"scenarioId\":\"" + scenarioId + "\",\"policy\":\"BLOCKS_CP_SAT\",\"seed\":1,"
                + "\"timeLimitSec\":30,\"idempotencyKey\":\"" + key + "\"}";
    }

    /** Starts a job and waits until it leaves QUEUED/RUNNING. */
    JsonNode runJob(String scenarioId, String key) throws Exception {
        String jobId = postJson("/api/v1/planning-jobs", jobBody(scenarioId, key), 202).get("jobId").asText();
        for (int i = 0; i < 900; i++) {
            JsonNode job = getJson("/api/v1/planning-jobs/" + jobId);
            String status = job.get("status").asText();
            if (!status.equals("QUEUED") && !status.equals("RUNNING")) return job;
            Thread.sleep(100);
        }
        return fail("job " + jobId + " did not finish within 90 s");
    }

    JsonNode plan(JsonNode job) throws Exception {
        return getJson("/api/v1/plans/" + job.get("planId").asText());
    }
}
