package com.vsm.okno.store;

import com.vsm.okno.dto.Dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

// ponytail: single-process in-memory store standing in for Postgres until D1's
// migrations land (see 02_Техническое_задание.md §7). Swap Store's guts for a
// repository layer then; the DTO/service boundary above it doesn't need to change.
public class Store {
    public final Map<UUID, Scenario> scenarios = new ConcurrentHashMap<>();
    public final Map<UUID, PlanningJob> jobs = new ConcurrentHashMap<>();
    public final Map<UUID, Plan> plans = new ConcurrentHashMap<>();
    public final Map<String, UUID> jobIdempotency = new ConcurrentHashMap<>();

    public static class Scenario {
        public UUID id;
        public Instant createdAt;
        public String provenance;
        public List<Dto.Train> trains;
        // Injected failure kinds in order; the planning snapshot is derived from these.
        public List<String> failures = List.of();
    }

    // Written by the planner worker thread, read by HTTP threads: volatile, and
    // status must be assigned last so readers that see SUCCEEDED also see the rest.
    public static class PlanningJob {
        public UUID id;
        public UUID scenarioId;
        public volatile String status;
        public volatile String solverStatus;
        public volatile UUID planId;
        public volatile String error;
    }

    public static class Plan {
        public UUID id;
        public UUID scenarioId;
        public int version;
        public String status;
        public String approvedBy;
        public List<Dto.PlanEvent> events;
        public List<Dto.Validation> validations;
    }
}
