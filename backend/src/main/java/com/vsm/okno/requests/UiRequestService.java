package com.vsm.okno.requests;

import com.vsm.okno.service.PlanningService;
import com.vsm.okno.store.DatabasePlanningRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.*;

/** Wire adapter for docs/UI_CONTRACT.md. Authors always come from the authenticated session. */
@Service
@Profile("database")
public class UiRequestService {
    public record NewRequest(String clientRequestId, String expectedSnapshotHash, JsonNode payload, String comment) {}
    public record Error(String code, String message) {}
    public record ChangeRequest(UUID id, long number, UUID scenarioId, String baseSnapshotHash, String status,
                                JsonNode payload, String comment, String createdBy, Instant createdAt,
                                String newSnapshotHash, UUID jobId, UUID planId, String validationStatus,
                                String decidedBy, Instant decidedAt, Error error,
                                UUID newScenarioId, UUID snapshotId, int sourceVersion) {}
    public record Submitted(ChangeRequest request, boolean replay) {}
    private final JdbcTemplate jdbc;
    private final SourceVersionService versions;
    private final DatabasePlanningRepository planning;
    private final boolean autoCalculate;
    private final ObjectMapper json = new ObjectMapper();

    public UiRequestService(JdbcTemplate jdbc, SourceVersionService versions, DatabasePlanningRepository planning,
                            @Value("${okno.requests.auto-calculate:true}") boolean autoCalculate) {
        this.jdbc=jdbc; this.versions=versions; this.planning=planning; this.autoCalculate=autoCalculate;
    }

    @Transactional public Submitted submit(UUID scenarioId, NewRequest command, String actor) {
        return submit(scenarioId, command, actor, autoCalculate);
    }

    /** calculate=false: the new source version is stored, the planner starts the recalculation. */
    @Transactional public Submitted submit(UUID scenarioId, NewRequest command, String actor, boolean calculate) {
        check(command!=null,"request","is required");
        bounded(command.clientRequestId(),"clientRequestId",160);
        check(command.expectedSnapshotHash()!=null && command.expectedSnapshotHash().matches("[a-f0-9]{64}"),
                "expectedSnapshotHash","must be a canonical SHA-256");
        check(command.comment()!=null && command.comment().length()<=1000,"comment","must be a string of at most 1000 characters");
        check(command.payload()!=null && command.payload().isObject(),"payload","must be an object");
        String key="ui:"+command.clientRequestId(), body=json.writeValueAsString(command);
        // Serialize identical retries before checking the head; enqueue and metadata share this transaction.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"request-key:"+actor+":"+key);
        var prior=jdbc.query("""
                select r.id,r.root_id,m.body_hash=vsm.canonical_sha256(?::jsonb) same
                from vsm.change_request r join vsm.request_client_metadata m on m.request_id=r.id
                where r.reported_by=? and r.idempotency_key=?
                """,(rs,n)->new Object[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getBoolean(3)},body,actor,key);
        UUID root=versions.version(scenarioId).rootId();
        if (!prior.isEmpty()) {
            if (!(Boolean)prior.getFirst()[2] || !root.equals(prior.getFirst()[1]))
                throw new PlanningService.IdempotencyConflictException(command.clientRequestId());
            return new Submitted(get((UUID)prior.getFirst()[0]),true);
        }
        // A root id is stable in calendars. The hash selects the exact source revision, never a silent refresh.
        var base=versions.versions(scenarioId).stream().filter(v->v.snapshotHash().equals(command.expectedSnapshotHash()))
                .findFirst().orElseThrow(()->new PlanningService.VersionConflictException(versions.head(scenarioId).version()));
        JsonNode payload=command.payload(); String kind=required(payload,"kind"); ObjectNode change=json.createObjectNode().put("kind",kind);
        String reason,source;
        if (kind.equals("TRIP_CHANGE")) {
            fields(payload,Set.of("kind","trainId","tripId","newDepartureAt","newArrivalAt","reason","source"));
            change.put("trainId",required(payload,"trainId")).put("tripId",required(payload,"tripId"))
                    .put("departureAt",required(payload,"newDepartureAt")).put("arrivalAt",required(payload,"newArrivalAt"));
            reason=required(payload,"reason"); source=required(payload,"source");
        } else if (kind.equals("URGENT_MAINTENANCE")) {
            fields(payload,Set.of("kind","trainId","problem","detectedAt","notBeforeTripEnd","urgency","dueBy","workType"));
            check(payload.path("notBeforeTripEnd").isBoolean(),"notBeforeTripEnd","must be explicit true or false");
            String urgency=required(payload,"urgency");
            check(Set.of("IMMEDIATE","WITHIN_24H","WITHIN_HORIZON").contains(urgency),"urgency","is unsupported");
            change.put("trainId",required(payload,"trainId")).put("problemType",required(payload,"problem"))
                    .put("detectedAt",required(payload,"detectedAt")).put("notBeforeCurrentTripEnd",payload.path("notBeforeTripEnd").asBoolean())
                    .put("urgency",urgency).put("workKind",required(payload,"workType"));
            if (!payload.path("dueBy").isNull() && !payload.path("dueBy").isMissingNode()) change.put("deadlineAt",required(payload,"dueBy"));
            reason=required(payload,"problem"); source="Events UI / "+actor;
        } else throw invalid("kind","this UI contract supports TRIP_CHANGE and URGENT_MAINTENANCE");
        var receipt=versions.submit(new RequestDto.Command(base.scenarioId(),base.version(),key,reason,source,change),actor,false);
        jdbc.update("insert into vsm.request_client_metadata(request_id,client_request_id,base_snapshot_hash,body) values (?,?,?,?::jsonb)",
                receipt.id(),command.clientRequestId(),command.expectedSnapshotHash(),body);
        if (calculate) {
            var v=receipt.version();
            planning.scenario(v.scenarioId());
            planning.enqueue(new DatabasePlanningRepository.Parameters(v.scenarioId(),v.snapshotId(),v.snapshotHash(),"BLOCKS_CP_SAT",1,30,0),
                    actor,"request:"+receipt.id());
        }
        return new Submitted(get(receipt.id()),false);
    }

