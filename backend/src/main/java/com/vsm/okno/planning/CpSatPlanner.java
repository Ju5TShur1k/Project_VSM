package com.vsm.okno.planning;

import com.google.ortools.Loader;
import com.google.ortools.sat.CpModel;
import com.google.ortools.sat.CpSolver;
import com.google.ortools.sat.CpSolverStatus;
import com.google.ortools.sat.IntVar;
import com.google.ortools.sat.IntervalVar;
import com.google.ortools.sat.LinearExpr;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** E1/E2 CP-SAT model: indivisible work, fixed trips, exclusive resources, windows and precedences. */
public final class CpSatPlanner implements Planner {
    private record Variables(IntVar start, IntVar end, IntervalVar interval) {}

    @Override
    public PlannerResult plan(ScenarioSnapshot snapshot, PlannerRequest request) {
        if (!snapshot.scenarioId().equals(request.scenarioId())
                || !snapshot.snapshotHash().equals(request.snapshotHash())) {
            throw new IllegalArgumentException("request does not match snapshot id/hash");
        }
        if (request.policy() != PlannerRequest.Policy.BLOCKS_CP_SAT
                && request.policy() != PlannerRequest.Policy.WHOLE_CYCLE_CP_SAT) {
            throw new IllegalArgumentException("unsupported planner policy");
        }

        // Loading here makes a failed native installation visible in a worker/test, not as a fabricated plan.
        Loader.loadNativeLibraries();
        long startedAt = System.nanoTime();
        CpModel model = new CpModel();
        Map<UUID, Variables> byBlock = new LinkedHashMap<>();
        Map<UUID, List<IntervalVar>> byTrain = new HashMap<>();
        Map<String, List<IntervalVar>> byResource = new HashMap<>();
        List<IntVar> ends = new ArrayList<>();

        for (ScenarioSnapshot.ServiceBlock block : snapshot.blocks()) {
            String suffix = block.id().toString();
            IntVar start = model.newIntVar(block.earliestStartMinute(),
                    block.latestEndMinute() - block.durationMinutes(), "start_" + suffix);
            IntVar end = model.newIntVar(block.earliestStartMinute() + block.durationMinutes(),
                    block.latestEndMinute(), "end_" + suffix);
            IntervalVar interval = model.newIntervalVar(start, LinearExpr.constant(block.durationMinutes()),
                    end, "block_" + suffix);
            byBlock.put(block.id(), new Variables(start, end, interval));
            byTrain.computeIfAbsent(block.trainId(), ignored -> new ArrayList<>()).add(interval);
            byResource.computeIfAbsent(block.resourceId(), ignored -> new ArrayList<>()).add(interval);
            ends.add(end);
        }

        for (ScenarioSnapshot.FixedTrip trip : snapshot.fixedTrips()) {
            IntervalVar occupied = model.newIntervalVar(model.newConstant(trip.startMinute()),
                    LinearExpr.constant(trip.endMinute() - trip.startMinute()),
                    model.newConstant(trip.endMinute()), "trip_" + trip.id());
            byTrain.computeIfAbsent(trip.trainId(), ignored -> new ArrayList<>()).add(occupied);
        }

        byTrain.values().forEach(model::addNoOverlap);
        byResource.values().forEach(model::addNoOverlap);
        for (ScenarioSnapshot.ServiceBlock block : snapshot.blocks()) {
            for (UUID predecessorId : block.predecessorIds()) {
                model.addGreaterOrEqual(byBlock.get(block.id()).start(), byBlock.get(predecessorId).end());
            }
        }

        // A transparent toy objective: the earliest completion of all required work.
        IntVar makespan = model.newIntVar(0, snapshot.horizonMinutes(), "makespan");
        model.addMaxEquality(makespan, ends);
        model.minimize(makespan);

        CpSolver solver = new CpSolver();
        solver.getParameters().setMaxTimeInSeconds(request.timeLimitSec());
        solver.getParameters().setRandomSeed(request.seed());
        solver.getParameters().setNumSearchWorkers(1);
        CpSolverStatus cpStatus = solver.solve(model);
        PlannerResult.SolverStatus status = PlannerResult.SolverStatus.valueOf(cpStatus.name());

        List<PlannerResult.PlannedBlock> planned = new ArrayList<>();
        List<PlannerResult.Diagnostic> diagnostics = new ArrayList<>();
        Double objectiveMinutes = null;
        if (status == PlannerResult.SolverStatus.OPTIMAL || status == PlannerResult.SolverStatus.FEASIBLE) {
            for (ScenarioSnapshot.ServiceBlock block : snapshot.blocks()) {
                Variables variables = byBlock.get(block.id());
                planned.add(new PlannerResult.PlannedBlock(block.id(), block.trainId(), block.resourceId(),
                        snapshot.horizonStart().plusMinutes(solver.value(variables.start())),
                        snapshot.horizonStart().plusMinutes(solver.value(variables.end()))));
            }
            planned.sort(Comparator.comparing(PlannerResult.PlannedBlock::startAt)
                    .thenComparing(PlannerResult.PlannedBlock::blockId));
            objectiveMinutes = solver.objectiveValue();
        } else {
            String message = switch (status) {
                case INFEASIBLE -> "The E1 model proved that no placement satisfies all stated windows and exclusive resources.";
                case UNKNOWN -> "No feasible result was established before the solver stopped.";
                case MODEL_INVALID -> "CP-SAT rejected the E1 model; inspect model construction and solver logs.";
                default -> throw new IllegalStateException("unexpected solver status");
            };
            diagnostics.add(new PlannerResult.Diagnostic("SOLVER_" + status, message));
        }
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
        return new PlannerResult("1.0", snapshot.scenarioId(), snapshot.snapshotHash(), request.policy(),
                status, planned, diagnostics, request.seed(), elapsedMillis, objectiveMinutes);
    }
}
