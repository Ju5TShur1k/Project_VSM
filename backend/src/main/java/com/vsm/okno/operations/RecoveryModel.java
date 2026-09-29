package com.vsm.okno.operations;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Advisory model only: a day of response to an explicitly simulated failure. */
public final class RecoveryModel {
    private RecoveryModel() {}
    public record Policy(String version, int preparationMinutes, int targetReservePerCity,
                         int cleaningEveryTrips, String source) {}
    public record Fault(int sequence, UUID id, UUID trainId, UUID tripId, OffsetDateTime occurredAt,
                        OffsetDateTime expectedRepairAt, String description, List<UUID> affectedTripIds) {}
    public record Assignment(int sequence, UUID faultId, UUID trainId, OffsetDateTime decidedAt, List<UUID> tripIds, String actor) {}
    public record Release(int sequence, UUID faultId, OffsetDateTime repairCompletedAt, OffsetDateTime acceptedAt,
                          String location, String acceptanceReference, String actor) {}
    public record State(String schemaVersion, UUID snapshotId, String snapshotHash, Policy policy,
                        OffsetDateTime asOf, List<Fault> faults, List<Assignment> assignments, List<Release> releases) {}

    public record FailureRequest(Integer expectedVersion, UUID tripId, OffsetDateTime occurredAt,
                                 OffsetDateTime expectedRepairAt, String description) {}
    public record ReplacementRequest(Integer expectedVersion, UUID faultId, UUID trainId) {}
    public record ReleaseRequest(Integer expectedVersion, UUID faultId, OffsetDateTime repairCompletedAt,
                                 OffsetDateTime acceptedAt, String location, String acceptanceReference) {}
    public record ClockRequest(Integer expectedVersion, OffsetDateTime asOf) {}

    public record TrainView(UUID id, String externalId, String status, String location, long mileageKm,
                            Integer tripsSinceCleaning, String readinessEvidence) {}
    public record TripView(UUID id, String label, String pairKey, UUID plannedTrainId, String plannedTrain,
                           UUID effectiveTrainId, String effectiveTrain, String origin, String destination,
                           OffsetDateTime departureAt, OffsetDateTime arrivalAt, String coverage) {}
    public record Candidate(UUID trainId, String externalId, String location, boolean eligible, List<String> reasons) {}
    public record FaultView(UUID id, UUID trainId, String train, String trip, OffsetDateTime occurredAt,
                            OffsetDateTime expectedRepairAt, String description, String status,
                            List<UUID> affectedTripIds, String replacementTrain, List<Candidate> candidates,
                            OffsetDateTime acceptedAt) {}
    public record ReserveView(String location, int available, int target, int deficit) {}
    public record Board(UUID id, int version, String stateHash, UUID scenarioId, UUID sourceSnapshotId, String snapshotHash,
                        String scope, String provenance, OffsetDateTime windowStart, OffsetDateTime windowEnd,
                        OffsetDateTime asOf, Policy policy, int trainCount, int availableTrainCount,
                        int unavailableTrainCount, int uncoveredTripCount, int incompletePairCount,
                        List<ReserveView> reserve, List<TrainView> trains, List<TripView> trips,
                        List<FaultView> faults, List<String> messages) {}
}
