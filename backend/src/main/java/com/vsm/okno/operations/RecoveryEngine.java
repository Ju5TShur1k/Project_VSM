package com.vsm.okno.operations;

import static com.vsm.okno.operations.RecoveryModel.*;

import java.math.BigInteger;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Deterministic advisory response. No solver, random breakdowns, teleportation or automatic release. */
public final class RecoveryEngine {
    public State initial(FleetSource source) {
        return new State("operational-response-1.0", source.snapshotId(),source.hash(),
                new Policy("expert-response-model-v1",55,2,4,
                        "MODELLED: expert reserve exchange; case cleaning every 4 trips; preparation 55 min is an explicit assumption"),
                source.start(),List.of(),List.of(),List.of());
    }

    public State failure(FleetSource source, State state, FailureRequest request) {
        within(source,state,request.occurredAt());
        var trip=source.trip(request.tripId());
        if (!request.occurredAt().isBefore(trip.departure())) throw bad("Поддерживается отказ до отправления, а не отказ в пути");
        if (request.expectedRepairAt()!=null && !request.expectedRepairAt().isAfter(request.occurredAt())) throw bad("Прогноз ремонта должен быть позже отказа");
        if (request.description()==null || request.description().isBlank() || request.description().length()>1000) throw bad("Нужно описание отказа до 1000 символов");
        var at=clock(source,state,request.occurredAt());
        UUID train=effectiveTrain(state,trip);
        if (!covered(state,trip) || role(source,at,train).equals("FAILED")) throw bad("У рейса уже нет допущенного назначенного состава");
        if (!location(source,at,train).equals(trip.origin())) throw bad("Состав ещё не находится в городе отправления");
        if (source.trips().stream().anyMatch(t -> effectiveTrain(state,t).equals(train) && covered(state,t)
                && !t.departure().isBefore(request.occurredAt()) && t.departure().isBefore(trip.departure())))
            throw bad("Выберите ближайший рейс состава после отказа: нельзя оставлять более ранний рейс за неисправным поездом");
        var affected=source.trips().stream().filter(t -> !t.departure().isBefore(trip.departure())
                && effectiveTrain(state,t).equals(train) && covered(state,t)).map(FleetSource.Trip::id).toList();
        var fault=new Fault(nextSequence(state),UUID.randomUUID(),train,trip.id(),request.occurredAt(),request.expectedRepairAt(),request.description().trim(),affected);
        return new State(state.schemaVersion(),state.snapshotId(),state.snapshotHash(),state.policy(),request.occurredAt(),
                append(state.faults(),fault),state.assignments(),state.releases());
    }

    public State replace(FleetSource source,State state, ReplacementRequest request,String actor) {
        var fault=fault(state,request.faultId());
        if (state.assignments().stream().anyMatch(a -> a.faultId().equals(fault.id()))) throw bad("Замена этого отказа уже согласована");
        var candidate=candidate(source,state,fault,request.trainId());
        if (!candidate.eligible()) throw bad("Замена запрещена: " + String.join("; ",candidate.reasons()));
        var assignment=new Assignment(nextSequence(state),fault.id(),request.trainId(),state.asOf(),fault.affectedTripIds(),actor);
        return new State(state.schemaVersion(),state.snapshotId(),state.snapshotHash(),state.policy(),state.asOf(),
                state.faults(),append(state.assignments(),assignment),state.releases());
    }

    public State release(FleetSource source,State state,ReleaseRequest request,String actor) {
        var fault=fault(state,request.faultId());
        within(source,state,request.acceptedAt());
        if (state.releases().stream().anyMatch(r -> r.faultId().equals(fault.id()))) throw bad("Приёмка этого отказа уже записана");
        if (request.repairCompletedAt()==null || request.repairCompletedAt().isBefore(fault.occurredAt())
                || request.repairCompletedAt().isAfter(request.acceptedAt())) throw bad("Нужен факт завершения ремонта не позже приёмки");
        var at=clock(source,state,request.acceptedAt());
        if (!location(source,at,fault.trainId()).equals(request.location())) throw bad("Город приёмки не совпадает с местоположением состава");
        if (request.acceptanceReference()==null || request.acceptanceReference().isBlank() || request.acceptanceReference().length()>200)
            throw bad("Нужен номер или описание документа приёмки до 200 символов");
        var release=new Release(nextSequence(state),fault.id(),request.repairCompletedAt(),request.acceptedAt(),request.location(),request.acceptanceReference().trim(),actor);
        return new State(state.schemaVersion(),state.snapshotId(),state.snapshotHash(),state.policy(),request.acceptedAt(),
                state.faults(),state.assignments(),append(state.releases(),release));
    }

