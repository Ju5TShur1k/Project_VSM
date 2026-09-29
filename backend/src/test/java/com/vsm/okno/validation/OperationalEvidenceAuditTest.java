package com.vsm.okno.validation;

import com.vsm.okno.dto.Dto;
import com.vsm.okno.planning.OperationalConstraints;
import com.vsm.okno.planning.PlannerRequest;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.ScenarioSnapshot;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class OperationalEvidenceAuditTest {
    private static final OffsetDateTime START=OffsetDateTime.parse("2031-07-01T00:00:00+03:00");
    private static final UUID WORK=id(100), CHECK=id(101);
    private static final OperationalEvidenceAudit.CleaningPolicy CLEANING=new OperationalEvidenceAudit.CleaningPolicy(4,120,180,"synthetic case policy");

    @Test void cleaningMustActuallyFinishAfterFourthArrivalAndBeforeFifthDeparture() {
        var trips=fiveTrips();
        var block=block(WORK,1,"PATH",150,ScenarioSnapshot.ServiceBlock.Kind.CLEANING,List.of());
        var source=source(1,List.of(block),trips,List.of());
        var good=result(source,List.of(placed(WORK,1,"PATH",70,220)));
        assertNoCritical(OperationalEvidenceAudit.cleaning(source,good,Map.of(id(1),0),CLEANING));
        assertCode(OperationalEvidenceAudit.cleaning(source,result(source,List.of()),Map.of(id(1),0),CLEANING),"D2_CLEANING_MISSING");
        assertCode(OperationalEvidenceAudit.cleaning(source,result(source,List.of(placed(WORK,1,"PATH",170,320))),Map.of(id(1),0),CLEANING),"D2_CLEANING_MISSING");
    }

    @Test void cleaningCounterAndDurationAreNeverImplicit() {
        var source=source(1,List.of(block(WORK,1,"PATH",150,ScenarioSnapshot.ServiceBlock.Kind.CLEANING,List.of())),fiveTrips(),List.of());
        assertCode(OperationalEvidenceAudit.cleaning(source,result(source,List.of()),Map.of(),CLEANING),"D2_CLEANING_COUNTER_MISSING");
        assertCode(OperationalEvidenceAudit.cleaning(source,result(source,List.of(placed(WORK,1,"PATH",70,100))),Map.of(id(1),0),CLEANING),"D2_CLEANING_DURATION");
        assertCode(OperationalEvidenceAudit.cleaning(source,result(source,List.of()),Map.of(id(1),0),null),"D2_CLEANING_POLICY_MISSING");
    }

    @Test void cleaningAfterLastFourthTripRemainsAnExplicitPendingObligation() {
        var source=source(1,List.of(),fiveTrips().subList(0,4),List.of());
        var findings=OperationalEvidenceAudit.cleaning(source,result(source,List.of()),Map.of(id(1),0),CLEANING);
        assertNoCritical(findings);
        assertTrue(findings.stream().anyMatch(f -> f.code().equals("D2_CLEANING_PENDING")));
    }

    @Test void checksReserveByCityAtEveryBoundaryEvenWhenFourAreIdleOverall() {
        var source=source(5,List.of(block(WORK,5,"PATH",100,ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE,List.of())),List.of(),List.of());
        List<OperationalEvidenceAudit.ReserveWindow> eligible=new ArrayList<>();
        for(int i=1;i<=5;i++) eligible.add(new OperationalEvidenceAudit.ReserveWindow(id(i),i<=3?"MSK":"SPB",START,START.plusMinutes(600),"synthetic eligibility"));
        var requirements=List.of(new OperationalEvidenceAudit.ReserveRequirement("MSK",START,START.plusMinutes(600),2,"synthetic policy"),
                new OperationalEvidenceAudit.ReserveRequirement("SPB",START,START.plusMinutes(600),2,"synthetic policy"));
        assertNoCritical(OperationalEvidenceAudit.reserve(source,result(source,List.of()),eligible,requirements));
        var findings=OperationalEvidenceAudit.reserve(source,result(source,List.of(placed(WORK,5,"PATH",100,200))),eligible,requirements);
        assertCode(findings,"D2_RESERVE_CITY_SHORTAGE");
        var shortage=findings.stream().filter(f -> f.code().equals("D2_RESERVE_CITY_SHORTAGE")).findFirst().orElseThrow();
        assertEquals("SPB",shortage.objectId()); assertEquals(START.plusMinutes(100).toString(),shortage.startAt()); assertEquals(START.plusMinutes(200).toString(),shortage.endAt());
        assertCode(OperationalEvidenceAudit.reserve(source,result(source,List.of()),List.of(),requirements),"D2_RESERVE_EVIDENCE_MISSING");
    }

    @Test void reserveRequirementCanExplicitlyChangeWhenReserveIsConsumed() {
        var source=source(4,List.of(block(WORK,4,"PATH",100,ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE,List.of())),List.of(),List.of());
        List<OperationalEvidenceAudit.ReserveWindow> eligible=new ArrayList<>();
        for(int i=1;i<=4;i++) eligible.add(new OperationalEvidenceAudit.ReserveWindow(id(i),i<=2?"MSK":"SPB",START,START.plusMinutes(600),"synthetic"));
        var requirements=List.of(new OperationalEvidenceAudit.ReserveRequirement("SPB",START,START.plusMinutes(100),2,"before use"),
                new OperationalEvidenceAudit.ReserveRequirement("SPB",START.plusMinutes(100),START.plusMinutes(200),1,"explicit temporary consumption"),
                new OperationalEvidenceAudit.ReserveRequirement("SPB",START.plusMinutes(200),START.plusMinutes(600),2,"restored"));
        assertNoCritical(OperationalEvidenceAudit.reserve(source,result(source,List.of(placed(WORK,4,"PATH",100,200))),eligible,requirements));
    }

    @Test void frozenWorkPreservesTrainResourceBothEndsAndItsExistence() {
        var source=source(1,List.of(block(WORK,1,"PATH",100,ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE,List.of())),List.of(),List.of());
        var pinned=placed(WORK,1,"PATH",100,200);
        assertNoCritical(OperationalEvidenceAudit.frozen(result(source,List.of(pinned)),List.of(pinned)));
        assertCode(OperationalEvidenceAudit.frozen(result(source,List.of(placed(WORK,1,"PATH",101,201))),List.of(pinned)),"D2_FROZEN_WORK_CHANGED");
        assertCode(OperationalEvidenceAudit.frozen(result(source,List.of()),List.of(pinned)),"D2_FROZEN_WORK_CHANGED");
    }

    @Test void releaseCheckMustFollowMaintenanceAndFinishBeforeNextDeparture() {
        var source=releaseSource();
        assertNoCritical(OperationalEvidenceAudit.release(source,result(source,List.of(placed(WORK,1,"PATH",0,50),placed(CHECK,1,"PATH",50,70)))));
        assertCode(OperationalEvidenceAudit.release(source,result(source,List.of(placed(WORK,1,"PATH",0,50)))),"D2_RELEASE_CHECK_MISSING_OR_EARLY");
        assertCode(OperationalEvidenceAudit.release(source,result(source,List.of(placed(WORK,1,"PATH",0,50),placed(CHECK,1,"PATH",40,60)))),"D2_RELEASE_CHECK_MISSING_OR_EARLY");
        assertCode(OperationalEvidenceAudit.release(source,result(source,List.of(placed(WORK,1,"PATH",150,200),placed(CHECK,1,"PATH",230,250)))),"D2_RELEASE_AFTER_DEPARTURE");
    }

    @Test void preparationIsCheckedEvenIfWorkDoesNotOverlapThePassengerTrip() {
        var source=source(1,List.of(block(WORK,1,"PATH",10,ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE,List.of())),List.of(trip(1,1,200,210)),List.of());
        var result=result(source,List.of(placed(WORK,1,"PATH",180,190)));
        assertCode(OperationalEvidenceAudit.preparation(source,result,Map.of(id(1),20)),"D2_PREPARATION_CONFLICT");
        assertCode(OperationalEvidenceAudit.preparation(source,result,Map.of()),"D2_PREPARATION_RULE_MISSING");
        assertNoCritical(OperationalEvidenceAudit.preparation(source,result(source,List.of(placed(WORK,1,"PATH",170,180))),Map.of(id(1),20)));
    }

    @Test void wheelLatheWorkCannotBeMovedToMoscow() {
        var source=source(1,List.of(),List.of(),List.of());
        var placed=result(source,List.of(placed(WORK,1,"MSK-LATHE",0,100)));
        assertCode(OperationalEvidenceAudit.locations(placed,Map.of(WORK,"SPB"),Map.of("MSK-LATHE","MSK")),"D2_REQUIRED_LOCATION");
        assertNoCritical(OperationalEvidenceAudit.locations(result(source,List.of(placed(WORK,1,"SPB-LATHE",0,100))),Map.of(WORK,"SPB"),Map.of("SPB-LATHE","SPB")));
    }

    @Test void calendarDeadlineAndPendingBeyondHorizonStaySeparate() {
        var source=source(1,List.of(),List.of(),List.of());
        var due=List.of(new OperationalEvidenceAudit.CalendarDue(WORK,START.plusMinutes(100),"synthetic 365-day deadline"));
        assertCode(OperationalEvidenceAudit.calendar(source,result(source,List.of()),due),"D2_CALENDAR_DEADLINE");
        assertCode(OperationalEvidenceAudit.calendar(source,result(source,List.of(placed(WORK,1,"PATH",0,150))),due),"D2_CALENDAR_DEADLINE");
        var pending=OperationalEvidenceAudit.calendar(source,result(source,List.of()),List.of(new OperationalEvidenceAudit.CalendarDue(WORK,START.plusMinutes(700),"synthetic")));
        assertNoCritical(pending); assertEquals("D2_CALENDAR_PENDING",pending.getFirst().code());
    }

    @Test void bothSeparateTrainsetsMustBePresentInPairedTrip() {
        var a=trip(1,1,200,210); var b=trip(2,2,200,210);
        var source=source(2,List.of(),List.of(a,b),List.of());
        assertNoCritical(OperationalEvidenceAudit.pairs(source,Map.of(id(1),"pair-run",id(2),"pair-run")));
        assertCode(OperationalEvidenceAudit.pairs(source(2,List.of(),List.of(a),List.of()),Map.of(id(1),"pair-run")),"D2_PAIR_INCOMPLETE");
        assertCode(OperationalEvidenceAudit.pairs(source,Map.of(id(1),"pair-run")),"D2_PAIR_EVIDENCE_MISSING");
    }

    @Test void sixIndependentPathsDoNotOverrideFiveTrainMaintenanceLimit() {
        List<ScenarioSnapshot.ServiceBlock> blocks=new ArrayList<>(); List<PlannerResult.PlannedBlock> placed=new ArrayList<>();
        for(int i=1;i<=6;i++) { blocks.add(block(id(100+i),i,"PATH-"+i,100,ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE,List.of())); placed.add(placed(id(100+i),i,"PATH-"+i,100,200)); }
        var source=source(6,blocks,List.of(),List.of());
        assertCode(OperationalEvidenceAudit.maintenanceCapacity(source,result(source,placed),5,"synthetic explicit unit=trainset"),"D2_MAINTENANCE_CAPACITY");
        assertNoCritical(OperationalEvidenceAudit.maintenanceCapacity(source,result(source,placed.subList(0,5)),5,"synthetic"));
    }

    private static List<ScenarioSnapshot.FixedTrip> fiveTrips() { return List.of(trip(1,1,0,10),trip(2,1,20,30),trip(3,1,40,50),trip(4,1,60,70),trip(5,1,300,310)); }
    private static ScenarioSnapshot releaseSource() {
        return source(1,List.of(block(WORK,1,"PATH",50,ScenarioSnapshot.ServiceBlock.Kind.RELEASE_GATED_MAINTENANCE,List.of()),
                block(CHECK,1,"PATH",20,ScenarioSnapshot.ServiceBlock.Kind.RELEASE_CHECK,List.of(WORK))),
                List.of(trip(1,1,200,210)),List.of(new OperationalConstraints.ReleaseRequirement(WORK,CHECK,"synthetic")));
    }
    private static ScenarioSnapshot source(int count,List<ScenarioSnapshot.ServiceBlock> blocks,List<ScenarioSnapshot.FixedTrip> trips,List<OperationalConstraints.ReleaseRequirement> release) {
        List<ScenarioSnapshot.Train> trains=new ArrayList<>(); for(int i=1;i<=count;i++) trains.add(new ScenarioSnapshot.Train(id(i),"TRAIN-"+i));
        Set<String> resourceIds=new HashSet<>(); resourceIds.add("PATH"); blocks.forEach(b -> resourceIds.add(b.resourceId()));
        var resources=resourceIds.stream().map(ScenarioSnapshot.Resource::new).toList();
        var windows=trains.stream().flatMap(t -> resources.stream().map(r -> new OperationalConstraints.ServiceWindow(t.id(),r.id(),0,600,"synthetic"))).toList();
        var operations=new OperationalConstraints(Set.of(),List.of(),windows,List.of(),release);
        return new ScenarioSnapshot("1.3",id(999),"ops-fixture","synthetic",START,START.plusMinutes(600),trains,resources,blocks,trips,operations);
    }
    private static ScenarioSnapshot.ServiceBlock block(UUID id,int train,String resource,int duration,ScenarioSnapshot.ServiceBlock.Kind kind,List<UUID> predecessors) {
        return new ScenarioSnapshot.ServiceBlock(id,id(train),resource,duration,0,600,predecessors,kind);
    }
    private static ScenarioSnapshot.FixedTrip trip(int id,int train,int start,int end) { return new ScenarioSnapshot.FixedTrip(id(id),id(train),"PAIR",start,end,670,"synthetic"); }
    private static PlannerResult.PlannedBlock placed(UUID id,int train,String resource,int start,int end) { return new PlannerResult.PlannedBlock(id,id(train),resource,START.plusMinutes(start),START.plusMinutes(end)); }
    private static PlannerResult result(ScenarioSnapshot source,List<PlannerResult.PlannedBlock> placed) { return new PlannerResult("1.0",source.scenarioId(),source.snapshotHash(),PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT,PlannerResult.SolverStatus.FEASIBLE,placed,List.of(),42,1,null); }
    private static UUID id(long n) { return new UUID(0,n); }
    private static void assertCode(List<Dto.Validation> findings,String code) { assertTrue(findings.stream().anyMatch(f -> f.code().equals(code)),findings.toString()); }
    private static void assertNoCritical(List<Dto.Validation> findings) { assertTrue(findings.stream().noneMatch(f -> f.severity().equals("CRITICAL")),findings.toString()); }
}
