package com.vsm.okno.service;

import com.vsm.okno.dto.Dto;
import com.vsm.okno.store.Store;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class PlanningService {

    private final Store store = new Store();

    public Dto.ImportResponse importScenario(Dto.ImportRequest request) {
        boolean noTrains = request == null || request.trains() == null || request.trains().isEmpty();
        List<Dto.Train> trains = noTrains ? syntheticFleet(43) : request.trains();

        List<String> warnings = new ArrayList<>();
        if (noTrains) {
            warnings.add("no trains provided, generated 43 synthetic trains");
        }

        Store.Scenario scenario = new Store.Scenario();
        scenario.id = UUID.randomUUID();
        scenario.createdAt = Instant.now();
        scenario.provenance = "synthetic";
        scenario.trains = trains;
        store.scenarios.put(scenario.id, scenario);

        return new Dto.ImportResponse(scenario.id, warnings, scenario.provenance);
    }

    public Dto.Scenario getScenario(UUID id) {
        Store.Scenario s = require(store.scenarios, id, "scenario");
        return new Dto.Scenario(s.id, s.createdAt, s.provenance, s.trains.size());
    }

    public List<Dto.Train> getTrains(UUID scenarioId) {
        return require(store.scenarios, scenarioId, "scenario").trains;
    }

    // ponytail: job "worker" resolves synchronously in-process instead of a real
    // queue + CP-SAT solver — that's F2/D2's territory. This just proves the API
    // contract (QUEUED/RUNNING/SUCCEEDED, solverStatus, idempotency) end to end.
    public Dto.JobStatus createJob(Dto.JobRequest req) {
        require(store.scenarios, req.scenarioId(), "scenario");

        if (req.idempotencyKey() != null) {
            UUID existing = store.jobIdempotency.get(req.idempotencyKey());
            if (existing != null) {
                return toJobStatus(store.jobs.get(existing));
            }
        }

        Store.Plan plan = buildMockPlan(req.scenarioId());
        store.plans.put(plan.id, plan);

        Store.PlanningJob job = new Store.PlanningJob();
        job.id = UUID.randomUUID();
        job.scenarioId = req.scenarioId();
        job.status = "SUCCEEDED";
        job.solverStatus = "FEASIBLE";
        job.planId = plan.id;
        store.jobs.put(job.id, job);

        if (req.idempotencyKey() != null) {
            store.jobIdempotency.put(req.idempotencyKey(), job.id);
        }
        return toJobStatus(job);
    }

    public Dto.JobStatus getJob(UUID id) {
        return toJobStatus(require(store.jobs, id, "job"));
    }

    public Dto.Plan getPlan(UUID id) {
        return toPlanDto(require(store.plans, id, "plan"));
    }

    public Dto.Plan approve(UUID planId, Dto.ApproveRequest req, String actor) {
        Store.Plan plan = require(store.plans, planId, "plan");
        if (plan.version != req.expectedVersion()) {
            throw new VersionConflictException(plan.version);
        }
        plan.approvedBy = actor;
        plan.status = "APPROVED";
        plan.version += 1;
        return toPlanDto(plan);
    }

    public String exportCsv(UUID planId) {
        Store.Plan plan = require(store.plans, planId, "plan");
        StringBuilder csv = new StringBuilder("trainId,kind,startAt,endAt\n");
        for (Dto.PlanEvent e : plan.events) {
            csv.append(e.trainId()).append(',').append(e.kind()).append(',')
                    .append(e.startAt()).append(',').append(e.endAt()).append('\n');
        }
        return csv.toString();
    }

    public UUID newScenarioVersion(UUID scenarioId, Dto.ScenarioEvent event) {
        Store.Scenario base = require(store.scenarios, scenarioId, "scenario");
        Store.Scenario copy = new Store.Scenario();
        copy.id = UUID.randomUUID();
        copy.createdAt = Instant.now();
        copy.provenance = base.provenance + "+" + event.kind();
        copy.trains = base.trains;
        store.scenarios.put(copy.id, copy);
        return copy.id;
    }

    private Store.Plan buildMockPlan(UUID scenarioId) {
        Store.Plan plan = new Store.Plan();
        plan.id = UUID.randomUUID();
        plan.scenarioId = scenarioId;
        plan.version = 0;
        plan.status = "DRAFT";
        plan.events = List.of();
        plan.validations = List.of();
        return plan;
    }

    private Dto.JobStatus toJobStatus(Store.PlanningJob job) {
        return new Dto.JobStatus(job.id, job.status, job.solverStatus, job.planId);
    }

    private Dto.Plan toPlanDto(Store.Plan plan) {
        return new Dto.Plan(plan.id, plan.scenarioId, plan.version, plan.status, plan.events, plan.validations, plan.approvedBy);
    }

    private static List<Dto.Train> syntheticFleet(int count) {
        List<Dto.Train> trains = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            boolean reserve = i <= 4;
            trains.add(new Dto.Train(
                    UUID.randomUUID(),
                    "EVS-" + String.format("%03d", i),
                    reserve ? "HOT_RESERVE" : "READY_IDLE",
                    0,
                    "IS100"
            ));
        }
        return trains;
    }

    private static <K, V> V require(Map<K, V> map, K id, String what) {
        V v = map.get(id);
        if (v == null) throw new NotFoundException(what + " not found: " + id);
        return v;
    }

    public static class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }

    public static class VersionConflictException extends RuntimeException {
        public final int currentVersion;

        public VersionConflictException(int currentVersion) {
            super("version conflict");
            this.currentVersion = currentVersion;
        }
    }
}
