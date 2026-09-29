package com.vsm.okno.requests;

import com.vsm.okno.data.SourceSnapshotRepository;
import com.vsm.okno.service.PlanningService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

import static com.vsm.okno.requests.RequestDto.*;

/** Source-version transaction boundary; no solver or approval decision lives here. */
@Service
@Profile("database")
public class SourceVersionService {
    private final JdbcTemplate jdbc;
    private final SourceSnapshotRepository snapshots;
    private final ObjectMapper json = new ObjectMapper();
    private static final List<String> FACT_TABLES = List.of("train", "odometer_reading", "resource",
            "resource_availability", "cycle_resource", "cycle_baseline", "fixed_trip", "service_event",
            "service_credit", "train_presence", "train_occupancy", "cleaning_counter", "frozen_work",
            "urgent_work_requirement", "resource_outage", "train_release", "urgent_work_rule", "urgent_work_rule_resource");
    public SourceVersionService(JdbcTemplate jdbc, SourceSnapshotRepository snapshots) {
        this.jdbc=jdbc; this.snapshots=snapshots;
    }

    @Transactional public Version register(UUID snapshotId, String actor) {
        var saved=snapshots.findById(snapshotId).orElseThrow(() -> missing("snapshot"));
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"source-register:"+saved.scenarioId());
        var existing=findVersion(saved.scenarioId());
        if (existing.isPresent()) return existing.get();
        jdbc.update("insert into vsm.scenario_version(scenario_id,root_id,version,snapshot_id,created_by) values (?,?,0,?,?)",
                saved.scenarioId(),saved.scenarioId(),saved.id(),actor);
        jdbc.update("insert into vsm.scenario_head(root_id,scenario_id,version) values (?,?,0)",saved.scenarioId(),saved.scenarioId());
        return version(saved.scenarioId());
    }

    public Optional<Version> findVersion(UUID id) {
        return jdbc.query("""
                select v.*,s.snapshot_hash from vsm.scenario_version v
                join vsm.scenario_snapshot s on s.id=v.snapshot_id where v.scenario_id=?
                """,(rs,n) -> new Version(rs.getObject("root_id",UUID.class),rs.getObject("scenario_id",UUID.class),
                rs.getInt("version"),rs.getObject("parent_scenario_id",UUID.class),rs.getObject("snapshot_id",UUID.class),
                rs.getString("snapshot_hash"),rs.getString("created_by"),rs.getTimestamp("created_at").toInstant()),id).stream().findFirst();
    }
    public Version version(UUID id) { return findVersion(id).orElseThrow(() -> missing("source version")); }
    public Version head(UUID id) {
        var v=version(id);
        return version(jdbc.queryForObject("select scenario_id from vsm.scenario_head where root_id=?",UUID.class,v.rootId()));
    }
    public List<Version> versions(UUID id) {
        return jdbc.queryForList("select scenario_id from vsm.scenario_version where root_id=? order by version",UUID.class,version(id).rootId())
                .stream().map(this::version).toList();
    }
    public JsonNode source(UUID id) {
        return json.readTree(snapshots.findById(version(id).snapshotId()).orElseThrow(() -> missing("snapshot")).canonicalPayload());
    }

    /** Returns exact retries before checking an advanced head. All effects commit together. */
    @Transactional public Receipt submit(Command command, String actor, boolean ruleEndpoint) {
        return submitAllowed(command,actor,ruleEndpoint ? Set.of("RULE_CHANGE")
                : Set.of("TRIP_CHANGE","TRIP_ADD","TRIP_CANCEL","URGENT_MAINTENANCE","RESOURCE_OUTAGE"));
    }

    /** Technologist-only endpoint; authorization is enforced before this transaction. */
    @Transactional public Receipt submitE3Facts(Command command, String actor) {
        return submitAllowed(command,actor,Set.of("TRAIN_RELEASE","URGENT_RULE_CHANGE"));
    }

    private Receipt submitAllowed(Command command,String actor,Set<String> allowedKinds) {
        check(command!=null,"request","is required");
        check(command.scenarioId()!=null,"scenarioId","is required");
        check(command.expectedVersion()!=null && command.expectedVersion()>=0,"expectedVersion","is required and nonnegative");
        bounded(command.idempotencyKey(),"idempotencyKey",200); bounded(command.reason(),"reason",1000); bounded(command.source(),"source",1000);
        JsonNode change=command.change(); check(change!=null && change.isObject(),"change","must be an object");
        String kind=text(change,"kind");
        check(allowedKinds.contains(kind),"kind","is not supported by this endpoint");
        String body=json.writeValueAsString(command);
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"request-key:"+actor+":"+command.idempotencyKey());
        var retry=jdbc.query("select id,command_hash=vsm.canonical_sha256(?::jsonb) as same from vsm.change_request where reported_by=? and idempotency_key=?",
                (rs,n) -> new Object[]{rs.getObject(1,UUID.class),rs.getBoolean(2)},body,actor,command.idempotencyKey());
        if (!retry.isEmpty()) {
            if (!(Boolean)retry.getFirst()[1]) throw new PlanningService.IdempotencyConflictException(command.idempotencyKey());
            return receipt((UUID)retry.getFirst()[0]);
        }
        Version parent=version(command.scenarioId());
        Version current=lockHead(parent.rootId());
        if (!current.scenarioId().equals(parent.scenarioId()) || current.version()!=command.expectedVersion())
            throw new PlanningService.VersionConflictException(current.version());
        UUID next=UUID.randomUUID(), requestId=UUID.randomUUID();
        copyFacts(parent.scenarioId(),next);
        apply(next,change,command.source(),actor);
        var saved=snapshots.capture(next);
        jdbc.update("insert into vsm.scenario_version(scenario_id,root_id,version,parent_scenario_id,snapshot_id,created_by) values (?,?,?,?,?,?)",
                next,parent.rootId(),parent.version()+1,parent.scenarioId(),saved.id(),actor);
        jdbc.update("update vsm.scenario_head set scenario_id=?,version=? where root_id=?",next,parent.version()+1,parent.rootId());
        jdbc.update("update vsm.plan_selection set latest_draft_id=null,updated_at=now() where root_id=?",parent.rootId());
        jdbc.update("""
                insert into vsm.change_request(id,root_id,base_scenario_id,new_scenario_id,snapshot_id,kind,train_id,trip_id,
                  reason,source,reported_by,idempotency_key,command) values (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb)
                """,requestId,parent.rootId(),parent.scenarioId(),next,saved.id(),kind,optionalUuid(change,"trainId"),optionalUuid(change,"tripId"),
                command.reason().strip(),command.source().strip(),actor,command.idempotencyKey(),body);
        event(requestId,"RECEIVED",actor,null,null,null,"Новая версия исходных данных сохранена");
        return receipt(requestId);
    }

    public Version lockHead(UUID rootId) {
        UUID id=jdbc.queryForObject("select scenario_id from vsm.scenario_head where root_id=? for update",UUID.class,rootId);
        return version(id);
    }
    public void assertCurrent(UUID scenarioId,String hash) {
        var v=version(scenarioId); var current=lockHead(v.rootId());
        if (!current.scenarioId().equals(scenarioId) || !current.snapshotHash().equals(hash))
            throw new PlanningService.NotApprovableException("source version changed; calculate and validate the current snapshot");
    }

    private void copyFacts(UUID from,UUID to) {
        jdbc.update("insert into vsm.scenario(id,name,rule_set_id,horizon_start,horizon_end,provenance) select ?,name,rule_set_id,horizon_start,horizon_end,provenance from vsm.scenario where id=?",to,from);
        for (String table:FACT_TABLES) {
            // Identifiers come exclusively from a fixed server-side table list and PostgreSQL catalog.
            var columns=jdbc.queryForList("select column_name from information_schema.columns where table_schema='vsm' and table_name=? and column_name<>'scenario_id' order by ordinal_position",String.class,table);
            String cols=columns.stream().map(c -> "\""+c+"\"").collect(java.util.stream.Collectors.joining(","));
            jdbc.update("insert into vsm."+table+"(scenario_id,"+cols+") select ?,"+cols+" from vsm."+table+" where scenario_id=?",to,from);
        }
    }
    private void apply(UUID scenario,JsonNode c,String source,String actor) {
        String kind=text(c,"kind");
        switch (kind) {
            case "TRIP_CHANGE", "TRIP_ADD", "TRIP_CANCEL" -> trip(scenario,c,source,kind);
            case "URGENT_MAINTENANCE" -> urgent(scenario,c,source);
            case "RESOURCE_OUTAGE" -> outage(scenario,c,source);
            case "RULE_CHANGE" -> rules(scenario,c,source);
            case "TRAIN_RELEASE", "URGENT_RULE_CHANGE" -> new E3SourceFactWriter(jdbc).apply(scenario,c,source,actor);
            default -> throw invalid("kind","is unsupported");
        }
    }
    private void trip(UUID scenario,JsonNode c,String source,String kind) {
        fields(c,kind.equals("TRIP_CANCEL") ? Set.of("kind","trainId","tripId") : kind.equals("TRIP_CHANGE")
                ? Set.of("kind","trainId","tripId","departureAt","arrivalAt")
                : Set.of("kind","trainId","tripId","departureAt","arrivalAt","distanceKm","label","origin","destination"));
        UUID train=uuid(c,"trainId"),trip=uuid(c,"tripId"); train(scenario,train);
        var existing=jdbc.queryForList("select train_id from vsm.fixed_trip where scenario_id=? and id=?",UUID.class,scenario,trip);
        if (kind.equals("TRIP_ADD")) check(existing.isEmpty(),"tripId","already exists in this version");
        else check(!existing.isEmpty() && existing.getFirst().equals(train),"tripId","must name a trip assigned to trainId in this version");
        if (kind.equals("TRIP_CANCEL")) { jdbc.update("delete from vsm.fixed_trip where scenario_id=? and id=?",scenario,trip); return; }
        OffsetDateTime departure=time(c,"departureAt"),arrival=time(c,"arrivalAt"); interval(scenario,departure,arrival);
        if (kind.equals("TRIP_CHANGE")) jdbc.update("update vsm.fixed_trip set departure_at=?,arrival_at=?,source=? where scenario_id=? and id=?",departure,arrival,source,scenario,trip);
        else {
            long distance=positive(c,"distanceKm"); String label=text(c,"label"),origin=text(c,"origin"),destination=text(c,"destination");
            jdbc.update("insert into vsm.fixed_trip values (?,?,?,?,?,?,?,?,?,?)",scenario,trip,train,label,departure,arrival,distance,origin,destination,source);
        }
    }
    private void urgent(UUID scenario,JsonNode c,String source) {
        fields(c,Set.of("kind","trainId","problemType","detectedAt","notBeforeCurrentTripEnd","earliestStartAt","deadlineAt","urgency","workKind"));
        UUID train=uuid(c,"trainId"); train(scenario,train);
        OffsetDateTime detected=time(c,"detectedAt"),earliest=detected,deadline=optionalTime(c,"deadlineAt");
        var bounds=bounds(scenario); check(!detected.isBefore(bounds[0]) && detected.isBefore(bounds[1]),"detectedAt","must be inside the scenario horizon");
        check(c.path("notBeforeCurrentTripEnd").isBoolean(),"notBeforeCurrentTripEnd","must be explicit true or false");
        boolean afterTrip=c.path("notBeforeCurrentTripEnd").asBoolean();
        if (afterTrip) {
            var ends=jdbc.query("select arrival_at from vsm.fixed_trip where scenario_id=? and train_id=? and departure_at<=? and arrival_at>?",
                    (rs,n) -> rs.getObject(1,OffsetDateTime.class),scenario,train,detected,detected);
            if (!ends.isEmpty()) earliest=ends.getFirst();
        }
        OffsetDateTime supplied=optionalTime(c,"earliestStartAt"); if (supplied!=null && supplied.isAfter(earliest)) earliest=supplied;
        String urgency=optionalText(c,"urgency");
        check(urgency==null || Set.of("CRITICAL","HIGH","NORMAL","IMMEDIATE","WITHIN_24H","WITHIN_HORIZON").contains(urgency),"urgency","is unsupported");
        // These named UI policies supply a deadline, never a resource or a scheduled slot.
        if ("WITHIN_24H".equals(urgency)) {
            var policyDeadline=detected.plusHours(24).isBefore(bounds[1])?detected.plusHours(24):bounds[1];
            if (deadline==null || policyDeadline.isBefore(deadline)) deadline=policyDeadline;
        } else if ("WITHIN_HORIZON".equals(urgency) && deadline==null) deadline=bounds[1];
        check(deadline!=null || urgency!=null,"deadlineAt","or urgency is required");
        check(earliest.isBefore(bounds[1]),"earliestStartAt","is outside the horizon");
        check(deadline==null || (deadline.isAfter(earliest) && !deadline.isAfter(bounds[1])),"deadlineAt","must be after earliest start and within the horizon");
        jdbc.update("insert into vsm.urgent_work_requirement values (?,?,?,?,?,?,?,?,?,?,?)",scenario,UUID.randomUUID(),train,
                text(c,"problemType"),detected,afterTrip,earliest,deadline,urgency,text(c,"workKind"),source);
    }
    private void outage(UUID scenario,JsonNode c,String source) {
        fields(c,Set.of("kind","resourceId","startsAt","endsAt"));
        String resource=text(c,"resourceId");
        check(jdbc.queryForObject("select count(*) from vsm.resource where scenario_id=? and id=?",Integer.class,scenario,resource)==1,"resourceId","does not exist in this scenario");
        OffsetDateTime start=time(c,"startsAt"),end=time(c,"endsAt"); interval(scenario,start,end);
        var windows=jdbc.query("select id,starts_at,ends_at from vsm.resource_availability where scenario_id=? and resource_id=? and starts_at<? and ends_at>?",
                (rs,n) -> new Object[]{rs.getObject(1,UUID.class),rs.getObject(2,OffsetDateTime.class),rs.getObject(3,OffsetDateTime.class)},scenario,resource,end,start);
        for (var window:windows) {
            jdbc.update("delete from vsm.resource_availability where scenario_id=? and id=?",scenario,window[0]);
            OffsetDateTime a=(OffsetDateTime)window[1],b=(OffsetDateTime)window[2];
            if (a.isBefore(start)) availability(scenario,resource,a,start,source);
            if (b.isAfter(end)) availability(scenario,resource,end,b,source);
        }
        jdbc.update("insert into vsm.resource_outage values (?,?,?,?,?,?)",scenario,UUID.randomUUID(),resource,start,end,source);
    }
    private void availability(UUID s,String r,OffsetDateTime a,OffsetDateTime b,String source) {
        jdbc.update("insert into vsm.resource_availability values (?,?,?,?,?,?)",s,UUID.randomUUID(),r,a,b,source);
    }

    private void rules(UUID scenario,JsonNode c,String source) {
        fields(c,Set.of("kind","version","confirmationStatus","mileagePolicy","toleranceBasis","rules","baselines"));
        UUID previous=jdbc.queryForObject("select rule_set_id from vsm.scenario where id=?",UUID.class,scenario),next=UUID.randomUUID();
        String policy=text(c,"mileagePolicy"),basis=text(c,"toleranceBasis"),confirmation=text(c,"confirmationStatus");
        check(Set.of("ABSOLUTE_GRID","FROM_LAST_SERVICE","UNCONFIRMED").contains(policy),"mileagePolicy","is unsupported");
        check(Set.of("INTERVAL","NOMINAL_MILESTONE","UNCONFIRMED").contains(basis),"toleranceBasis","is unsupported");
        check(Set.of("CONFIRMED","UNCONFIRMED","SYNTHETIC").contains(confirmation),"confirmationStatus","is unsupported");
        check(c.path("rules").isArray() && !c.path("rules").isEmpty(),"rules","must be a nonempty array");
        jdbc.update("insert into vsm.rule_set(id,version,source,confirmation_status,mileage_policy,tolerance_basis) values (?,?,?,?,?,?)",
                next,text(c,"version"),source,confirmation,policy,basis);
        Set<String> codes=new HashSet<>(); boolean gridChanged=false;
        String oldPolicy=jdbc.queryForObject("select mileage_policy from vsm.rule_set where id=?",String.class,previous);
        gridChanged=!policy.equals(oldPolicy);
        for (JsonNode rule:c.path("rules")) {
            fields(rule,Set.of("code","intervalKm","toleranceBasisPoints","durationMinutes","rank","resourceIds"));
            String code=text(rule,"code"); check(codes.add(code),"code","is duplicated");
            long interval=positive(rule,"intervalKm"),duration=positive(rule,"durationMinutes"),tolerance=integer(rule,"toleranceBasisPoints"),rank=integer(rule,"rank");
            check(duration<=Integer.MAX_VALUE && tolerance>=0 && tolerance<=9999 && rank>=0 && rank<=Integer.MAX_VALUE,"rules","contain invalid duration, tolerance or rank");
            var old=jdbc.queryForList("select interval_km from vsm.cycle_rule where rule_set_id=? and code=?",Long.class,previous,code);
            gridChanged |= old.isEmpty() || old.getFirst()!=interval;
            jdbc.update("insert into vsm.cycle_rule values (?,?,?,?,?,?,?)",next,code,interval,tolerance,duration,rank,source);
            check(rule.path("resourceIds").isArray() && !rule.path("resourceIds").isEmpty(),"resourceIds","are required for each rule");
        }
        jdbc.update("delete from vsm.cycle_resource where scenario_id=?",scenario);
        jdbc.update("update vsm.scenario set rule_set_id=? where id=?",next,scenario);
        for (JsonNode rule:c.path("rules")) for (JsonNode resource:rule.path("resourceIds")) {
            check(resource.isTextual(),"resourceIds","must contain strings");
            jdbc.update("insert into vsm.cycle_resource values (?,?,?,?,?)",scenario,next,text(rule,"code"),resource.asText(),source);
        }
        var trains=jdbc.queryForList("select id from vsm.train where scenario_id=?",UUID.class,scenario);
        Map<String,Long> overrides=new HashMap<>();
        if (!c.path("baselines").isMissingNode()) {
            check(c.path("baselines").isArray(),"baselines","must be an array");
            for (var row:c.path("baselines")) {
                fields(row,Set.of("trainId","cycleCode","creditedNominalKm")); UUID train=uuid(row,"trainId"); String code=text(row,"cycleCode");
                check(trains.contains(train) && codes.contains(code),"baselines","must refer to the new trains/rules");
                long credit=integer(row,"creditedNominalKm"); check(credit>=0 && overrides.put(train+":"+code,credit)==null,"baselines","must be nonnegative and unique");
            }
        }
        for (UUID train:trains) for (String code:codes) {
            Long credit=overrides.get(train+":"+code);
            if (credit==null) {
                check(!gridChanged,"baselines","explicit credit for every train/cycle is required when changing the mileage grid");
                credit=jdbc.queryForObject("""
                        select max(km) from (
                          select credited_nominal_km km from vsm.cycle_baseline where scenario_id=? and train_id=? and rule_set_id=? and cycle_code=?
                          union all select c.credited_nominal_km from vsm.service_credit c join vsm.service_event e
                            on (e.scenario_id,e.id)=(c.scenario_id,c.service_event_id)
                            where e.scenario_id=? and e.train_id=? and c.rule_set_id=? and c.covered_cycle_code=?
                              and e.accepted_at<=(select horizon_start from vsm.scenario where id=?)
                        ) credits
                        """,Long.class,scenario,train,previous,code,scenario,train,previous,code,scenario);
                check(credit!=null,"baselines","explicit credit history is missing for "+train+"/"+code);
            }
            jdbc.update("insert into vsm.cycle_baseline values (?,?,?,?,?,?,?)",scenario,train,next,code,credit,bounds(scenario)[0],source);
        }
    }

    public Receipt receipt(UUID id) {
        return jdbc.query("""
                select r.*,e.status,e.job_id,e.plan_id,e.code,e.message from vsm.change_request r
                join lateral(select * from vsm.request_event where request_id=r.id order by sequence desc limit 1) e on true where r.id=?
                """,(rs,n) -> new Receipt(rs.getObject("id",UUID.class),rs.getString("kind"),rs.getObject("train_id",UUID.class),rs.getObject("trip_id",UUID.class),
                rs.getString("reason"),rs.getString("source"),rs.getString("reported_by"),rs.getTimestamp("reported_at").toInstant(),
                version(rs.getObject("new_scenario_id",UUID.class)),json.readTree(rs.getString("command")).path("change"),rs.getString("status"),
                rs.getObject("job_id",UUID.class),rs.getObject("plan_id",UUID.class),rs.getString("code"),rs.getString("message")),id)
                .stream().findFirst().orElseThrow(() -> missing("request"));
    }
    public List<Receipt> requests(UUID scenarioId,int limit) {
        check(limit>0 && limit<=200,"limit","must be 1..200");
        var ids=scenarioId==null ? jdbc.queryForList("select id from vsm.change_request order by reported_at desc,id limit ?",UUID.class,limit)
                : jdbc.queryForList("select id from vsm.change_request where root_id=? order by reported_at desc,id limit ?",UUID.class,version(scenarioId).rootId(),limit);
        return ids.stream().map(this::receipt).toList();
    }
    public Updates updates(long after) {
        check(after>=0,"after","must be nonnegative");
        var rows=jdbc.query("select * from vsm.request_event where sequence>? order by sequence limit 100",(rs,n) -> new Update(rs.getLong("sequence"),
                rs.getObject("request_id",UUID.class),rs.getString("status"),rs.getString("actor"),rs.getObject("job_id",UUID.class),rs.getObject("plan_id",UUID.class),
                rs.getString("code"),rs.getString("message"),rs.getTimestamp("recorded_at").toInstant()),after);
        return new Updates(rows.isEmpty()?after:rows.getLast().sequence(),rows);
    }
    public List<Update> history(UUID id) {
        receipt(id);
        return jdbc.query("select * from vsm.request_event where request_id=? order by sequence",(rs,n) -> new Update(rs.getLong("sequence"),id,
                rs.getString("status"),rs.getString("actor"),rs.getObject("job_id",UUID.class),rs.getObject("plan_id",UUID.class),rs.getString("code"),rs.getString("message"),
                rs.getTimestamp("recorded_at").toInstant()),id);
    }
    public void event(UUID id,String status,String actor,UUID job,UUID plan,String code,String message) {
        // Sequence allocation is serialized until commit: reconnect cursors cannot skip a late commit.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended('vsm-request-event-sequence',0))",Object.class);
        jdbc.update("insert into vsm.request_event(request_id,status,actor,job_id,plan_id,code,message) values (?,?,?,?,?,?,?)",id,status,actor,job,plan,code,message);
    }
    public void stage(UUID scenarioId,String status,String actor,UUID job,UUID plan,String code,String message) {
        var found=findVersion(scenarioId); if (found.isEmpty()) return;
        var v=found.get();
        // Protect the latest lifecycle from an older concurrent job finishing later.
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended('vsm-request-event-sequence',0))",Object.class);
        for (UUID id:jdbc.queryForList("""
                select r.id from vsm.change_request r join vsm.scenario_version v on v.scenario_id=r.new_scenario_id
                where r.root_id=? and v.version<=? order by v.version
                """,UUID.class,v.rootId(),v.version())) {
            var latest=receipt(id);
            if ("APPROVED".equals(latest.status()) && !"APPROVED".equals(status)) continue;
            if (!Set.of("IN_CALCULATION","APPROVED").contains(status) && latest.jobId()!=null && !latest.jobId().equals(job)) continue;
            event(id,status,actor,job,plan,code,message);
        }
    }
    private OffsetDateTime[] bounds(UUID id) {
        return jdbc.queryForObject("select horizon_start,horizon_end from vsm.scenario where id=?",(rs,n) -> new OffsetDateTime[]{rs.getObject(1,OffsetDateTime.class),rs.getObject(2,OffsetDateTime.class)},id);
    }
    private void interval(UUID id,OffsetDateTime a,OffsetDateTime b) {
        var h=bounds(id); check(b.isAfter(a) && !a.isBefore(h[0]) && !b.isAfter(h[1]),"time","must form a positive interval within the horizon");
    }
    private void train(UUID scenario,UUID id) {
        check(jdbc.queryForObject("select count(*) from vsm.train where scenario_id=? and id=?",Integer.class,scenario,id)==1,"trainId","does not exist in this scenario");
    }
    private static void fields(JsonNode node,Set<String> allowed) {
        check(node.isObject(),"change","must be an object");
        for (String field:node.propertyNames()) check(allowed.contains(field),field,"is not supported for this kind");
    }
    private static String text(JsonNode node,String key) { String value=optionalText(node,key); bounded(value,key,1000); return value.strip(); }
    private static String optionalText(JsonNode node,String key) {
        JsonNode v=node.path(key); if (v.isNull() || v.isMissingNode()) return null;
        check(v.isTextual(),key,"must be a string"); return v.asText();
    }
    private static UUID uuid(JsonNode n,String k) { try { return UUID.fromString(text(n,k)); } catch (IllegalArgumentException e) { throw invalid(k,"must be a UUID"); } }
    private static UUID optionalUuid(JsonNode n,String k) { return n.path(k).isMissingNode() || n.path(k).isNull() ? null : uuid(n,k); }
    private static OffsetDateTime time(JsonNode n,String k) {
        try { var t=OffsetDateTime.parse(text(n,k)); check(t.getSecond()==0 && t.getNano()==0,k,"must fall on a whole minute"); return t; }
        catch (java.time.format.DateTimeParseException e) { throw invalid(k,"must be ISO 8601 with an explicit offset"); }
    }
    private static OffsetDateTime optionalTime(JsonNode n,String k) { return n.path(k).isMissingNode() || n.path(k).isNull() ? null : time(n,k); }
    private static long integer(JsonNode n,String k) { check(n.path(k).isIntegralNumber() && n.path(k).canConvertToLong(),k,"must be an integer"); return n.path(k).asLong(); }
    private static long positive(JsonNode n,String k) { long v=integer(n,k); check(v>0,k,"must be positive"); return v; }
    private static void bounded(String s,String f,int max) { check(s!=null && !s.isBlank() && s.length()<=max,f,"must be 1.."+max+" characters"); }
    private static void check(boolean good,String field,String reason) { if (!good) throw invalid(field,reason); }
    private static PlanningService.InvalidRequestException invalid(String f,String r) { return new PlanningService.InvalidRequestException(f,r); }
    private static PlanningService.NotFoundException missing(String what) { return new PlanningService.NotFoundException(what+" not found"); }
}
