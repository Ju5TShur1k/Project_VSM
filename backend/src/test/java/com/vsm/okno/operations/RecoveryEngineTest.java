package com.vsm.okno.operations;

import static com.vsm.okno.operations.RecoveryModel.*;
import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

class RecoveryEngineTest {
    final RecoveryEngine engine=new RecoveryEngine();
    final OffsetDateTime start=OffsetDateTime.parse("2031-07-01T00:00:00+03:00");
    final UUID line=id(1),partner=id(2),reserve=id(42),otherCity=id(40),trip=id(101);
    static UUID id(long value) { return new UUID(0,value); }
    FleetSource source(Integer counter,long km,List<FleetSource.Occupation> occupied) {
        return new FleetSource(id(98),id(99),"fixture",start,start.plusDays(1),List.of(
                new FleetSource.Train(line,"CASE-01","AVAILABLE","SPB_DEPOT",1000,0,"SYNTHETIC",Map.of("IS100",0L)),
                new FleetSource.Train(partner,"CASE-02","AVAILABLE","SPB_DEPOT",1000,0,"SYNTHETIC",Map.of("IS100",0L)),
                new FleetSource.Train(reserve,"CASE-42","RESERVE","SPB_DEPOT",km,counter,"SYNTHETIC",Map.of("IS100",0L)),
                new FleetSource.Train(otherCity,"CASE-40","RESERVE","MOSCOW",1000,0,"SYNTHETIC",Map.of("IS100",0L))),
                List.of(new FleetSource.Trip(trip,line,"PAIR-1-R1","SPB_DEPOT","MOSCOW",start.plusHours(6),start.plusHours(8),670),
                        new FleetSource.Trip(id(102),partner,"PAIR-1-R1","SPB_DEPOT","MOSCOW",start.plusHours(6),start.plusHours(8),670),
                        new FleetSource.Trip(id(103),line,"PAIR-1-R2","MOSCOW","SPB_DEPOT",start.plusHours(12),start.plusHours(14),670),
                        new FleetSource.Trip(id(104),partner,"PAIR-1-R2","MOSCOW","SPB_DEPOT",start.plusHours(12),start.plusHours(14),670)),
                occupied,List.of(new FleetSource.Presence(reserve,"SPB_DEPOT",start,start.plusDays(1),"SYNTHETIC"),
                new FleetSource.Presence(otherCity,"MOSCOW",start,start.plusDays(1),"SYNTHETIC")),List.of(new FleetSource.Rule("IS100",12500,1000)));
    }
    State failed(FleetSource source) { return engine.failure(source,engine.initial(source),new FailureRequest(0,trip,start.plusHours(5),start.plusHours(9),"Блокирующий отказ")); }

