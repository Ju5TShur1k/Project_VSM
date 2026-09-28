package com.vsm.okno.data;

import com.vsm.okno.dto.Dto;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Synthetic E2 source facts for the defense path. Snapshots remain immutable D1 records. */
@Service
@Profile("database")
public class DemoSourceService {
    private static final OffsetDateTime START = OffsetDateTime.parse("2028-07-01T00:00:00+03:00");
    private final JdbcTemplate jdbc;
    private final SourceSnapshotRepository snapshots;

    public record Captured(UUID scenarioId, UUID snapshotId, String snapshotHash,
                           List<Dto.Train> trains) {}

    public DemoSourceService(JdbcTemplate jdbc, SourceSnapshotRepository snapshots) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
    }

    @Transactional
    public Captured create() {
        UUID scenario = UUID.randomUUID();
        UUID rules = UUID.randomUUID();
        UUID train = UUID.randomUUID();
        jdbc.update("insert into vsm.rule_set(id,version,source,confirmation_status,mileage_policy,tolerance_basis) "
                        + "values (?,?,?,'SYNTHETIC','ABSOLUTE_GRID','NOMINAL_MILESTONE')",
                rules, "demo-e2-" + scenario, "Демонстрационные данные");
        jdbc.update("insert into vsm.cycle_rule values (?,?,?,?,?,?,?)",
                rules, "IS100", 12500, 1000, 20, 1, "Демонстрационные данные: длительность IS100");
        jdbc.update("insert into vsm.cycle_rule values (?,?,?,?,?,?,?)",
                rules, "IS200", 25000, 2000, 30, 2, "Демонстрационные данные: длительность IS200");
        jdbc.update("insert into vsm.scenario(id,name,rule_set_id,horizon_start,horizon_end,provenance) "
                        + "values (?,?,?,?,?,?)", scenario, "Сквозной демо-сценарий F2", rules,
                START, START.plusHours(4), "Демонстрационные данные");
        jdbc.update("insert into vsm.train values (?,?,?,?,?,?)", scenario, train,
                "EVS-SYN-1", "AVAILABLE", "TEST_DEPOT", "Демонстрационные данные");
        jdbc.update("insert into vsm.odometer_reading values (?,?,?,?,?)", scenario, train,
                START, 24000, "Демонстрационные данные");
        jdbc.update("insert into vsm.resource values (?,?,?,?,?)", scenario, "PATH",
                "Демонстрационный путь", "TEST_DEPOT", "Демонстрационные данные");
        jdbc.update("insert into vsm.resource_availability values (?,?,?,?,?,?)", scenario,
                UUID.randomUUID(), "PATH", START, START.plusHours(4), "Демонстрационные данные");
        jdbc.update("insert into vsm.cycle_resource values (?,?,?,?,?)", scenario, rules,
                "IS100", "PATH", "Демонстрационные данные");
        jdbc.update("insert into vsm.cycle_resource values (?,?,?,?,?)", scenario, rules,
                "IS200", "PATH", "Демонстрационные данные");
        jdbc.update("insert into vsm.cycle_baseline values (?,?,?,?,?,?,?)", scenario, train,
                rules, "IS100", 0, START.minusMonths(6), "Демонстрационные данные: начальный зачёт");
        jdbc.update("insert into vsm.cycle_baseline values (?,?,?,?,?,?,?)", scenario, train,
                rules, "IS200", 0, START.minusMonths(6), "Демонстрационные данные: начальный зачёт");
        trip(scenario, train, "R1", 20, 50);
        trip(scenario, train, "R2", 90, 120);
        trip(scenario, train, "R3", 160, 190);
        UUID event = UUID.randomUUID();
        jdbc.update("insert into vsm.service_event values (?,?,?,?,?,?,?,?,?)", scenario, event,
                train, rules, "IS100", START.minusDays(30).plusHours(10),
                START.minusDays(30).plusHours(10).plusMinutes(5), 12400,
                "Демонстрационные данные: принятая работа");
        jdbc.update("insert into vsm.service_credit values (?,?,?,?,?,?)", scenario, event,
                rules, "IS100", 12500, "Демонстрационные данные: зачёт IS100");
        jdbc.update("insert into vsm.train_presence values (?,?,?,?,?,?,?,?)", scenario,
                UUID.randomUUID(), train, "TEST_DEPOT", START.plusMinutes(50),
                START.plusMinutes(90), "SYNTHETIC", "Демонстрационные данные: присутствие состава");
        return captured(scenario, train);
    }

    @Transactional
    public Captured changeR1Arrival(UUID scenario, int arrivalMinute) {
        if (arrivalMinute < 50 || arrivalMinute > 60) {
            throw new IllegalArgumentException("Время прибытия R1: от 00:50 до 01:00 по Москве");
        }
        // Serialize concurrent edits, then capture the changed source in this transaction.
        UUID train = jdbc.query("select id from vsm.train where scenario_id = ? for update",
                (rs, row) -> rs.getObject(1, UUID.class), scenario).stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Демо-сценарий не найден"));
        int changed = jdbc.update("update vsm.fixed_trip set arrival_at = ? "
                        + "where scenario_id = ? and label = 'R1'", START.plusMinutes(arrivalMinute), scenario);
        if (changed != 1) throw new IllegalArgumentException("Рейс R1 не найден");
        return captured(scenario, train);
    }

    private Captured captured(UUID scenario, UUID train) {
        var snapshot = snapshots.capture(scenario);
        return new Captured(scenario, snapshot.id(), snapshot.snapshotHash(),
                List.of(new Dto.Train(train, "EVS-SYN-1", "AVAILABLE", 24000, "IS200@25 000 км")));
    }

    private void trip(UUID scenario, UUID train, String label, int from, int to) {
        jdbc.update("insert into vsm.fixed_trip values (?,?,?,?,?,?,?,?,?,?)", scenario,
                UUID.randomUUID(), train, label, START.plusMinutes(from), START.plusMinutes(to),
                670, "TEST_DEPOT", "TEST_DEPOT", "Демонстрационные данные: фиксированный рейс");
    }
}
