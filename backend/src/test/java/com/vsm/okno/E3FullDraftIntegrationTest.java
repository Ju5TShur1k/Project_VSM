package com.vsm.okno;

import com.vsm.okno.data.E3FullDraftService;
import com.vsm.okno.data.SourceSnapshotRepository;
import com.vsm.okno.planning.E3JointFullPlanner;
import com.vsm.okno.service.PlanningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "D1_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class E3FullDraftIntegrationTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("D1_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("D1_TEST_DB_USER", "okno"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("D1_TEST_DB_PASSWORD", "okno"));
    }

    @Autowired PlanningService planning;
    @Autowired SourceSnapshotRepository snapshots;
    @Autowired E3FullDraftService drafts;

    @Test
    @Transactional
    void fullFleetCandidateKeepsTheExactSourceAndNeverClaimsD2() {
        var dataset = planning.importCaseDataset("FULL43");
        var snapshot = snapshots.findById(dataset.source().snapshotId()).orElseThrow();
        OffsetDateTime start = OffsetDateTime.parse(new ObjectMapper()
                .readTree(snapshot.canonicalPayload()).path("scenario").path("horizon_start").asText());
        var result = drafts.calculate(snapshot.id(),
                new E3JointFullPlanner.Input(start, 55, Integer.getInteger("e3MaxMoves", 3),
                        Integer.getInteger("e3Evaluations", 8), 1, 8), "e3-test");
        assertEquals(snapshot.snapshotHash(), result.snapshotHash());
        assertEquals(1428, result.tripCount());
        assertEquals("NOT_PERFORMED", result.d2Status());
        if (result.planId() != null) {
            var calendar = planning.getCalendar(result.planId());
            assertNotNull(calendar);
            assertFalse(calendar.independentlyValidated());
            assertEquals(snapshot.snapshotHash(), calendar.snapshotHash());
            assertTrue(calendar.events().size() >= 1428);
            assertEquals("NOT_PERFORMED", planning.getPlan(result.planId()).validationStatus());
        } else {
            assertFalse("MODEL_CANDIDATE_FOUND".equals(result.searchStatus()));
            assertFalse("FEASIBLE".equals(result.modelSolverStatus())
                    || "OPTIMAL".equals(result.modelSolverStatus()));
        }
        System.out.printf("FULL43 E3: search=%s moves=%d changed=%d cleanings=%d solver=%s blocks=%d plan=%s%n",
                result.searchStatus(), result.moves(), result.changedTripCount(),
                result.requiredCleaningCount(), result.modelSolverStatus(),
                result.placedBlockCount(), result.planId());
        result.diagnostics().stream().limit(3).forEach(d ->
                System.out.printf("FULL43 blocker: %s %s%n", d.code(), d.message()));
    }
}