    public State clock(FleetSource source,State state,OffsetDateTime asOf) {
        within(source,state,asOf);
        return new State(state.schemaVersion(),state.snapshotId(),state.snapshotHash(),state.policy(),asOf,state.faults(),state.assignments(),state.releases());
    }

    public Board board(UUID id,int version,String hash,FleetSource source,State state) {
        if (!source.hash().equals(state.snapshotHash()) || !source.snapshotId().equals(state.snapshotId())) throw bad("Источник оперативной версии изменён");
        List<TrainView> trains=new ArrayList<>();
        for (var t:source.trains()) trains.add(new TrainView(t.id(),t.name(),role(source,state,t.id()),location(source,state,t.id()),
                mileage(source,state,t.id()),counter(source,state,t.id()),"MODELLED: исходное состояние; факты приёмки отдельно, прогноз ремонта не является допуском"));
        List<TripView> trips=new ArrayList<>();
        Map<String,Integer> pairs=new HashMap<>();
        for (var t:source.trips()) {
            UUID effective=effectiveTrain(state,t); boolean covered=covered(state,t);
            String pair=t.label()+"@"+t.departure().toInstant();
            pairs.merge(pair,covered?1:0,Integer::sum);
            trips.add(new TripView(t.id(),t.label(),pair,t.trainId(),source.train(t.trainId()).name(),effective,
                    source.train(effective).name(),t.origin(),t.destination(),t.departure(),t.arrival(),
                    !covered?"UNCOVERED":effective.equals(t.trainId())?"SCHEDULED":"REPLACED"));
        }
        List<FaultView> faults=new ArrayList<>();
        for (var f:state.faults()) {
            var assignment=state.assignments().stream().filter(a -> a.faultId().equals(f.id())).findFirst().orElse(null);
            var release=state.releases().stream().filter(r -> r.faultId().equals(f.id())).findFirst().orElse(null);
            List<Candidate> candidates=assignment==null?source.trains().stream()
                    .filter(t -> t.status().equals("RESERVE") || state.releases().stream().anyMatch(r -> fault(state,r.faultId()).trainId().equals(t.id())))
                    .map(t -> candidate(source,state,f,t.id())).toList():List.of();
            faults.add(new FaultView(f.id(),f.trainId(),source.train(f.trainId()).name(),source.trip(f.tripId()).label(),f.occurredAt(),
                    f.expectedRepairAt(),f.description(),release!=null?"RELEASED_TO_RESERVE":assignment!=null?"REPLACEMENT_ASSIGNED":"NEEDS_REPLACEMENT",
                    f.affectedTripIds(),assignment==null?null:source.train(assignment.trainId()).name(),candidates,release==null?null:release.acceptedAt()));
        }
        List<ReserveView> reserve=new ArrayList<>(); List<String> messages=new ArrayList<>();
        for (String city:List.of("MOSCOW","SPB_DEPOT")) {
            int available=(int)trains.stream().filter(t -> t.status().equals("RESERVE") && t.location().equals(city)
                    && readyReasons(source,state,t.id()).isEmpty()).count();
            int deficit=Math.max(0,state.policy().targetReservePerCity()-available);
            reserve.add(new ReserveView(city,available,state.policy().targetReservePerCity(),deficit));
            if (deficit>0) messages.add("ДЕФИЦИТ РЕЗЕРВА: "+city+" — доступно "+available+" из целевых "+state.policy().targetReservePerCity()+
                    ". Выход резерва на линию разрешён; восстановление — после ремонта и приёмки.");
        }
        int uncovered=(int)trips.stream().filter(t -> t.coverage().equals("UNCOVERED")).count();
        if (uncovered>0) messages.add("НЕПОКРЫТЫЕ РЕЙСЫ: "+uncovered+". Выпуск неисправного состава запрещён. Нужна замена; без неё парная отправка не подтверждена.");
        for (var f:faults) if (f.status().equals("NEEDS_REPLACEMENT") && f.candidates().stream().noneMatch(Candidate::eligible))
            messages.add("НЕТ ДОПУСТИМОЙ ЗАМЕНЫ для "+f.train()+" / "+f.trip()+". Причины показаны по каждому резервному составу.");
        int available=(int)trains.stream().filter(t -> List.of("LINE","RESERVE").contains(t.status())
                && readyReasons(source,state,t.id()).isEmpty()).count();
        messages.add("Модельный оперативный день: сохраняются время, маршрут и оба места каждой парной отправки. График следующих суток требует полного E3-пересчёта.");
        messages.add("Коэффициент эксплуатационной готовности 88%/89% не рассчитан: доля доступных составов в момент времени не заменяет согласованную формулу КЭГ.");
        return new Board(id,version,hash,source.scenarioId(),source.snapshotId(),source.hash(),"OPERATIONAL_DAY_MODEL",
                "MODELLED FULL43: response to a simulated pre-departure failure; not an approved full E3 maintenance plan",
                source.start(),source.end(),state.asOf(),state.policy(),trains.size(),available,trains.size()-available,uncovered,
                (int)pairs.values().stream().filter(n -> n!=2).count(),List.copyOf(reserve),List.copyOf(trains),List.copyOf(trips),List.copyOf(faults),List.copyOf(messages));
    }

