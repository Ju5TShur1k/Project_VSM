package com.vsm.okno.planning;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class E3FeasibilityAuditTest {
    private static final UUID TRAIN = UUID.nameUUIDFromBytes("line-train".getBytes());
    private static final UUID WORK = UUID.nameUUIDFromBytes("service".getBytes());
    private static final Set<UUID> RESERVE = Set.of(id("reserve-1"), id("reserve-2"),
            id("reserve-3"), id("reserve-4"));
    private static final OffsetDateTime START = OffsetDateTime.parse("2031-07-01T00:00:00+03:00");

    @Test
    void sourceOccupancyCanEliminateAnOtherwiseLongEnoughServiceWindow() {
        var blocked = source(List.of(new OperationalConstraints.FixedOccupancy(id("occupancy"), TRAIN, null,
                40, 90, OperationalConstraints.Kind.OTHER_COMMITMENT, "confirmed depot work")));
        var findings = E3FeasibilityAudit.noContiguousWindows(blocked, List.of());
        assertEquals(1, findings.size());
        assertEquals("E3_NO_CONTIGUOUS_SERVICE_WINDOW", findings.getFirst().code());
        assertTrue(findings.getFirst().message().contains("CASE-01"));
        assertTrue(E3FeasibilityAudit.noContiguousWindows(source(List.of()), List.of()).isEmpty());
    }

    private static ScenarioSnapshot source(List<OperationalConstraints.FixedOccupancy> occupied) {
        var trains = new java.util.ArrayList<ScenarioSnapshot.Train>();
        trains.add(new ScenarioSnapshot.Train(TRAIN, "CASE-01"));
        RESERVE.forEach(id -> trains.add(new ScenarioSnapshot.Train(id, "RESERVE-" + id)));
        var operations = new OperationalConstraints(RESERVE, occupied,
                List.of(new OperationalConstraints.ServiceWindow(TRAIN, "PATH-1", 0, 120, "confirmed presence")),
                List.of(), List.of(), new OperationalConstraints.HotReserve(RESERVE, "confirmed reserve"));
        return new ScenarioSnapshot("1.4", id("scenario"), "test-hash", "synthetic test", START,
                START.plusMinutes(120), trains, List.of(new ScenarioSnapshot.Resource("PATH-1")),
                List.of(new ScenarioSnapshot.ServiceBlock(WORK, TRAIN, "PATH-1", 60,
                        0, 120, List.of(), ScenarioSnapshot.ServiceBlock.Kind.MAINTENANCE)),
                List.of(), operations);
    }

    private static UUID id(String value) { return UUID.nameUUIDFromBytes(value.getBytes()); }
}
