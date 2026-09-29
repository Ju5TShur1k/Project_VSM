package com.vsm.okno.planning;

import com.vsm.okno.dto.Dto;
import com.vsm.okno.store.Store;
import com.vsm.okno.validation.PlanFingerprint;
import com.vsm.okno.validation.ValidationReport;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** A saved E3 model candidate is always a non-approvable draft until independent D2. */
public final class E3DraftPlanFactory {
    public Store.Plan create(E3JointFullPlanner.Result candidate) {
        if (candidate == null || !"MODEL_CANDIDATE_FOUND".equals(candidate.searchStatus())
                || candidate.plan() == null || candidate.plan().calendar() == null
                || !"PASS".equals(candidate.plan().structuralStatus())
                || !List.of("FEASIBLE", "OPTIMAL").contains(candidate.plan().modelSolverStatus())
                || !candidate.snapshotHash().equals(candidate.plan().snapshotHash())
                || !candidate.snapshotHash().equals(candidate.plan().calendar().snapshotHash())
                || candidate.plan().calendar().independentlyValidated())
            throw new IllegalArgumentException("only an audited E3 model candidate can become a draft");
        List<Dto.PlanEvent> events = candidate.plan().blocks().stream()
                .map(block -> new Dto.PlanEvent(block.blockId(), block.trainId(), "SERVICE_BLOCK",
                        block.startAt().toString(), block.endAt().toString(),
                        List.of(block.resourceId()))).toList();
        String fingerprint = PlanFingerprint.events(candidate.scenarioId(),
                candidate.snapshotHash(), events);
        String assignments = candidate.effectiveTrainByTrip().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining("\n"));
        String candidateHash = PlanFingerprint.sha256(fingerprint + assignments);
        var findings = new ArrayList<Dto.Validation>();
        findings.add(new Dto.Validation("VALIDATION_NOT_PERFORMED", "WARNING",
                "Полная независимая D2-проверка E3 ещё не выполнена; утверждение запрещено"));
        findings.add(new Dto.Validation("E3_CLEANING_CAPACITY_UNSPECIFIED", "WARNING",
                "Уборки занимают состав, но число уборочных бригад в D1 не задано"));
        if (candidate.plan().reserve().cities().stream().anyMatch(city -> city.deficit() > 0))
            findings.add(new Dto.Validation("E3_RESERVE_DEFICIT", "WARNING",
                    "Использование резерва создаёт видимый дефицит по городам"));
        var plan = new Store.Plan();
        plan.id = UUID.nameUUIDFromBytes(("e3-model-draft:" + candidateHash)
                .getBytes(StandardCharsets.UTF_8));
        plan.scenarioId = candidate.scenarioId();
        plan.version = 0;
        plan.status = "DRAFT";
        plan.events = events;
        plan.validations = List.copyOf(findings);
        plan.snapshotHash = candidate.snapshotHash();
        plan.solverStatus = candidate.plan().modelSolverStatus();
        plan.validationStatus = "NOT_PERFORMED";
        plan.validationReport = new ValidationReport("d2-validation-1.0", "NOT_PERFORMED",
                "E3_MODEL_CANDIDATE", plan.scenarioId, candidate.sourceSnapshotId(),
                plan.snapshotHash, candidateHash, PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT.name(),
                plan.solverStatus, 0, Instant.now(), "D1 source snapshot", "SYNTHETIC",
                candidate.plan().blocks().size(), List.of(), plan.validations);
        plan.calendar = candidate.plan().calendar();
        return plan;
    }
}
