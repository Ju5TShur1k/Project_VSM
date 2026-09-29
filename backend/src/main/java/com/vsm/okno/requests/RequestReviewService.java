package com.vsm.okno.requests;

import com.vsm.okno.service.PlanningService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Planner proposes, dispatcher decides. A proposal is stored as is and changes nothing;
 * approval turns a schedule change into a new source version (through UiRequestService,
 * without auto-calculation: the planner recalculates), rejection only records the answer.
 * A train failure is decided on the operations board (replacement from reserve); its
 * approval records that decision and does not create a source version.
 */
@Service
@Profile("database")
public class RequestReviewService {
    static final Set<String> KINDS = Set.of("TRIP_CHANGE", "URGENT_MAINTENANCE", "TRAIN_FAILURE");

    public record Proposal(UUID id, long number, UUID scenarioId, String status, JsonNode payload,
                           String comment, String createdBy, Instant createdAt,
                           String reviewedBy, Instant reviewedAt, String reviewComment,
                           UiRequestService.ChangeRequest applied) {}
    public record Decision(String comment) {}

    private final JdbcTemplate jdbc;
    private final SourceVersionService versions;
    private final UiRequestService requests;
    private final ObjectMapper json = new ObjectMapper();

    public RequestReviewService(JdbcTemplate jdbc, SourceVersionService versions, UiRequestService requests) {
        this.jdbc = jdbc; this.versions = versions; this.requests = requests;
    }

    @Transactional public Proposal propose(UUID scenarioId, UiRequestService.NewRequest command, String actor) {
        check(command != null && command.payload() != null && command.payload().isObject(), "payload", "must be an object");
        check(command.clientRequestId() != null && !command.clientRequestId().isBlank()
                && command.clientRequestId().length() <= 160, "clientRequestId", "must be 1..160 characters");
        check(KINDS.contains(command.payload().path("kind").asText()), "kind", "must be one of " + KINDS);
        check(command.payload().path("trainId").isTextual(), "trainId", "is required");
        check(command.comment() == null || command.comment().length() <= 1000, "comment", "must be at most 1000 characters");
        UUID root = versions.version(scenarioId).rootId();
        // Same click retried: same proposal, never a second one.
        var existing = jdbc.queryForList("select id from vsm.request_proposal where proposed_by=? and client_request_id=?",
                UUID.class, actor, command.clientRequestId());
        if (!existing.isEmpty()) return get(existing.getFirst());
        UUID id = UUID.randomUUID();
        jdbc.update("insert into vsm.request_proposal(id,root_id,client_request_id,proposed_by,body) values (?,?,?,?,?::jsonb)",
                id, root, command.clientRequestId(), actor, json.writeValueAsString(command));
        return get(id);
    }

    public List<Proposal> list(UUID scenarioId) {
        UUID root = scenarioId == null ? null : versions.version(scenarioId).rootId();
        return jdbc.queryForList("select id from vsm.request_proposal where (?::uuid is null or root_id=?) order by number desc limit 200",
                UUID.class, root, root).stream().map(this::get).toList();
    }

    @Transactional public Proposal approve(UUID id, Decision decision, String actor) {
        var row = lockUndecided(id);
        JsonNode body = json.readTree(row.body());
        JsonNode payload = body.path("payload");
        UUID requestId = null;
        if (!"TRAIN_FAILURE".equals(payload.path("kind").asText())) {
            // Applied on the current head: data may have moved on since the proposal.
            var head = versions.head(row.root());
            var command = new UiRequestService.NewRequest("proposal:" + id, head.snapshotHash(), payload,
                    body.path("comment").asText(""));
            requestId = requests.submit(head.scenarioId(), command, row.proposedBy(), false).request().id();
        }
        jdbc.update("insert into vsm.request_review(proposal_id,decision,reviewed_by,comment,request_id) values (?,'APPROVED',?,?,?)",
                id, actor, comment(decision), requestId);
        return get(id);
    }

    @Transactional public Proposal reject(UUID id, Decision decision, String actor) {
        lockUndecided(id);
        String reason = comment(decision);
        check(!reason.isBlank(), "comment", "a rejection needs a reason");
        jdbc.update("insert into vsm.request_review(proposal_id,decision,reviewed_by,comment) values (?,'REJECTED',?,?)",
                id, actor, reason);
        return get(id);
    }

    public Proposal get(UUID id) {
        return jdbc.query("""
                select p.id,p.number,p.root_id,p.body::text,p.proposed_by,p.proposed_at,
                       r.decision,r.reviewed_by,r.reviewed_at,r.comment,r.request_id
                from vsm.request_proposal p left join vsm.request_review r on r.proposal_id=p.id where p.id=?
                """, this::map, id).stream().findFirst()
                .orElseThrow(() -> new PlanningService.NotFoundException("proposal not found: " + id));
    }

    private Proposal map(ResultSet rs, int n) throws SQLException {
        JsonNode body = json.readTree(rs.getString(4));
        String decision = rs.getString(7);
        UUID requestId = rs.getObject(11, UUID.class);
        return new Proposal(rs.getObject(1, UUID.class), rs.getLong(2), rs.getObject(3, UUID.class),
                decision == null ? "PENDING" : decision, body.path("payload"), body.path("comment").asText(""),
                rs.getString(5), rs.getObject(6, java.time.OffsetDateTime.class).toInstant(),
                rs.getString(8), rs.getObject(9, java.time.OffsetDateTime.class) == null ? null
                        : rs.getObject(9, java.time.OffsetDateTime.class).toInstant(),
                rs.getString(10), requestId == null ? null : requests.get(requestId));
    }

    private record Row(UUID root, String proposedBy, String body) {}

    private Row lockUndecided(UUID id) {
        var rows = jdbc.query("select root_id,proposed_by,body::text from vsm.request_proposal where id=? for update",
                (rs, n) -> new Row(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3)), id);
        if (rows.isEmpty()) throw new PlanningService.NotFoundException("proposal not found: " + id);
        Integer decided = jdbc.queryForObject("select count(*) from vsm.request_review where proposal_id=?", Integer.class, id);
        if (decided != null && decided > 0) throw new PlanningService.VersionConflictException(1);
        return rows.getFirst();
    }

    private static String comment(Decision d) {
        String c = d == null || d.comment() == null ? "" : d.comment().strip();
        check(c.length() <= 1000, "comment", "must be at most 1000 characters");
        return c;
    }

    private static void check(boolean ok, String field, String reason) {
        if (!ok) throw new PlanningService.InvalidRequestException(field, reason);
    }
}
