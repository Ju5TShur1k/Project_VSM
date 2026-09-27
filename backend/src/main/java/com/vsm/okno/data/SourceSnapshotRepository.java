package com.vsm.okno.data;

import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** D1 persistence boundary. Capturing source facts does NOT validate or approve a plan. */
@Repository
@Profile("database")
public class SourceSnapshotRepository {
    public record SourceSnapshot(UUID id, UUID scenarioId, String schemaVersion,
                                 String canonicalization, String snapshotHash,
                                 String canonicalPayload, Instant capturedAt) {}

    private static final RowMapper<SourceSnapshot> MAPPER = (rs, row) -> new SourceSnapshot(
            rs.getObject("id", UUID.class), rs.getObject("scenario_id", UUID.class),
            rs.getString("schema_version"), rs.getString("canonicalization"),
            rs.getString("snapshot_hash"), rs.getString("canonical_payload"),
            rs.getTimestamp("captured_at").toInstant());

    private final JdbcTemplate jdbc;

    public SourceSnapshotRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Same source content returns the existing immutable snapshot, including its original ID. */
    public SourceSnapshot capture(UUID scenarioId) {
        return jdbc.queryForObject("select * from vsm.capture_snapshot(?, ?)",
                MAPPER, scenarioId, UUID.randomUUID());
    }

    public Optional<SourceSnapshot> findById(UUID snapshotId) {
        return jdbc.query("select * from vsm.scenario_snapshot where id = ?", MAPPER, snapshotId)
                .stream().findFirst();
    }
}
