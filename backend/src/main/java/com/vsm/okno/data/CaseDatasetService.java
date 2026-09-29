package com.vsm.okno.data;

import com.vsm.okno.dto.Dto;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/** Case numeric norms plus explicitly MODELLED source facts. Never actual operator data. */
@Service
@Profile("database")
public class CaseDatasetService {
    public enum Dataset {
        FULL43(1, false), E2_6(2, true), BLOCKED6(3, true);
        final int mode;
        final boolean planningSupported;
        Dataset(int mode, boolean planningSupported) {
            this.mode = mode;
            this.planningSupported = planningSupported;
        }
    }

    public record Loaded(Dto.CaseDataset response, List<Dto.Train> trains) {}

    private final JdbcTemplate jdbc;
    private final SourceSnapshotRepository snapshots;

    public CaseDatasetService(JdbcTemplate jdbc, SourceSnapshotRepository snapshots) {
        this.jdbc = jdbc;
        this.snapshots = snapshots;
    }

    @Transactional
    public Loaded load(Dataset dataset) {
        try {
            // Execute the DO block whole; splitting on SQL semicolons breaks PL/pgSQL.
            jdbc.execute(new ClassPathResource("db/demo/seed_case_fleet.sql")
                    .getContentAsString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException("case dataset resource unavailable", e);
        }
        UUID scenarioId = jdbc.queryForObject("select md5(?)::uuid", UUID.class,
                "vsm-case-v1:scenario:" + dataset.mode);
        var stored = snapshots.capture(scenarioId);
        String provenance = jdbc.queryForObject("select provenance from vsm.scenario where id = ?",
                String.class, scenarioId);
        List<Dto.Train> trains = jdbc.query("""
                select t.id, t.external_id, t.status, o.odometer_km
                from vsm.train t join vsm.scenario s on s.id=t.scenario_id
                join lateral (
                    select odometer_km from vsm.odometer_reading r
                    where (r.scenario_id,r.train_id)=(t.scenario_id,t.id)
                      and r.observed_at<=s.horizon_start
                    order by r.observed_at desc limit 1
                ) o on true
                where t.scenario_id = ? order by t.external_id collate "C"
                """, (rs, row) -> new Dto.Train(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getLong(4), dataset.planningSupported ? "IS100/IS200: рассчитывается по snapshot"
                        : "Полные исходные данные E3; адаптер не подключён"), scenarioId);
        int tripCount = jdbc.queryForObject("select count(*) from vsm.fixed_trip where scenario_id = ?",
                Integer.class, scenarioId);
        var source = new Dto.DemoSource(scenarioId, stored.id(), stored.snapshotHash(), provenance);
        List<String> warnings = dataset.planningSupported
                ? List.of("Пробеги, даты и расписание модельные; IS100=2 ч, IS200=4 ч и 670 км взяты из кейса.",
                          "Это проверка E2: уборка, резерв, парная отправка и выпуск не входят в расчёт.")
                : List.of("Модельные факты: 34 состава на линии (17 пар), 5 в депо и 4 в резерве.",
                          "Нормативы восьми циклов — из кейса; история, рейсы и окна — модельные.",
                          "Расчёт полного парка заблокирован до адаптера E3 и независимой проверки D2.");
        return new Loaded(new Dto.CaseDataset(source, dataset.name(), trains.size(), tripCount,
                dataset.planningSupported, warnings), List.copyOf(trains));
    }
}
