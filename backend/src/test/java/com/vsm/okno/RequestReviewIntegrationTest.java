package com.vsm.okno;

import com.vsm.okno.requests.RequestReviewService;
import com.vsm.okno.requests.ScheduleImportService;
import com.vsm.okno.requests.SourceVersionService;
import com.vsm.okno.requests.UiRequestService;
import com.vsm.okno.service.PlanningService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Planner proposes, dispatcher decides; CSV upload is one source version. Real PostgreSQL. */
@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "D1_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class RequestReviewIntegrationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("D1_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("D1_TEST_DB_USER", "okno"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("D1_TEST_DB_PASSWORD", "okno"));
    }
    @Autowired PlanningService service;
    @Autowired SourceVersionService versions;
    @Autowired RequestReviewService reviews;
    @Autowired ScheduleImportService schedules;
    private final ObjectMapper json = new ObjectMapper();

    private JsonNode r1(UUID id) {
        for (var trip : versions.source(versions.head(id).scenarioId()).path("fixedTrips"))
            if (trip.path("label").asText().equals("R1")) return trip;
        return fail("R1 missing");
    }

    private UiRequestService.NewRequest shift(UUID id, int minutes, String clientId) {
        var trip = r1(id);
        var payload = json.createObjectNode().put("kind", "TRIP_CHANGE").put("trainId", trip.path("train_id").asText())
                .put("tripId", trip.path("id").asText())
                .put("newDepartureAt", OffsetDateTime.parse(trip.path("departure_at").asText()).plusMinutes(minutes).toString())
                .put("newArrivalAt", OffsetDateTime.parse(trip.path("arrival_at").asText()).plusMinutes(minutes).toString())
                .put("reason", "Задержка").put("source", "Планировщик");
        return new UiRequestService.NewRequest(clientId, "", payload, "");
    }

    @Test
    void rejectedProposalChangesNothingAndApprovedOneCreatesAVersion() {
        UUID root = versions.head(service.importDemoSource().scenarioId()).rootId();
        int start = versions.head(root).version();

        var first = reviews.propose(root, shift(root, 5, "a"), "planner");
        assertEquals("PENDING", first.status());
        assertEquals(first.id(), reviews.propose(root, shift(root, 5, "a"), "planner").id(), "a retried click is the same proposal");
        assertThrows(PlanningService.InvalidRequestException.class,
                () -> reviews.reject(first.id(), new RequestReviewService.Decision(" "), "dispatcher"), "a rejection needs a reason");
        var rejected = reviews.reject(first.id(), new RequestReviewService.Decision("Рейс ушёл по графику"), "dispatcher");
        assertEquals("REJECTED", rejected.status());
        assertEquals(start, versions.head(root).version(), "rejection must not touch source data");

        var second = reviews.propose(root, shift(root, 5, "b"), "planner");
        String before = versions.head(root).snapshotHash();
        var approved = reviews.approve(second.id(), new RequestReviewService.Decision(""), "dispatcher");
        assertEquals("APPROVED", approved.status());
        assertEquals("dispatcher", approved.reviewedBy());
        assertEquals(start + 1, versions.head(root).version());
        assertNotEquals(before, versions.head(root).snapshotHash());
        assertEquals(versions.head(root).snapshotHash(), approved.applied().newSnapshotHash());
        assertEquals("planner", approved.applied().createdBy(), "the change stays authored by the planner");
        assertThrows(PlanningService.VersionConflictException.class,
                () -> reviews.approve(second.id(), null, "dispatcher"), "a decision is final");
    }

    @Test
    void scheduleCsvIsOneVersionAndUnchangedRowsAreSkipped() {
        UUID root = versions.head(service.importDemoSource().scenarioId()).rootId();
        int start = versions.head(root).version();
        var trip = r1(root);
        var moscow = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
        String dep = OffsetDateTime.parse(trip.path("departure_at").asText()).plusMinutes(3).atZoneSameInstant(ZoneOffset.ofHours(3)).format(moscow);
        String arr = OffsetDateTime.parse(trip.path("arrival_at").asText()).plusMinutes(3).atZoneSameInstant(ZoneOffset.ofHours(3)).format(moscow);
        String csv = "рейс;состав;отправление;прибытие\nR1;EVS-SYN-1;" + dep + ";" + arr + "\n";

        var result = schedules.importCsv(root, csv, "planner");
        assertEquals(1, result.changed());
        assertEquals(start + 1, versions.head(root).version());
        assertThrows(PlanningService.InvalidRequestException.class, () -> schedules.importCsv(root, csv, "planner"),
                "the same file again has no changes and must not create an empty version");
        assertThrows(PlanningService.InvalidRequestException.class,
                () -> schedules.importCsv(root, "рейс;состав;отправление;прибытие\nR1;NOPE;" + dep + ";" + arr, "planner"));
    }
}
