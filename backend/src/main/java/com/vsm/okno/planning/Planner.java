package com.vsm.okno.planning;

public interface Planner {
    PlannerResult plan(ScenarioSnapshot snapshot, PlannerRequest request);
}
