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
    }

    public static class PlanningJob {
        public UUID id;
        public UUID scenarioId;
        public String status;
        public String solverStatus;
        public UUID planId;
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
