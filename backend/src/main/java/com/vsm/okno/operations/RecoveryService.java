package com.vsm.okno.operations;

import static com.vsm.okno.operations.RecoveryModel.*;

import com.vsm.okno.data.SourceSnapshotRepository;
import com.vsm.okno.service.PlanningService;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;
import java.util.function.BiFunction;

/** PostgreSQL-backed optimistic revisions. Every command keeps the original snapshot intact. */
@Service
@Profile("database")
public class RecoveryService {
    private final JdbcTemplate jdbc;
    private final SourceSnapshotRepository snapshots;
    private final ObjectMapper json=new ObjectMapper();
    private final RecoveryEngine engine=new RecoveryEngine();
    private record Stored(UUID snapshotId,int version,String hash,State state) {}
    public RecoveryService(JdbcTemplate jdbc,SourceSnapshotRepository snapshots) { this.jdbc=jdbc; this.snapshots=snapshots; }

    @Transactional
    public Board create(UUID scenarioId,String actor) {
        var exists=jdbc.queryForObject("select count(*) from vsm.scenario where id=?",Integer.class,scenarioId);
        if (exists==null || exists==0) throw new PlanningService.NotFoundException("scenario not found");
        var saved=snapshots.capture(scenarioId);
        FleetSource source;
        try { source=FleetSource.read(saved); } catch (IllegalArgumentException e) { throw invalid(e); }
        UUID id=UUID.randomUUID(); State state=engine.initial(source);
        jdbc.update("insert into vsm.operational_session(id,source_snapshot_id) values (?,?)",id,saved.id());
        insert(id,0,state,actor);
        return get(id);
    }

    @Transactional(readOnly=true)
    public Board get(UUID id) {
        var stored=load(id,false); var source=source(stored);
        return engine.board(id,stored.version(),stored.hash(),source,stored.state());
    }
    @Transactional public Board failure(UUID id,FailureRequest request,String actor) {
        return update(id,request.expectedVersion(),actor,(source,state) -> engine.failure(source,state,request));
    }
    @Transactional public Board replace(UUID id,ReplacementRequest request,String actor) {
        return update(id,request.expectedVersion(),actor,(source,state) -> engine.replace(source,state,request,actor));
    }
    @Transactional public Board release(UUID id,ReleaseRequest request,String actor) {
        return update(id,request.expectedVersion(),actor,(source,state) -> engine.release(source,state,request,actor));
    }
    @Transactional public Board clock(UUID id,ClockRequest request,String actor) {
        return update(id,request.expectedVersion(),actor,(source,state) -> engine.clock(source,state,request.asOf()));
    }
    private Board update(UUID id,Integer expected,String actor,BiFunction<FleetSource,State,State> action) {
        if (expected==null || expected<0) throw invalid(new IllegalArgumentException("Нужна expectedVersion текущего оперативного состояния"));
        var stored=load(id,true);
        if (stored.version()!=expected) throw new PlanningService.VersionConflictException(stored.version());
        var source=source(stored); State changed;
        try { changed=action.apply(source,stored.state()); } catch (IllegalArgumentException | ArithmeticException e) { throw invalid(e); }
        int version=stored.version()+1;
        insert(id,version,changed,actor);
        jdbc.update("update vsm.operational_session set current_version=? where id=?",version,id);
        return get(id);
    }
    private Stored load(UUID id,boolean lock) {
        // Lock head first. A waiter then reads the latest revision after the first transaction commits.
        var heads=jdbc.query("select source_snapshot_id,current_version from vsm.operational_session where id=?"+(lock?" for update":""),
                (rs,n) -> new Object[]{rs.getObject(1,UUID.class),rs.getInt(2)},id);
        if (heads.isEmpty()) throw new PlanningService.NotFoundException("operational session not found");
        var head=heads.getFirst(); UUID snapshot=(UUID)head[0]; int version=(Integer)head[1];
        return jdbc.queryForObject("select payload::text,state_hash from vsm.operational_revision where session_id=? and version=?",
                (rs,n) -> new Stored(snapshot,version,rs.getString(2),json.readValue(rs.getString(1),State.class)),id,version);
    }
    private FleetSource source(Stored stored) {
        return FleetSource.read(snapshots.findById(stored.snapshotId()).orElseThrow(() -> new PlanningService.NotFoundException("source snapshot not found")));
    }
    private void insert(UUID id,int version,State state,String actor) {
        jdbc.update("insert into vsm.operational_revision(session_id,version,actor,payload) values (?,?,?,?::jsonb)",id,version,actor,json.writeValueAsString(state));
    }
    private static PlanningService.InvalidRequestException invalid(Exception e) { return new PlanningService.InvalidRequestException("operations",e.getMessage()); }
}
