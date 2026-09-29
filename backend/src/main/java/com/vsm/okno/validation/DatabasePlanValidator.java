package com.vsm.okno.validation;

import com.vsm.okno.data.SourceSnapshotRepository;
import com.vsm.okno.dto.Dto;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.ScenarioSnapshot;
import com.vsm.okno.service.PlanValidator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;

/** Production wiring for supported source scopes. No source never means PASS. */
@Component
@Profile("database")
public final class DatabasePlanValidator implements PlanValidator {
    private final SourceSnapshotRepository repository;
    private final SourcePlanAudit audit = new SourcePlanAudit();

    public DatabasePlanValidator(SourceSnapshotRepository repository) { this.repository = repository; }

    @Override public List<Dto.Validation> validate(ScenarioSnapshot source, PlannerResult result) {
        return report(source, result).findings();
    }

    @Override public ValidationReport report(ScenarioSnapshot source, PlannerResult result) {
        return audit.report(repository.findByScenarioAndHash(source.scenarioId(), source.snapshotHash()).orElse(null), source, result);
    }
}