    public Candidate candidate(FleetSource source,State state,Fault fault,UUID trainId) {
        var train=source.train(trainId); var first=source.trip(fault.tripId()); List<String> reasons=new ArrayList<>();
        if (trainId.equals(fault.trainId())) reasons.add("Неисправный состав не может заменить себя");
        if (!role(source,state,trainId).equals("RESERVE")) reasons.add("Состав не находится в свободном резерве");
        String city=location(source,state,trainId);
        if (!city.equals(first.origin())) reasons.add("Другой город: "+city+", нужен "+first.origin());
        if (state.asOf().isAfter(first.departure().minusMinutes(state.policy().preparationMinutes())))
            reasons.add("Недостаточно времени на подготовку: требуется "+state.policy().preparationMinutes()+" мин");
        reasons.addAll(readyReasons(source,state,trainId));
        boolean released=state.releases().stream().anyMatch(r -> fault(state,r.faultId()).trainId().equals(trainId)
                && r.location().equals(first.origin()) && !r.acceptedAt().isAfter(state.asOf()));
        if (!released && source.presences().stream().noneMatch(p -> p.trainId().equals(trainId) && p.location().equals(first.origin())
                && !p.start().isAfter(state.asOf()) && !p.end().isBefore(first.departure())
                && List.of("CONFIRMED","SYNTHETIC").contains(p.confirmation()))) reasons.add("Нет подтверждённого окна присутствия перед отправлением");
        Integer cleaning=counter(source,state,trainId);
        long km=mileage(source,state,trainId); String route=first.origin(); int completed=cleaning==null?0:cleaning;
        var chain=fault.affectedTripIds().stream().map(source::trip).sorted(java.util.Comparator.comparing(FleetSource.Trip::departure)).toList();
        OffsetDateTime freeAt=state.asOf();
        for (var trip:chain) {
            if (!route.equals(trip.origin()) || trip.departure().isBefore(freeAt)) reasons.add("Нельзя сохранить непрерывность оборота на "+trip.label());
            if (completed>=state.policy().cleaningEveryTrips()) reasons.add("Нужна принятая уборка после четвёртого рейса до "+trip.label());
            if (source.occupations().stream().anyMatch(o -> o.trainId().equals(trainId) && !o.kind().equals("RESERVE")
                    && overlaps(o.start(),o.end(),trip.departure().minusMinutes(state.policy().preparationMinutes()),trip.arrival())))
                reasons.add("Занят ТО, уборкой или закреплённой работой на "+trip.label());
            if (source.trips().stream().anyMatch(other -> !fault.affectedTripIds().contains(other.id()) && covered(state,other)
                    && effectiveTrain(state,other).equals(trainId) && overlaps(other.departure().minusMinutes(state.policy().preparationMinutes()),
                    other.arrival(),trip.departure().minusMinutes(state.policy().preparationMinutes()),trip.arrival())))
                reasons.add("Уже назначен на пересекающийся рейс");
            km=Math.addExact(km,trip.distance());
            reasons.addAll(mileageReasons(source,train,km));
            route=trip.destination(); freeAt=trip.arrival().plusMinutes(state.policy().preparationMinutes()); completed++;
        }
        List<String> unique=reasons.stream().distinct().toList();
        return new Candidate(trainId,train.name(),city,unique.isEmpty(),unique.isEmpty()
                ?List.of("Город, присутствие, подготовка, занятость, уборка, пробег и оставшийся оборот текущих суток проверены"):unique);
    }

