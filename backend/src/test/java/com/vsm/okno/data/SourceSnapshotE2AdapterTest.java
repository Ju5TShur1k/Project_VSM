package com.vsm.okno.data;

import com.vsm.okno.planning.CpSatPlanner;
import com.vsm.okno.planning.PlannerRequest;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.PlanCalendarProjector;
import com.vsm.okno.planning.MileageObligationGenerator;
import com.vsm.okno.validation.IndependentIntervalAudit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Opt-in DB-to-solver contract check on a dedicated empty test database. */
@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "D1_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class SourceSnapshotE2AdapterTest {
    private static final UUID BASE = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID E2 = UUID.fromString("20000000-0000-0000-0000-000000000002");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("D1_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("D1_TEST_DB_USER", "okno"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("D1_TEST_DB_PASSWORD", "okno"));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired SourceSnapshotRepository snapshots;
    @Autowired DemoSourceService demoSources;

    @Test
    @Transactional
    void changingSourceTripCreatesNewHashAndMovesCalculatedCalendar() {
        var first = demoSources.create();
        var oldStored = snapshots.findById(first.snapshotId()).orElseThrow();
        var before = new SourceSnapshotE2Adapter().project(oldStored);
        var beforeCalendar = calendar(before);
        assertEquals(50, before.snapshot().fixedTrips().stream()
                .filter(t -> t.label().equals("R1")).findFirst().orElseThrow().endMinute());
        assertEquals(before.snapshot().horizonStart().plusMinutes(50).toString(),
                beforeCalendar.events().stream().filter(e -> e.kind().equals("SERVICE"))
                        .findFirst().orElseThrow().startAt());

        var changed = demoSources.changeR1Arrival(first.scenarioId(), 55);
        assertNotEquals(first.snapshotHash(), changed.snapshotHash());
        assertNotEquals(first.snapshotId(), changed.snapshotId());
        var after = new SourceSnapshotE2Adapter().project(snapshots.findById(changed.snapshotId()).orElseThrow());
        var afterCalendar = calendar(after);
        assertEquals(changed.snapshotHash(), afterCalendar.snapshotHash());
        assertEquals(after.snapshot().horizonStart().plusMinutes(55).toString(),
                afterCalendar.events().stream().filter(e -> e.kind().equals("SERVICE"))
                        .findFirst().orElseThrow().startAt());
        assertEquals(50, new SourceSnapshotE2Adapter().project(
                snapshots.findById(first.snapshotId()).orElseThrow()).snapshot().fixedTrips().stream()
                .filter(t -> t.label().equals("R1")).findFirst().orElseThrow().endMinute());
    }

    private com.vsm.okno.dto.Dto.PlanCalendar calendar(MileageObligationGenerator.Projection projection) {
        var source = projection.snapshot();
        var result = new CpSatPlanner().plan(source, new PlannerRequest("1.0", source.scenarioId(),
                source.snapshotHash(), PlannerRequest.Policy.BLOCKS_CP_SAT, 42, 5));
        assertEquals(PlannerResult.SolverStatus.OPTIMAL, result.solverStatus());
        var obligations = projection.obligations().stream().collect(Collectors.toMap(
                MileageObligationGenerator.Obligation::blockId, Function.identity()));
        return PlanCalendarProjector.project(source, result, obligations, "NOT_PERFORMED");
    }

    @Test
    @Transactional
    void persistedSnapshotFeedsRealSolverAndUnsupportedE3FactsFailClosed() {
        load("../database/demo/seed_synthetic.sql");
        load("../database/demo/seed_e2_adapter.sql");

        var stored = snapshots.capture(E2);
        var projected = new SourceSnapshotE2Adapter().project(stored);
        assertEquals(stored.snapshotHash(), projected.snapshot().snapshotHash());
        assertEquals(3, projected.snapshot().fixedTrips().size());
        assertEquals("IS200", projected.obligations().getFirst().cycleCode());
        assertEquals(1, projected.snapshot().blocks().size());
        assertEquals(1, projected.snapshot().operations().serviceWindows().size());

        var source = projected.snapshot();
        var result = new CpSatPlanner().plan(source, new PlannerRequest("1.0", source.scenarioId(),
                source.snapshotHash(), PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT, 42, 5));
        assertEquals(PlannerResult.SolverStatus.OPTIMAL, result.solverStatus());
        assertEquals(source.horizonStart().plusMinutes(50), result.blocks().getFirst().startAt());
        assertEquals(source.horizonStart().plusMinutes(80), result.blocks().getFirst().endAt());
        assertTrue(IndependentIntervalAudit.check(source, result).isEmpty());

        jdbc.update("UPDATE vsm.train_presence SET confirmation_status = 'UNCONFIRMED' WHERE scenario_id = ?::uuid",
                E2.toString());
        var unconfirmed = snapshots.capture(E2);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new SourceSnapshotE2Adapter().project(unconfirmed)).getMessage()
                .contains("unconfirmed train presence"));
        jdbc.update("UPDATE vsm.train_presence SET confirmation_status = 'SYNTHETIC' WHERE scenario_id = ?::uuid",
                E2.toString());

        load("../database/demo/seed_operational.sql");
        var e3 = snapshots.capture(BASE);
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> new SourceSnapshotE2Adapter().project(e3)).getMessage().contains("requires the E3 adapter"));
    }

    private void load(String path) {
        Connection connection = DataSourceUtils.getConnection(jdbc.getDataSource());
        try {
            ScriptUtils.executeSqlScript(connection, new FileSystemResource(path));
        } finally {
            DataSourceUtils.releaseConnection(connection, jdbc.getDataSource());
        }
    }
}
