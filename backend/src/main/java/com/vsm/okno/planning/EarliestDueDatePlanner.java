package com.vsm.okno.planning;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** B0: honest whole-cycle EDD; earliest available minute, no deliberate delays or backtracking. */
public final class EarliestDueDatePlanner implements Planner {
    private record Placement(ScenarioSnapshot.ServiceBlock block, int start, int end) {}
    private record Occupied(int start, int end) {}

    @Override
    public PlannerResult plan(ScenarioSnapshot snapshot, PlannerRequest request) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(request, "request");
        request.validateAgainst(snapshot);
        if (request.policy() != PlannerRequest.Policy.WHOLE_CYCLE_EDD) {
            throw new IllegalArgumentException("unsupported planner policy");
        }
        long startedAt = System.nanoTime();
        long deadline = startedAt + request.timeLimitSec() * 1_000_000_000L;
        if (!fixedCommitmentsConsistent(snapshot)) {
            return failed(snapshot, request, startedAt, "EDD_FIXED_CONFLICT",
                    "Fixed trips and operational occupancies overlap; no complete baseline was established.");
        }
        Map<UUID, Placement> placed = new HashMap<>();
        List<ScenarioSnapshot.ServiceBlock> pending = new ArrayList<>(snapshot.blocks());
        Map<UUID, ScenarioSnapshot.ServiceBlock> blockById = new HashMap<>();
        for (ScenarioSnapshot.ServiceBlock block : snapshot.blocks()) blockById.put(block.id(), block);
        for (OperationalConstraints.FrozenPlacement frozen : snapshot.operations().frozenPlacements()) {
            ScenarioSnapshot.ServiceBlock block = blockById.get(frozen.blockId());
            int end = frozen.startMinute() + block.durationMinutes();
            if (!available(snapshot, placed.values(), block, frozen.startMinute(), end)) {
                return failed(snapshot, request, startedAt, "EDD_FROZEN_CONFLICT",
                        "A frozen placement conflicts with fixed commitments or a checked service window.");
            }
            placed.put(block.id(), new Placement(block, frozen.startMinute(), end));
            pending.remove(block);
        }
        Comparator<ScenarioSnapshot.ServiceBlock> dueOrder = Comparator
                .comparingInt(ScenarioSnapshot.ServiceBlock::latestEndMinute)
                .thenComparingInt(ScenarioSnapshot.ServiceBlock::earliestStartMinute)
                .thenComparing(ScenarioSnapshot.ServiceBlock::id);

        while (!pending.isEmpty()) {
            if (System.nanoTime() >= deadline) {
                return failed(snapshot, request, startedAt, "EDD_TIMEOUT",
                        "Baseline stopped at its time limit; no complete plan was established.");
            }
            ScenarioSnapshot.ServiceBlock next = pending.stream()
                    .filter(block -> placed.keySet().containsAll(block.predecessorIds()))
                    .min(dueOrder).orElse(null);
            if (next == null) {
                return failed(snapshot, request, startedAt, "EDD_PRECEDENCE_CYCLE",
                        "Predecessor graph has no ready block; inspect source obligations.");
            }
            int earliest = Math.max(next.earliestStartMinute(), request.frozenUntilMinute());
            for (UUID predecessor : next.predecessorIds()) {
                earliest = Math.max(earliest, placed.get(predecessor).end());
            }
            Placement chosen = null;
            for (long minute = earliest; minute + next.durationMinutes() <= next.latestEndMinute(); minute++) {
                if (System.nanoTime() >= deadline) {
                    return failed(snapshot, request, startedAt, "EDD_TIMEOUT",
                            "Baseline stopped at its time limit; no complete plan was established.");
                }
                int start = (int) minute;
                int end = start + next.durationMinutes();
                if (available(snapshot, placed.values(), next, start, end)) {
                    chosen = new Placement(next, start, end);
                    break;
                }
            }
            if (chosen == null) {
                return failed(snapshot, request, startedAt, "EDD_NO_SLOT",
                        "Greedy EDD found no slot for block " + next.id()
                                + "; this does not prove model infeasibility.");
            }
            placed.put(next.id(), chosen);
            pending.remove(next);
        }

        for (Placement placement : placed.values()) {
            for (UUID predecessorId : placement.block().predecessorIds()) {
                if (placement.start() < placed.get(predecessorId).end()) {
                    return failed(snapshot, request, startedAt, "EDD_PRECEDENCE_CONFLICT",
                            "A frozen placement and its predecessor cannot keep their required order.");
                }
            }
        }

