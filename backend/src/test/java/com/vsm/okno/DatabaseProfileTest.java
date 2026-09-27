package com.vsm.okno;

import com.vsm.okno.data.SourceSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Opt-in: use a dedicated EMPTY PostgreSQL test database. Never a production URL. */
@SpringBootTest
@ActiveProfiles("database")
@EnabledIfEnvironmentVariable(named = "D1_TEST_DB_URL", matches = "jdbc:postgresql:.*")
class DatabaseProfileTest {
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("D1_TEST_DB_URL"));
        registry.add("spring.datasource.username", () -> System.getenv().getOrDefault("D1_TEST_DB_USER", "okno"));
        registry.add("spring.datasource.password", () -> System.getenv().getOrDefault("D1_TEST_DB_PASSWORD", "okno"));
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired SourceSnapshotRepository snapshots;

    @Test
    void databaseProfileAppliesMigrationsAndCreatesSnapshotStorage() {
        assertEquals(3, jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success and type = 'SQL'", Integer.class));
        assertEquals(17, jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = 'vsm' and table_type = 'BASE TABLE'", Integer.class));
        // Known SHA-256 of the exact canonical bytes "{}" (no newline).
        assertEquals("44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a",
                jdbc.queryForObject("select vsm.canonical_sha256('{}'::jsonb)", String.class));
    }

    @Test
    @Transactional
    void capturesCanonicalUtf8AndKeepsPreviousVersionWhenSourceChanges() throws Exception {
        UUID rules = UUID.randomUUID();
        UUID scenario = UUID.randomUUID();
        jdbc.update("""
                insert into vsm.rule_set(id, version, source, confirmation_status, mileage_policy, tolerance_basis)
                values (?, ?, 'test', 'SYNTHETIC', 'UNCONFIRMED', 'UNCONFIRMED')
                """, rules, "test-" + rules);
        jdbc.update("""
                insert into vsm.scenario(id, name, rule_set_id, horizon_start, horizon_end, provenance)
                values (?, 'Сценарий проверки', ?, '2028-07-01T00:00:00+03:00', '2028-07-02T00:00:00+03:00', 'synthetic')
                """, scenario, rules);
        var first = snapshots.capture(scenario);
        assertEquals(first, snapshots.capture(scenario));
        assertEquals(first, snapshots.findById(first.id()).orElseThrow());
        assertEquals(first.snapshotHash(), HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(first.canonicalPayload().getBytes(StandardCharsets.UTF_8))));
        jdbc.update("update vsm.scenario set name = 'Изменённый сценарий' where id = ?", scenario);
        var second = snapshots.capture(scenario);
        assertNotEquals(first.snapshotHash(), second.snapshotHash());
        assertEquals(first, snapshots.findById(first.id()).orElseThrow());
    }
}
