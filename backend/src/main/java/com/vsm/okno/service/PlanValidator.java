package com.vsm.okno.service;

import com.vsm.okno.dto.Dto;
import com.vsm.okno.planning.PlannerResult;
import com.vsm.okno.planning.ScenarioSnapshot;
import com.vsm.okno.validation.ValidationReport;

import java.util.List;

/**
 * Independent check of a finished plan (D2's PlanValidator). Any violation with
 * severity CRITICAL makes the plan un-approvable. Implement this as a Spring bean
 * and it replaces the built-in "not performed" placeholder automatically.
 */
public interface PlanValidator {
    List<Dto.Validation> validate(ScenarioSnapshot snapshot, PlannerResult result);

    default ValidationReport report(ScenarioSnapshot snapshot, PlannerResult result) {
        return ValidationReport.of(snapshot, result, null, "PREPARED_ONLY", null, null,
                snapshot.blocks().size(), List.of(), validate(snapshot, result));
    }
}