    @Test void sameCityReserveCoversWholeTurnKeepingBothPairMembersAndRoute() {
        var src=source(0,1000,List.of()); var state=failed(src); var f=state.faults().getFirst();
        assertTrue(engine.candidate(src,state,f,reserve).eligible());
        assertFalse(engine.candidate(src,state,f,otherCity).eligible());
        state=engine.replace(src,state,new ReplacementRequest(1,f.id(),reserve),"dispatcher");
        var board=engine.board(id(1000),2,"hash",src,state);
        assertEquals(0,board.uncoveredTripCount()); assertEquals(0,board.incompletePairCount());
        assertEquals(2,board.trips().stream().filter(t -> t.coverage().equals("REPLACED")).count());
        assertEquals(0,board.reserve().stream().filter(r -> r.location().equals("SPB_DEPOT")).findFirst().orElseThrow().available());
        assertEquals("LINE",board.trains().stream().filter(t -> t.id().equals(reserve)).findFirst().orElseThrow().status());
        assertTrue(board.messages().stream().anyMatch(m -> m.contains("ДЕФИЦИТ РЕЗЕРВА")));
    }
    @Test void insufficientPreparationNeverOffersAnImpossibleLateSubstitution() {
        var src=source(0,1000,List.of());
        var state=engine.failure(src,engine.initial(src),new FailureRequest(0,trip,start.plusHours(5).plusMinutes(10),null,"Отказ"));
        assertFalse(engine.candidate(src,state,state.faults().getFirst(),reserve).eligible());
        assertThrows(IllegalArgumentException.class,()->engine.replace(src,state,new ReplacementRequest(1,state.faults().getFirst().id(),reserve),"actor"));
    }
    @Test void maintenanceFrozenAndCleaningConflictRemoveCandidate() {
        for (String kind:List.of("UNAVAILABLE","CLEANING","FROZEN")) {
            var src=source(0,1000,List.of(new FleetSource.Occupation(reserve,kind,start.plusHours(5),start.plusHours(7))));
            var state=failed(src); assertFalse(engine.candidate(src,state,state.faults().getFirst(),reserve).eligible());
        }
    }
    @Test void missingOrExhaustedCleaningEvidenceCannotBecomeImplicitReadiness() {
        for (Integer count:new Integer[]{null,4}) {
            var src=source(count,1000,List.of()); var state=failed(src);
            assertFalse(engine.candidate(src,state,state.faults().getFirst(),reserve).eligible());
        }
        var src=source(3,1000,List.of()); var state=failed(src);
        assertFalse(engine.candidate(src,state,state.faults().getFirst(),reserve).eligible(),"Fourth trip is allowed, fifth needs cleaning");
    }
    @Test void replacementMileageMustCoverEntireProposedTurnWithinTolerance() {
        var src=source(0,13000,List.of()); var state=failed(src);
        var candidate=engine.candidate(src,state,state.faults().getFirst(),reserve);
        assertFalse(candidate.eligible()); assertTrue(candidate.reasons().stream().anyMatch(r -> r.contains("Перепробег")));
    }
    @Test void repairEstimateNeverAutomaticallyRestoresReserveAndAcceptanceDoesNotMoveTrain() {
        var src=source(0,1000,List.of()); var state=failed(src); var f=state.faults().getFirst();
        state=engine.replace(src,state,new ReplacementRequest(1,f.id(),reserve),"actor");
        state=engine.clock(src,state,start.plusHours(10));
        assertEquals("FAILED",engine.board(id(1000),3,"h",src,state).trains().stream().filter(t -> t.id().equals(line)).findFirst().orElseThrow().status());
        var current=state;
        assertThrows(IllegalArgumentException.class,()->engine.release(src,current,new ReleaseRequest(3,f.id(),start.plusHours(9),start.plusHours(10),"MOSCOW","test acceptance"),"actor"));
        assertThrows(IllegalArgumentException.class,()->engine.release(src,current,new ReleaseRequest(3,f.id(),start.plusHours(11),start.plusHours(10),"SPB_DEPOT","test"),"actor"));
        state=engine.release(src,state,new ReleaseRequest(3,f.id(),start.plusHours(9),start.plusHours(10),"SPB_DEPOT","MODELLED acceptance"),"actor");
        var board=engine.board(id(1000),4,"h",src,state);
        assertEquals("RESERVE",board.trains().stream().filter(t -> t.id().equals(line)).findFirst().orElseThrow().status());
        assertEquals("В ПУТИ: MOSCOW → SPB_DEPOT",engine.board(id(1000),5,"h",src,engine.clock(src,state,start.plusHours(13))).trains().stream().filter(t -> t.id().equals(reserve)).findFirst().orElseThrow().location());
    }
    @Test void sourceReserveOccupationDoesNotForbidItsOperationalUse() {
        var src=source(0,1000,List.of(new FleetSource.Occupation(reserve,"RESERVE",start,start.plusDays(1))));
        var state=failed(src); assertTrue(engine.candidate(src,state,state.faults().getFirst(),reserve).eligible());
    }
    @Test void inTransitFailureAndTimeReversalAreRejected() {
        var src=source(0,1000,List.of()); var state=engine.initial(src);
        assertThrows(IllegalArgumentException.class,()->engine.failure(src,state,new FailureRequest(0,trip,start.plusHours(6),null,"Отказ")));
        var future=engine.clock(src,state,start.plusHours(8));
        assertThrows(IllegalArgumentException.class,()->engine.clock(src,future,start.plusHours(7)));
    }
    @Test void failureCannotLeaveAnEarlierFutureTripAssignedToAFailedTrain() {
        var src=source(0,1000,List.of());
        var trips=new java.util.ArrayList<>(src.trips());
        trips.add(new FleetSource.Trip(id(105),line,"R3","SPB_DEPOT","MOSCOW",start.plusHours(18),start.plusHours(20),670));
        var extended=new FleetSource(src.scenarioId(),src.snapshotId(),src.hash(),src.start(),src.end(),src.trains(),List.copyOf(trips),src.occupations(),src.presences(),src.rules());
        assertThrows(IllegalArgumentException.class,()->engine.failure(extended,engine.initial(extended),new FailureRequest(0,id(105),start.plusHours(5),null,"Отказ")));
    }
}
