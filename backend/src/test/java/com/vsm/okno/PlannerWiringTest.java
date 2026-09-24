package com.vsm.okno;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

// Behaviour with the DEFAULT validator (no D2 bean present), and how injected
// failures change what the solver produces. Needs the OR-Tools natives (README).
@SpringBootTest
@AutoConfigureMockMvc
@WithMockUser("tester")
@DirtiesContext
class PlannerWiringTest {

    static final OffsetDateTime HORIZON_START = OffsetDateTime.parse("2028-07-01T00:00:00+03:00");

    @Autowired
    MockMvc mvc;

    @Test
    void unvalidatedPlanCannotBeApproved() throws Exception {
        PlannerApi api = new PlannerApi(mvc);
        JsonNode job = api.runJob(api.importScenario(), "k");
        JsonNode plan = api.plan(job);

        assertEquals(1, plan.get("validations").size());
        assertEquals("VALIDATION_NOT_PERFORMED", plan.get("validations").get(0).get("code").asText());
        assertEquals("CRITICAL", plan.get("validations").get(0).get("severity").asText());

        JsonNode refused = api.postJson("/api/v1/plans/" + plan.get("id").asText() + "/approve",
                "{\"expectedVersion\":0,\"comment\":\"try\"}", 422);
        assertEquals("PLAN_NOT_APPROVABLE", refused.get("code").asText());
        assertEquals("DRAFT", api.getJson("/api/v1/plans/" + plan.get("id").asText()).get("status").asText());
    }

    @Test
    void machineFailureMovesWorkOffTheDownTrack() throws Exception {
        PlannerApi api = new PlannerApi(mvc);
        String base = api.importScenario();

        // Baseline: work on TRACK_1 starts right at the beginning of the horizon.
        assertEquals(0, earliestStartMinutes(api.plan(api.runJob(base, "a")), "TRACK_1"));

        // After a 12 h outage of TRACK_1, nothing may occupy it during the first 720 minutes.
        JsonNode failed = api.runJob(api.addFailure(base, "MACHINE_DOWN"), "b");
        assertEquals("SUCCEEDED", failed.get("status").asText());
        assertTrue(earliestStartMinutes(api.plan(failed), "TRACK_1") >= 720);
    }

    @Test
    void invalidInputIsRejectedNotSilentlyAccepted() throws Exception {
        PlannerApi api = new PlannerApi(mvc);
        String scenario = api.importScenario();

        api.postJson("/api/v1/scenarios/" + scenario + "/events", "{\"kind\":\"METEOR\",\"description\":\"x\"}", 422);
        api.postJson("/api/v1/planning-jobs", PlannerApi.jobBody(scenario, "k").replace("BLOCKS_CP_SAT", "MAGIC"), 422);
        api.postJson("/api/v1/planning-jobs", PlannerApi.jobBody(scenario, "k").replace("\"timeLimitSec\":30", "\"timeLimitSec\":0"), 422);
        api.postJson("/api/v1/planning-jobs", PlannerApi.jobBody(scenario, "k").replace("\"timeLimitSec\":30", "\"timeLimitSec\":99999"), 422);
        assertFalse(api.getJson("/api/v1/scenarios/" + scenario).isEmpty());
    }

    static long earliestStartMinutes(JsonNode plan, String resource) {
        long earliest = Long.MAX_VALUE;
        for (JsonNode event : plan.get("events")) {
            if (!event.get("resourceIds").get(0).asText().equals(resource)) continue;
            long minutes = java.time.Duration.between(HORIZON_START, OffsetDateTime.parse(event.get("startAt").asText())).toMinutes();
            earliest = Math.min(earliest, minutes);
        }
        return earliest;
    }
}
