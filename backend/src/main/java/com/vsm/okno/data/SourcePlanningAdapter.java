package com.vsm.okno.data;

import com.vsm.okno.planning.MileageObligationGenerator;

/** F2 extension point: preserve all facts of an immutable source, or reject the unsupported scope. */
@FunctionalInterface
public interface SourcePlanningAdapter {
    MileageObligationGenerator.Projection project(SourceSnapshotRepository.SourceSnapshot source);
}
