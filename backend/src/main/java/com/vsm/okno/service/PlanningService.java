package com.vsm.okno.service;

import com.vsm.okno.dto.Dto;
import com.vsm.okno.data.DemoSourceService;
import com.vsm.okno.data.CaseDatasetService;
import com.vsm.okno.data.SourceSnapshotE2Adapter;
import com.vsm.okno.data.SourceSnapshotRepository;
import com.vsm.okno.planning.CpSatPlanner;
import com.vsm.okno.planning.EarliestDueDatePlanner;
import com.vsm.okno.planning.MileageObligationGenerator;
import com.vsm.okno.planning.PlanCalendarProjector;
import com.vsm.okno.planning.Planner;
import com.vsm.okno.planning.PlannerRequest;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.ScenarioSnapshot;
import com.vsm.okno.store.Store;
import com.vsm.okno.validation.IndependentIntervalAudit;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class PlanningService {

    // A client-supplied limit would let any logged-in user pin the solver thread.
    private static final int MAX_TIME_LIMIT_SEC = 300;

    // Placeholder until D2's validator exists. A WARNING, not CRITICAL: the plan may be
    // approved "with a caveat" (validationStatus stays NOT_PERFORMED on the approved plan).
    // Once a PlanValidator bean exists, approval requires PASS again.
    private static final PlanValidator NOT_PERFORMED = (snapshot, result) -> List.of(new Dto.Validation(
            "VALIDATION_NOT_PERFORMED", "WARNING",
            "Независимая проверка D2 не выполнена — план согласуется с оговоркой"));

    private final Store store = new Store();
    private final PlanValidator validator;
    private final DemoSourceService demoSource;
    private final SourceSnapshotRepository sourceSnapshots;
    private final CaseDatasetService caseDatasets;

    private final Planner cpSat = new CpSatPlanner();
    private final Map<PlannerRequest.Policy, Planner> planners = Map.of(
            PlannerRequest.Policy.BLOCKS_CP_SAT, cpSat,
            PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT, cpSat,
            PlannerRequest.Policy.WHOLE_CYCLE_EDD, new EarliestDueDatePlanner());

    // ponytail: one in-process worker thread, jobs run strictly one at a time and
    // are lost on restart. Move to a PostgreSQL-backed queue (FOR UPDATE SKIP LOCKED,
    // lease/heartbeat, per the ТЗ) once D1's DB carries jobs.
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "planner-worker");
        t.setDaemon(true);
        return t;
    });

    public PlanningService(ObjectProvider<PlanValidator> validators,
                           ObjectProvider<DemoSourceService> demoSources,
                           ObjectProvider<SourceSnapshotRepository> sourceRepositories,
                           ObjectProvider<CaseDatasetService> caseSources) {
        this.validator = validators.getIfAvailable(() -> NOT_PERFORMED);
        this.demoSource = demoSources.getIfAvailable();
        this.sourceSnapshots = sourceRepositories.getIfAvailable();
        this.caseDatasets = caseSources.getIfAvailable();
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }

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

    public Dto.DemoSource importDemoSource() {
        if (demoSource == null) throw new InvalidRequestException("database", "profile is required for the saved demo source");
        var captured = demoSource.create();
        Store.Scenario scenario = new Store.Scenario();
        scenario.id = captured.scenarioId();
        scenario.createdAt = Instant.now();
        scenario.provenance = "Демонстрационные данные";
        scenario.trains = captured.trains();
        scenario.sourceSnapshotId = captured.snapshotId();
        store.scenarios.put(scenario.id, scenario);
        return demoResponse(captured);
    }

    public Dto.DemoSource changeDemoR1Arrival(UUID scenarioId, int arrivalMinute) {
        if (demoSource == null) throw new InvalidRequestException("database", "profile is required");
        if (arrivalMinute < 50 || arrivalMinute > 60) {
            throw new InvalidRequestException("arrivalMinute", "must be between 50 and 60");
        }
        Store.Scenario scenario = require(store.scenarios, scenarioId, "scenario");
        if (!"Демонстрационные данные".equals(scenario.provenance)) {
            throw new InvalidRequestException("scenario", "R1 editor is only supported for the short demo");
        }
        if (scenario.sourceSnapshotId == null) throw new InvalidRequestException("scenario", "is not a saved demo source");
        var captured = demoSource.changeR1Arrival(scenarioId, arrivalMinute);
        scenario.sourceSnapshotId = captured.snapshotId();
        return demoResponse(captured);
    }

    private static Dto.DemoSource demoResponse(DemoSourceService.Captured captured) {
        return new Dto.DemoSource(captured.scenarioId(), captured.snapshotId(),
                captured.snapshotHash(), "Демонстрационные данные");
    }

    public Dto.CaseDataset importCaseDataset(String key) {
        if (caseDatasets == null) throw new InvalidRequestException("database", "profile is required");
        CaseDatasetService.Dataset dataset;
        try {
            dataset = CaseDatasetService.Dataset.valueOf(key);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new InvalidRequestException("dataset", "must be FULL43, E2_6 or BLOCKED6");
        }
        var loaded = caseDatasets.load(dataset);
        var response = loaded.response();
        Store.Scenario scenario = new Store.Scenario();
        scenario.id = response.source().scenarioId();
        scenario.createdAt = Instant.now();
        scenario.provenance = response.source().provenance();
        scenario.trains = loaded.trains();
        scenario.sourceSnapshotId = response.source().snapshotId();
        if (!response.planningSupported()) scenario.planningUnsupportedReason =
                "FULL43 содержит резерв, уборку и закреплённые работы; необходим адаптер E3. Используйте E2_6 для проверки расчёта.";
        store.scenarios.put(scenario.id, scenario);
        return response;
    }

    public Dto.Scenario getScenario(UUID id) {
        Store.Scenario s = require(store.scenarios, id, "scenario");
        return new Dto.Scenario(s.id, s.createdAt, s.provenance, s.trains.size());
    }

    public List<Dto.Train> getTrains(UUID scenarioId) {
        return require(store.scenarios, scenarioId, "scenario").trains;
    }

    /** Queues a planning job and returns immediately; the solver runs on the worker thread. */
    public Dto.JobStatus createJob(Dto.JobRequest req) {
        if (req.scenarioId() == null) throw new InvalidRequestException("scenarioId", "is required");
        Store.Scenario scenario = require(store.scenarios, req.scenarioId(), "scenario");
        if (scenario.planningUnsupportedReason != null) {
            throw new InvalidRequestException("scenario", scenario.planningUnsupportedReason);
        }
        PlannerRequest.Policy policy = parsePolicy(req.policy());
        if (req.timeLimitSec() <= 0 || req.timeLimitSec() > MAX_TIME_LIMIT_SEC) {
            throw new InvalidRequestException("timeLimitSec", "must be between 1 and " + MAX_TIME_LIMIT_SEC);
        }
        if (req.seed() < Integer.MIN_VALUE || req.seed() > Integer.MAX_VALUE) {
            throw new InvalidRequestException("seed", "must fit in a 32-bit integer");
        }
        int frozenMinute = frozenMinute(req.frozenUntil());

        UUID jobId;
        if (req.idempotencyKey() == null) {
            jobId = submit(scenario, policy, (int) req.seed(), req.timeLimitSec(), frozenMinute);
        } else {
            // Same key must mean the same request. A different one is a client bug
            // (key reuse), never silently answered with someone else's old result.
            String content = String.join("|", req.scenarioId().toString(), policy.name(),
                    Long.toString(req.seed()), Integer.toString(req.timeLimitSec()), Integer.toString(frozenMinute));
            String existing = store.jobIdempotencyContent.putIfAbsent(req.idempotencyKey(), content);
            if (existing != null && !existing.equals(content)) {
                throw new IdempotencyConflictException(req.idempotencyKey());
            }
            jobId = store.jobIdempotency.computeIfAbsent(req.idempotencyKey(),
                    k -> submit(scenario, policy, (int) req.seed(), req.timeLimitSec(), frozenMinute));
        }
        return toJobStatus(store.jobs.get(jobId));
    }

    private UUID submit(Store.Scenario scenario, PlannerRequest.Policy policy, int seed, int timeLimitSec, int frozenMinute) {
        Store.PlanningJob job = new Store.PlanningJob();
        job.id = UUID.randomUUID();
        job.scenarioId = scenario.id;
        job.status = "QUEUED";
        store.jobs.put(job.id, job);
        UUID snapshotId = scenario.sourceSnapshotId;
        worker.execute(() -> run(job, scenario, snapshotId, policy, seed, timeLimitSec, frozenMinute));
        return job.id;
    }

    // Job status is the lifecycle; solverStatus is what the solver concluded. A
    // SUCCEEDED job can carry INFEASIBLE, and either way the plan still needs validation.
    private void run(Store.PlanningJob job, Store.Scenario scenario, UUID snapshotId, PlannerRequest.Policy policy,
                     int seed, int timeLimitSec, int frozenMinute) {
        job.status = "RUNNING";
        try {
            MileageObligationGenerator.Projection projection = snapshotId == null ? null
                    : new SourceSnapshotE2Adapter().project(sourceSnapshots.findById(snapshotId)
                    .orElseThrow(() -> new NotFoundException("snapshot not found: " + snapshotId)));
            ScenarioSnapshot snapshot = projection == null
                    ? SyntheticSnapshot.of(scenario.id, scenario.trains, scenario.failures)
                    : projection.snapshot();
            PlannerRequest request = new PlannerRequest(frozenMinute > 0 ? "1.1" : "1.0", scenario.id,
                    snapshot.snapshotHash(), policy, seed, timeLimitSec, frozenMinute);
            PlannerResult result = planners.get(policy).plan(snapshot, request);

            Store.Plan plan = toPlan(scenario.id, snapshot, result, projection);
            store.plans.put(plan.id, plan);
            job.planId = plan.id;
            job.solverStatus = result.solverStatus().name();
            job.status = "SUCCEEDED";
        } catch (Exception | LinkageError e) { // LinkageError: OR-Tools natives failed to load
            job.error = e.getClass().getSimpleName() + ": " + e.getMessage();
            job.status = "FAILED";
        }
    }

    private Store.Plan toPlan(UUID scenarioId, ScenarioSnapshot snapshot, PlannerResult result,
                              MileageObligationGenerator.Projection projection) {
        Store.Plan plan = new Store.Plan();
        plan.id = UUID.randomUUID();
        plan.scenarioId = scenarioId;
        plan.version = 0;
        plan.status = "DRAFT";
        plan.snapshotHash = snapshot.snapshotHash();
        plan.solverStatus = result.solverStatus().name();
        plan.events = result.blocks().stream()
                .map(b -> new Dto.PlanEvent(b.blockId(), b.trainId(), "SERVICE_BLOCK",
                        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(b.startAt()),
                        DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(b.endAt()),
                        List.of(b.resourceId())))
                .toList();

        // No admissible plan (INFEASIBLE/UNKNOWN/...) is itself a blocking finding.
        List<Dto.Validation> validations = new ArrayList<>();
        result.diagnostics().forEach(d -> validations.add(new Dto.Validation(d.code(), "CRITICAL", d.message())));
        List<Dto.Validation> intervalFindings = IndependentIntervalAudit.check(snapshot, result);
        validations.addAll(intervalFindings);
        List<Dto.Validation> d2Findings = validator.validate(snapshot, result);
        validations.addAll(d2Findings);
        plan.validationStatus = validator == NOT_PERFORMED ? "NOT_PERFORMED"
                : validations.stream().anyMatch(v -> "CRITICAL".equals(v.severity())) ? "FAILED" : "PASS";
        plan.validations = List.copyOf(validations);
        Map<UUID, MileageObligationGenerator.Obligation> obligations = projection == null ? Map.of()
                : projection.obligations().stream().collect(Collectors.toMap(
                MileageObligationGenerator.Obligation::blockId, Function.identity()));
        plan.calendar = PlanCalendarProjector.project(snapshot, result, obligations, plan.validationStatus);
        return plan;
    }

    public Dto.JobStatus getJob(UUID id) {
        return toJobStatus(require(store.jobs, id, "job"));
    }

    public Dto.Plan getPlan(UUID id) {
        return toPlanDto(require(store.plans, id, "plan"));
    }

    public Dto.PlanCalendar getCalendar(UUID id) {
        return require(store.plans, id, "plan").calendar;
    }

    public Dto.Plan approve(UUID planId, Dto.ApproveRequest req, String actor) {
        Store.Plan plan = require(store.plans, planId, "plan");
        // Serialized so two concurrent approvals can't both pass the version check.
        synchronized (plan) {
            if (plan.version != req.expectedVersion()) {
                throw new VersionConflictException(plan.version);
            }
            if ("FAILED".equals(plan.validationStatus)
                    || (!"OPTIMAL".equals(plan.solverStatus) && !"FEASIBLE".equals(plan.solverStatus))) {
                throw new NotApprovableException("D2 validation failed or solver result is not feasible");
            }
            List<String> critical = plan.validations.stream()
                    .filter(v -> "CRITICAL".equals(v.severity())).map(Dto.Validation::code).toList();
            if (!critical.isEmpty()) {
                throw new NotApprovableException("plan has critical violations: " + String.join(", ", critical));
            }
            plan.approvedBy = actor;
            plan.status = "APPROVED";
            plan.version += 1;
            return toPlanDto(plan);
        }
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
        if (base.sourceSnapshotId != null) {
            throw new InvalidRequestException("scenario", "saved demo source does not support injected failures");
        }
        if (event.kind() == null || !SyntheticSnapshot.FAILURE_KINDS.contains(event.kind())) {
            throw new InvalidRequestException("kind", "must be one of " + SyntheticSnapshot.FAILURE_KINDS);
        }
        Store.Scenario copy = new Store.Scenario();
        copy.id = UUID.randomUUID();
        copy.createdAt = Instant.now();
        copy.provenance = base.provenance + "+" + event.kind();
        copy.trains = base.trains;
        copy.failures = java.util.stream.Stream.concat(base.failures.stream(), java.util.stream.Stream.of(event.kind())).toList();
        store.scenarios.put(copy.id, copy);
        return copy.id;
    }

    private static PlannerRequest.Policy parsePolicy(String policy) {
        if (policy == null) throw new InvalidRequestException("policy", "is required");
        try {
            return PlannerRequest.Policy.valueOf(policy);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("policy", "must be one of " + List.of(PlannerRequest.Policy.values()));
        }
    }

    // frozenUntil is an offset timestamp in the API but a whole-minute offset from the horizon start in the solver.
    private static int frozenMinute(Instant frozenUntil) {
        if (frozenUntil == null) return 0;
        long seconds = Duration.between(SyntheticSnapshot.HORIZON_START.toInstant(), frozenUntil).getSeconds();
        if (frozenUntil.getNano() != 0 || seconds % 60 != 0) {
            throw new InvalidRequestException("frozenUntil", "must fall on a whole minute");
        }
        if (seconds / 60 > SyntheticSnapshot.HORIZON_MINUTES) {
            throw new InvalidRequestException("frozenUntil", "is after the planning horizon");
        }
        return (int) Math.max(0, seconds / 60);
    }

    private Dto.JobStatus toJobStatus(Store.PlanningJob job) {
        // Read the volatile fields once each; status was written last by the worker.
        return new Dto.JobStatus(job.id, job.status, job.solverStatus, job.planId, job.error);
    }

    private Dto.Plan toPlanDto(Store.Plan plan) {
        return new Dto.Plan(plan.id, plan.scenarioId, plan.version, plan.status, plan.events,
                plan.validations, plan.approvedBy, plan.snapshotHash, plan.validationStatus);
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

    public static class InvalidRequestException extends RuntimeException {
        public final String field;

        public InvalidRequestException(String field, String reason) {
            super(field + " " + reason);
            this.field = field;
        }
    }

    public static class NotApprovableException extends RuntimeException {
        public NotApprovableException(String message) {
            super(message);
        }
    }

    public static class IdempotencyConflictException extends RuntimeException {
        public IdempotencyConflictException(String key) {
            super("idempotencyKey " + key + " was already used with a different request");
        }
    }
}