        List<PlannerResult.PlannedBlock> blocks = placed.values().stream()
                .sorted(Comparator.comparingInt(Placement::start).thenComparing(p -> p.block().id()))
                .map(p -> new PlannerResult.PlannedBlock(p.block().id(), p.block().trainId(),
                        p.block().resourceId(), snapshot.horizonStart().plusMinutes(p.start()),
                        snapshot.horizonStart().plusMinutes(p.end())))
                .toList();
        double makespan = placed.values().stream().mapToInt(Placement::end).max().orElse(0);
        return new PlannerResult("1.0", snapshot.scenarioId(), snapshot.snapshotHash(), request.policy(),
                PlannerResult.SolverStatus.FEASIBLE, blocks, List.of(), request.seed(),
                elapsed(startedAt), makespan);
    }

    private static boolean available(ScenarioSnapshot snapshot, Iterable<Placement> placed,
                                     ScenarioSnapshot.ServiceBlock block, int start, int end) {
        if ("1.2".equals(snapshot.schemaVersion()) && snapshot.operations().serviceWindows().stream()
                .noneMatch(window -> window.trainId().equals(block.trainId())
                        && window.resourceId().equals(block.resourceId())
                        && window.startMinute() <= start && end <= window.endMinute())) {
            return false;
        }
        for (ScenarioSnapshot.FixedTrip trip : snapshot.fixedTrips()) {
            if (block.trainId().equals(trip.trainId()) && overlaps(start, end, trip.startMinute(), trip.endMinute())) {
                return false;
            }
        }
        for (OperationalConstraints.FixedOccupancy occupancy : snapshot.operations().fixedOccupancies()) {
            if ((block.trainId().equals(occupancy.trainId())
                    || block.resourceId().equals(occupancy.resourceId()))
                    && overlaps(start, end, occupancy.startMinute(), occupancy.endMinute())) {
                return false;
            }
        }
        for (Placement other : placed) {
            if ((block.trainId().equals(other.block().trainId())
                    || block.resourceId().equals(other.block().resourceId()))
                    && overlaps(start, end, other.start(), other.end())) {
                return false;
            }
        }
        return true;
    }

    private static boolean overlaps(int aStart, int aEnd, int bStart, int bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }

    private static boolean fixedCommitmentsConsistent(ScenarioSnapshot snapshot) {
        Map<UUID, List<Occupied>> byTrain = new HashMap<>();
        Map<String, List<Occupied>> byResource = new HashMap<>();
        for (ScenarioSnapshot.FixedTrip trip : snapshot.fixedTrips()) {
            byTrain.computeIfAbsent(trip.trainId(), ignored -> new ArrayList<>())
                    .add(new Occupied(trip.startMinute(), trip.endMinute()));
        }
        for (OperationalConstraints.FixedOccupancy occupancy : snapshot.operations().fixedOccupancies()) {
            Occupied interval = new Occupied(occupancy.startMinute(), occupancy.endMinute());
            if (occupancy.trainId() != null) {
                byTrain.computeIfAbsent(occupancy.trainId(), ignored -> new ArrayList<>()).add(interval);
            }
            if (occupancy.resourceId() != null) {
                byResource.computeIfAbsent(occupancy.resourceId(), ignored -> new ArrayList<>()).add(interval);
            }
        }
        return byTrain.values().stream().allMatch(EarliestDueDatePlanner::noOverlap)
                && byResource.values().stream().allMatch(EarliestDueDatePlanner::noOverlap);
    }

    private static boolean noOverlap(List<Occupied> intervals) {
        intervals.sort(Comparator.comparingInt(Occupied::start));
        for (int index = 1; index < intervals.size(); index++) {
            if (intervals.get(index).start() < intervals.get(index - 1).end()) return false;
        }
        return true;
    }

    private static PlannerResult failed(ScenarioSnapshot snapshot, PlannerRequest request,
                                        long startedAt, String code, String message) {
        return new PlannerResult("1.0", snapshot.scenarioId(), snapshot.snapshotHash(), request.policy(),
                PlannerResult.SolverStatus.UNKNOWN, List.of(),
                List.of(new PlannerResult.Diagnostic(code, message)), request.seed(), elapsed(startedAt), null);
    }

    private static long elapsed(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }
}