    public List<ChangeRequest> list(UUID scenarioId,int limit) {
        return versions.requests(scenarioId,limit).stream()
                .filter(this::representable).map(r->get(r.id())).toList();
    }
    public ChangeRequest get(UUID id) {
        var r=versions.receipt(id);
        if (!representable(r)) throw new PlanningService.NotFoundException("Request uses the extended contract; open /change-requests/"+id);
        var row=jdbc.queryForMap("select number,base_scenario_id from vsm.change_request where id=?",id);
        var client=jdbc.query("select body::text from vsm.request_client_metadata where request_id=?",(rs,n)->json.readTree(rs.getString(1)),id)
                .stream().findFirst().orElse(null);
        JsonNode payload;
        if (client!=null) payload=client.path("payload");
        else {
            ObjectNode p=json.createObjectNode().put("kind",r.kind()).put("trainId",r.trainId()==null?null:r.trainId().toString());
            if (r.kind().equals("TRIP_CHANGE")) {
                p.put("tripId",r.tripId().toString()).put("newDepartureAt",r.change().path("departureAt").asText())
                        .put("newArrivalAt",r.change().path("arrivalAt").asText()).put("reason",r.reason()).put("source",r.source());
            } else p.put("problem",r.change().path("problemType").asText()).put("detectedAt",r.change().path("detectedAt").asText())
                    .put("notBeforeTripEnd",r.change().path("notBeforeCurrentTripEnd").asBoolean())
                    .put("urgency",r.change().path("urgency").asText()).put("workType",r.change().path("workKind").asText())
                    .set("dueBy",r.change().path("deadlineAt").isMissingNode()?json.nullNode():r.change().path("deadlineAt"));
            payload=p;
        }
        String state=switch(r.status()) { case "RECEIVED","IN_CALCULATION" -> "APPLIED"; case "ERROR" -> "FAILED"; default -> r.status(); };
        var decision=versions.history(id).stream().filter(e->e.status().equals("APPROVED")).reduce((a,b)->b).orElse(null);
        String validation=r.planId()==null?null:planning.plan(r.planId()).validationStatus;
        var v=r.version();
        return new ChangeRequest(id,((Number)row.get("number")).longValue(),v.rootId(),
                versions.version((UUID)row.get("base_scenario_id")).snapshotHash(),state,payload,
                client==null?r.reason():client.path("comment").asText(),r.reportedBy(),r.reportedAt(),v.snapshotHash(),r.jobId(),r.planId(),
                validation,decision==null?null:decision.actor(),decision==null?null:decision.recordedAt(),
                r.errorCode()==null?null:new Error(r.errorCode(),r.errorMessage()),v.scenarioId(),v.snapshotId(),v.version());
    }
    private boolean representable(RequestDto.Receipt r) {
        return r.kind().equals("TRIP_CHANGE") || (r.kind().equals("URGENT_MAINTENANCE")
                && Set.of("IMMEDIATE","WITHIN_24H","WITHIN_HORIZON").contains(r.change().path("urgency").asText()));
    }
    private static void fields(JsonNode p,Set<String> allowed) { for(String key:p.propertyNames()) check(allowed.contains(key),key,"is unsupported"); }
    private static String required(JsonNode p,String key) { check(p.path(key).isTextual(),key,"must be a string"); String s=p.path(key).asText(); bounded(s,key,1000); return s; }
    private static void bounded(String s,String f,int max) { check(s!=null && !s.isBlank() && s.length()<=max,f,"must be 1.."+max+" characters"); }
    private static void check(boolean ok,String field,String reason) { if(!ok) throw invalid(field,reason); }
    private static PlanningService.InvalidRequestException invalid(String f,String r) { return new PlanningService.InvalidRequestException(f,r); }
}
