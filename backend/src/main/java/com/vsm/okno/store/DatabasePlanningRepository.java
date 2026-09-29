package com.vsm.okno.store;

import com.vsm.okno.dto.Dto;
import com.vsm.okno.requests.RequestDto;
import com.vsm.okno.requests.SourceVersionService;
import com.vsm.okno.service.PlanningService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** PostgreSQL is authoritative; worker leases and approval locks also work across API processes. */
@Repository
@Profile("database")
public class DatabasePlanningRepository {
    public record Parameters(UUID scenarioId,UUID snapshotId,String snapshotHash,String policy,int seed,int timeLimitSec,int frozenMinute) {}
    public record Claim(Store.PlanningJob job,Store.Scenario scenario,Parameters parameters,UUID owner) {}
    private final JdbcTemplate jdbc;
    private final SourceVersionService versions;
    private final ObjectMapper json=new ObjectMapper();
    public DatabasePlanningRepository(JdbcTemplate jdbc,SourceVersionService versions) { this.jdbc=jdbc; this.versions=versions; }

    @Transactional public void saveScenario(Store.Scenario s) {
        if (s.sourceSnapshotId!=null) {
            var auth=org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            var v=versions.register(s.sourceSnapshotId,auth==null?"system":auth.getName()); s.sourceSnapshotId=v.snapshotId();
        }
        jdbc.update("insert into vsm.api_scenario(id,payload) values (?,?::jsonb) on conflict(id) do nothing",s.id,json.writeValueAsString(s));
    }
    @Transactional public Store.Scenario scenario(UUID id) {
        var saved=jdbc.query("select payload::text from vsm.api_scenario where id=?",(rs,n) -> json.readValue(rs.getString(1),Store.Scenario.class),id);
        if (!saved.isEmpty()) return saved.getFirst();
        var version=versions.findVersion(id).orElseThrow(() -> missing("scenario",id));
        Store.Scenario s=new Store.Scenario(); s.id=id; s.createdAt=version.createdAt(); s.sourceSnapshotId=version.snapshotId();
        if (version.parentScenarioId()!=null) {
            var parent=jdbc.query("select payload::text from vsm.api_scenario where id=?",(rs,n)->json.readValue(rs.getString(1),Store.Scenario.class),version.parentScenarioId());
            if (!parent.isEmpty()) s.planningUnsupportedReason=parent.getFirst().planningUnsupportedReason;
        }
        s.provenance=jdbc.queryForObject("select provenance from vsm.scenario where id=?",String.class,id);
        s.trains=jdbc.query("""
                select t.id,t.external_id,t.status,o.odometer_km from vsm.train t
                join vsm.scenario s on s.id=t.scenario_id left join lateral(
                  select odometer_km from vsm.odometer_reading where scenario_id=t.scenario_id and train_id=t.id
                    and observed_at<=s.horizon_start order by observed_at desc limit 1
                ) o on true where t.scenario_id=? order by t.external_id collate "C"
                """,(rs,n) -> new Dto.Train(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),
                rs.getLong(4),"Обязанности рассчитываются по версии исходных данных"),id);
        // E3 support is negotiated by the adapter owner, not inferred from fleet size here.
        jdbc.update("insert into vsm.api_scenario(id,payload) values (?,?::jsonb) on conflict(id) do nothing",id,json.writeValueAsString(s));
        return s;
    }

    @Transactional public Store.PlanningJob enqueue(Parameters p,String actor,String key) {
        String body=json.writeValueAsString(p);
        if (key!=null) {
            if (key.isBlank() || key.length()>200) throw new PlanningService.InvalidRequestException("idempotencyKey","must be 1..200 characters");
            jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,"job-key:"+actor+":"+key);
            var saved=jdbc.query("select id,request_hash=vsm.canonical_sha256(?::jsonb) as same from vsm.planning_job where actor=? and idempotency_key=?",
                    (rs,n) -> new Object[]{rs.getObject(1,UUID.class),rs.getBoolean(2)},body,actor,key);
            if (!saved.isEmpty()) {
                if (!(Boolean)saved.getFirst()[1]) throw new PlanningService.IdempotencyConflictException(key);
                return job((UUID)saved.getFirst()[0]);
            }
        }
        UUID id=UUID.randomUUID();
        jdbc.update("insert into vsm.planning_job(id,scenario_id,source_snapshot_id,request,actor,idempotency_key,status) values (?,?,?,?::jsonb,?,?,'QUEUED')",
                id,p.scenarioId(),p.snapshotId(),body,actor,key);
        versions.stage(p.scenarioId(),"IN_CALCULATION",actor,id,null,null,"Расчёт поставлен в очередь");
        return job(id);
    }
    public Store.PlanningJob job(UUID id) {
        return jdbc.query("select * from vsm.planning_job where id=?",(rs,n) -> {
            var j=new Store.PlanningJob(); j.id=id; j.scenarioId=rs.getObject("scenario_id",UUID.class); j.status=rs.getString("status");
            j.solverStatus=rs.getString("solver_status"); j.planId=rs.getObject("plan_id",UUID.class); j.error=rs.getString("error"); return j;
        },id).stream().findFirst().orElseThrow(() -> missing("job",id));
    }
    @Transactional public Optional<Claim> claim(UUID owner) {
        var exhausted=jdbc.query("""
                update vsm.planning_job set status='FAILED',error='WORKER_RETRY_EXHAUSTED: worker lease expired three times',updated_at=now()
                where status='RUNNING' and lease_until<now() and attempts>=3 returning id,scenario_id
                """,(rs,n) -> new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)});
        for (var row:exhausted) versions.stage(row[1],"ERROR","worker",row[0],null,"WORKER_RETRY_EXHAUSTED","Расчёт прерван трижды; запустите новый job");
        var claimed=jdbc.query("""
                with candidate as (
                  select id from vsm.planning_job where status='QUEUED' or (status='RUNNING' and lease_until<now() and attempts<3)
                  order by created_at,id for update skip locked limit 1
                ) update vsm.planning_job j set status='RUNNING',lease_owner=?,lease_until=now()+interval '60 seconds',
                    attempts=attempts+1,updated_at=now() from candidate c where j.id=c.id returning j.id,j.request::text
                """,(rs,n) -> new Object[]{rs.getObject(1,UUID.class),json.readValue(rs.getString(2),Parameters.class)},owner);
        if (claimed.isEmpty()) return Optional.empty();
        UUID id=(UUID)claimed.getFirst()[0]; var p=(Parameters)claimed.getFirst()[1];
        return Optional.of(new Claim(job(id),scenario(p.scenarioId()),p,owner));
    }
    public boolean heartbeat(UUID job,UUID owner) {
        return jdbc.update("update vsm.planning_job set lease_until=now()+interval '60 seconds' where id=? and lease_owner=? and status='RUNNING' and lease_until>now()",job,owner)==1;
    }
    private boolean owns(UUID id,UUID owner) {
        return !jdbc.queryForList("select id from vsm.planning_job where id=? and status='RUNNING' and lease_owner=? and lease_until>now() for update",UUID.class,id,owner).isEmpty();
    }
    @Transactional public boolean complete(Claim claim,Store.Plan plan) {
        var source=plan.scenarioId==null?Optional.<RequestDto.Version>empty():versions.findVersion(plan.scenarioId);
        var current=source.isEmpty()?null:versions.lockHead(source.get().rootId());
        if (!owns(claim.job().id,claim.owner())) return false;
        persistPlan(plan,claim.parameters().snapshotId(),"worker","Расчёт и D2");
        UUID root=versions.findVersion(plan.scenarioId).map(RequestDto.Version::rootId).orElse(plan.scenarioId);
        if (current==null || current.scenarioId().equals(plan.scenarioId)) jdbc.update("""
                insert into vsm.plan_selection(root_id,latest_draft_id) values (?,?)
                on conflict(root_id) do update set latest_draft_id=excluded.latest_draft_id,updated_at=now()
                """,root,plan.id);
        jdbc.update("update vsm.planning_job set status='SUCCEEDED',solver_status=?,plan_id=?,lease_owner=null,lease_until=null,updated_at=now() where id=?",
                plan.solverStatus,plan.id,claim.job().id);
        versions.stage(plan.scenarioId,"CALCULATED","worker",claim.job().id,plan.id,null,"Результат расчёта сохранён: "+plan.solverStatus);
        if ("PASS".equals(plan.validationStatus)) versions.stage(plan.scenarioId,"VALIDATED","D2",claim.job().id,plan.id,null,"Независимая проверка D2 пройдена");
        else versions.stage(plan.scenarioId,"ERROR","D2",claim.job().id,plan.id,"D2_"+plan.validationStatus,"План не прошёл независимую проверку; согласование запрещено");
        return true;
    }
    @Transactional public void fail(Claim claim,String code,String message) {
        if (!owns(claim.job().id,claim.owner())) return;
        jdbc.update("update vsm.planning_job set status='FAILED',error=?,lease_owner=null,lease_until=null,updated_at=now() where id=?",code+": "+message,claim.job().id);
        versions.stage(claim.job().scenarioId,"ERROR","worker",claim.job().id,null,code,message);
    }
    private void persistPlan(Store.Plan p,UUID snapshot,String actor,String reason) {
        String payload=json.writeValueAsString(p);
        jdbc.update("insert into vsm.plan(id,scenario_id,source_snapshot_id,snapshot_hash,version,status,payload) values (?,?,?,?,?,?,?::jsonb)",
                p.id,p.scenarioId,snapshot,p.snapshotHash,p.version,p.status,payload);
        jdbc.update("insert into vsm.plan_history(plan_id,version,actor,reason,payload) values (?,?,?,?,?::jsonb)",p.id,p.version,actor,reason,payload);
    }
    public Store.Plan plan(UUID id) { return loadPlan(id,false); }
    private Store.Plan loadPlan(UUID id,boolean lock) {
        return jdbc.query("select payload::text from vsm.plan where id=?"+(lock?" for update":""),
                (rs,n) -> json.readValue(rs.getString(1),Store.Plan.class),id).stream().findFirst().orElseThrow(() -> missing("plan",id));
    }
    @Transactional public Store.Plan approve(UUID id,int expected,String actor,String comment,Consumer<Store.Plan> validation) {
        var info=plan(id); var source=versions.findVersion(info.scenarioId);
        if (source.isPresent()) versions.assertCurrent(info.scenarioId,info.snapshotHash);
        var p=loadPlan(id,true);
        if (p.version!=expected) throw new PlanningService.VersionConflictException(p.version);
        if (!"DRAFT".equals(p.status)) throw new PlanningService.NotApprovableException("plan is already approved");
        validation.accept(p);
        p.approvedBy=actor; p.status="APPROVED"; p.version++;
        String body=json.writeValueAsString(p);
        jdbc.update("update vsm.plan set version=?,status='APPROVED',payload=?::jsonb,approved_at=now() where id=?",p.version,body,p.id);
        jdbc.update("insert into vsm.plan_history(plan_id,version,actor,reason,payload) values (?,?,?,?,?::jsonb)",p.id,p.version,actor,
                comment==null || comment.isBlank()?"Согласование проверенного плана":comment,body);
        UUID root=source.map(RequestDto.Version::rootId).orElse(p.scenarioId);
        jdbc.update("""
                insert into vsm.plan_selection(root_id,effective_plan_id) values (?,?)
                on conflict(root_id) do update set effective_plan_id=excluded.effective_plan_id,updated_at=now()
                """,root,p.id);
        UUID job=jdbc.queryForList("select id from vsm.planning_job where plan_id=?",UUID.class,p.id).stream().findFirst().orElse(null);
        versions.stage(p.scenarioId,"APPROVED",actor,job,p.id,null,"Согласование человеком");
        return p;
    }
    public UUID currentPlan(UUID scenarioId) {
        if (scenarioId!=null) return selection(scenarioId).effectivePlanId();
        return jdbc.queryForList("select s.effective_plan_id from vsm.plan_selection s join vsm.plan p on p.id=s.effective_plan_id order by p.approved_at desc nulls last,p.id limit 1",UUID.class)
                .stream().findFirst().orElse(null);
    }
    public RequestDto.Selection selection(UUID scenarioId) {
        var v=versions.findVersion(scenarioId); UUID root=v.map(RequestDto.Version::rootId).orElse(scenarioId);
        var rows=jdbc.query("select latest_draft_id,effective_plan_id from vsm.plan_selection where root_id=?",
                (rs,n) -> new UUID[]{rs.getObject(1,UUID.class),rs.getObject(2,UUID.class)},root);
        UUID draft=rows.isEmpty()?null:rows.getFirst()[0],effective=rows.isEmpty()?null:rows.getFirst()[1];
        boolean current=effective!=null && (v.isEmpty() || plan(effective).scenarioId.equals(versions.head(scenarioId).scenarioId()));
        return new RequestDto.Selection(root,draft,effective,current);
    }
    public void incident(Dto.Incident i) { jdbc.update("insert into vsm.incident_message(id,payload,reported_at) values (?,?::jsonb,?)",i.id(),json.writeValueAsString(i),java.sql.Timestamp.from(i.reportedAt())); }
    public List<Dto.Incident> incidents() {
        return jdbc.query("select payload::text from vsm.incident_message order by reported_at desc,id",(rs,n) -> json.readValue(rs.getString(1),Dto.Incident.class));
    }
    private static PlanningService.NotFoundException missing(String type,UUID id) { return new PlanningService.NotFoundException(type+" not found: "+id); }
}