    private List<String> readyReasons(FleetSource source,State state,UUID id) {
        var train=source.train(id); List<String> reasons=new ArrayList<>();
        if (role(source,state,id).equals("FAILED")) reasons.add("Нет приёмки после отказа; прогноз ремонта не является допуском");
        if (!List.of("CONFIRMED","SYNTHETIC").contains(train.cleaningEvidence()) || train.cleaningCounter()==null)
            reasons.add("Неизвестен начальный счётчик уборки");
        Integer count=counter(source,state,id);
        if (count!=null && count>=state.policy().cleaningEveryTrips()) reasons.add("Уборка после четвёртого рейса ещё не принята");
        if (occupiedAt(source,id,state.asOf())) reasons.add("Состав занят обслуживанием или недоступен");
        reasons.addAll(mileageReasons(source,train,mileage(source,state,id)));
        return reasons;
    }
    private static List<String> mileageReasons(FleetSource source,FleetSource.Train train,long km) {
        List<String> reasons=new ArrayList<>();
        if (source.rules().isEmpty()) reasons.add("Отсутствуют пробеговые правила");
        for (var rule:source.rules()) {
            Long credit=train.credits().get(rule.code());
            if (credit==null || credit<0 || credit%rule.interval()!=0) { reasons.add("Неизвестен зачёт "+rule.code()); continue; }
            long next=Math.addExact(credit,rule.interval());
            long maximum=BigInteger.valueOf(next).multiply(BigInteger.valueOf(10000L+rule.tolerance())).divide(BigInteger.valueOf(10000)).longValueExact();
            if (km>maximum) reasons.add("Перепробег "+rule.code()+": "+km+" > "+maximum+" км; нужно ТО");
        }
        return reasons;
    }
    private static boolean occupiedAt(FleetSource source,UUID id,OffsetDateTime at) {
        return source.occupations().stream().anyMatch(o -> o.trainId().equals(id) && !o.kind().equals("RESERVE")
                && !at.isBefore(o.start()) && at.isBefore(o.end()));
    }
    private static String role(FleetSource source,State state,UUID id) {
        String role=source.train(id).status().equals("AVAILABLE")?"LINE":source.train(id).status();
        // Recorded decisions are chronological; a released train may subsequently fail or substitute again.
        Map<Integer,String> changes=new java.util.TreeMap<>();
        state.assignments().stream().filter(a -> a.trainId().equals(id) && !a.decidedAt().isAfter(state.asOf())).forEach(a -> changes.put(a.sequence(),"LINE"));
        state.faults().stream().filter(f -> f.trainId().equals(id) && !f.occurredAt().isAfter(state.asOf())).forEach(f -> changes.put(f.sequence(),"FAILED"));
        state.releases().stream().filter(r -> fault(state,r.faultId()).trainId().equals(id) && !r.acceptedAt().isAfter(state.asOf())).forEach(r -> changes.put(r.sequence(),"RESERVE"));
        for (String change:changes.values()) role=change;
        return role;
    }
    private static UUID effectiveTrain(State state,FleetSource.Trip trip) {
        UUID train=trip.trainId(); for (var a:state.assignments()) if (a.tripIds().contains(trip.id())) train=a.trainId(); return train;
    }
    private static boolean covered(State state,FleetSource.Trip trip) {
        UUID train=effectiveTrain(state,trip);
        int assignmentSequence=state.assignments().stream().filter(a -> a.tripIds().contains(trip.id())).mapToInt(Assignment::sequence).max().orElse(0);
        // Release into reserve never silently reassigns the withdrawn turn to the repaired train.
        return state.faults().stream().noneMatch(f -> f.trainId().equals(train) && f.affectedTripIds().contains(trip.id()) && f.sequence()>assignmentSequence);
    }
    private static String location(FleetSource source,State state,UUID id) {
        String city=source.train(id).location();
        for (var t:source.trips()) if (effectiveTrain(state,t).equals(id) && covered(state,t)) {
            if (!state.asOf().isBefore(t.arrival())) city=t.destination();
            else if (!state.asOf().isBefore(t.departure())) return "В ПУТИ: "+t.origin()+" → "+t.destination();
        }
        return city;
    }
    private static long mileage(FleetSource source,State state,UUID id) {
        long km=source.train(id).initialKm();
        for (var t:source.trips()) if (effectiveTrain(state,t).equals(id) && covered(state,t) && !state.asOf().isBefore(t.arrival())) km=Math.addExact(km,t.distance());
        return km;
    }
    private static Integer counter(FleetSource source,State state,UUID id) {
        Integer counter=source.train(id).cleaningCounter(); if (counter==null) return null;
        for (var t:source.trips()) if (effectiveTrain(state,t).equals(id) && covered(state,t) && !state.asOf().isBefore(t.arrival())) counter++;
        // A source CLEANING occupation is only a schedule, not an accepted completion; no reset is invented.
        return counter;
    }
    private static Fault fault(State state,UUID id) { return state.faults().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow(() -> bad("Неизвестный отказ")); }
    private static void within(FleetSource source,State state,OffsetDateTime time) {
        if (time==null || time.isBefore(state.asOf()) || time.isBefore(source.start()) || time.isAfter(source.end())) throw bad("Время должно идти вперёд внутри оперативных суток");
    }
    private static <T> List<T> append(List<T> original,T value) { List<T> copy=new ArrayList<>(original); copy.add(value); return List.copyOf(copy); }
    private static int nextSequence(State state) { return state.faults().size()+state.assignments().size()+state.releases().size()+1; }
    private static boolean overlaps(OffsetDateTime a,OffsetDateTime b,OffsetDateTime c,OffsetDateTime d) { return a.isBefore(d) && c.isBefore(b); }
    private static IllegalArgumentException bad(String text) { return new IllegalArgumentException(text); }
}
