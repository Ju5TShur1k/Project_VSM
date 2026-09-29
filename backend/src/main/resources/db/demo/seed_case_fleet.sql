-- CASE DATASET v1. Additive, repeatable loader; NEVER a Flyway migration.
-- Case p.3: normative mileage/tolerance/duration. All individual odometers,
-- dates, trips, histories and calendars below are MODELLED, not operator data.
-- Full43 stages E3 source facts; E2_6/Blocked6 are explicit reduced E2 checks.
DO $case$
DECLARE
    mode integer;
    s uuid;
    rules uuid;
    t uuid;
    trip_id uuid;
    event_id uuid;
    r record;
    i integer;
    d integer;
    leg integer;
    count_trains integer;
    km bigint;
    history_km bigint;
    from_city text;
    to_city text;
    location text;
    pair_no integer;
    starts_moscow boolean;
    dep timestamptz;
    arr timestamptz;
    next_dep timestamptz;
    present_from timestamptz;
    present_to timestamptz;
    horizon timestamptz := '2031-07-01T00:00:00+03:00';
    model_source text := 'MODELLED case dataset v1; not actual train/trip history';
BEGIN
    PERFORM pg_advisory_xact_lock(hashtextextended('vsm-case-dataset-v1', 0));
    FOR mode IN 1..3 LOOP
        s := md5('vsm-case-v1:scenario:' || mode)::uuid;
        -- Existing scenarios are preserved, including user edits and snapshots.
        IF EXISTS (SELECT 1 FROM vsm.scenario WHERE id = s) THEN CONTINUE; END IF;
        rules := md5('vsm-case-v1:rules:' || mode)::uuid;
        count_trains := CASE WHEN mode = 1 THEN 43 ELSE 6 END;
        INSERT INTO vsm.rule_set(id,version,source,confirmation_status,mileage_policy,tolerance_basis)
        VALUES (rules, 'case-model-v1-' || mode,
                'Case 06 p.3 numeric norms; grid/tolerance interpretation is MODELLED pending A1',
                'SYNTHETIC', 'ABSOLUTE_GRID', 'NOMINAL_MILESTONE');
        FOR r IN SELECT * FROM (VALUES
            ('IS100',12500::bigint,1000,120,1), ('IS200',25000::bigint,2000,240,2),
            ('IS510',75000::bigint,2000,600,3), ('IS520',150000::bigint,2000,960,4),
            ('IS530',300000::bigint,2000,2160,5), ('IS540',600000::bigint,2000,3360,6),
            ('IS600',1200000::bigint,2000,23040,7), ('IS700',2400000::bigint,2000,34500,8)
        ) AS norm(code,interval_km,tolerance,duration,rank) LOOP
            IF mode <> 1 AND r.rank > 2 THEN CONTINUE; END IF;
            INSERT INTO vsm.cycle_rule VALUES
            (rules,r.code,r.interval_km,r.tolerance,r.duration,r.rank,'Case 06 p.3 table; whole-cycle duration in minutes');
        END LOOP;
        INSERT INTO vsm.scenario(id,name,rule_set_id,horizon_start,horizon_end,provenance)
        VALUES (s, CASE mode WHEN 1 THEN 'Case model: full 43 train source (E3 staging)'
                           WHEN 2 THEN 'Case model: 6 train E2 calculation'
                           ELSE 'Case model: 6 train E2 resource shortage' END,
                rules,horizon,horizon + interval '14 days',
                CASE WHEN mode = 1 THEN 'MODELLED FULL43: 34 line / 5 depot / 4 reserve; not an approved E3 plan'
                     ELSE 'MODELLED E2: case IS100/IS200 norms; reserve, cleaning and paired dispatch excluded' END);
        FOR i IN 1..CASE WHEN mode = 1 THEN 5 ELSE 1 END LOOP
            INSERT INTO vsm.resource VALUES (s,'SPB-PATH-' || i,'Model depot position ' || i,'SPB_DEPOT',model_source);
            INSERT INTO vsm.resource_availability VALUES
            (s,md5(s || ':availability:' || i)::uuid,'SPB-PATH-' || i,horizon,
             horizon + CASE WHEN mode = 3 THEN interval '1 day' ELSE interval '14 days' END,model_source);
            INSERT INTO vsm.cycle_resource
            SELECT s,rules,code,'SPB-PATH-' || i,model_source FROM vsm.cycle_rule WHERE rule_set_id = rules;
        END LOOP;
        IF mode = 1 THEN
            INSERT INTO vsm.resource VALUES (s,'SPB-LATHE','Model lathe (count not confirmed)','SPB_DEPOT',model_source);
            INSERT INTO vsm.resource_availability VALUES
            (s,md5(s || ':lathe-availability')::uuid,'SPB-LATHE',horizon,horizon + interval '14 days',model_source);
        END IF;
        FOR i IN 1..count_trains LOOP
            t := md5(s || ':train:' || i)::uuid;
            km := CASE WHEN mode <> 1 THEN (ARRAY[1000,2500,4000,15500,17500,19000])[i]
                       WHEN i = 35 THEN 74000 WHEN i = 36 THEN 149000
                       WHEN i = 37 THEN 299000 WHEN i = 38 THEN 599000 WHEN i = 39 THEN 1199000
                       ELSE 1000 + ((i - 1) * 57317) % 1150000 END;
            location := CASE WHEN mode = 1 AND i > 39 THEN
                                CASE WHEN i < 42 THEN 'MOSCOW' ELSE 'SPB_DEPOT' END
                             WHEN mode = 1 AND i >= 35 THEN 'SPB_DEPOT'
                             WHEN mod(i,2) = 0 THEN 'MOSCOW' ELSE 'SPB_DEPOT' END;
            -- Full source has 17 actual *model* pairs, not independent single-train dispatch.
            pair_no := (i + 1) / 2;
            IF mode = 1 AND i <= 34 THEN location := CASE WHEN mod(pair_no,2)=0 THEN 'MOSCOW' ELSE 'SPB_DEPOT' END; END IF;
            INSERT INTO vsm.train VALUES (s,t,'CASE-' || lpad(i::text,2,'0'),
                CASE WHEN mode=1 AND i>39 THEN 'RESERVE' WHEN mode=1 AND i>=35 THEN 'MAINTENANCE' ELSE 'AVAILABLE' END,
                location,model_source);
            INSERT INTO vsm.odometer_reading VALUES (s,t,horizon,km,model_source);
            INSERT INTO vsm.cycle_baseline
            SELECT s,t,rules,code,(km / interval_km) * interval_km,horizon - interval '1 day',model_source
            FROM vsm.cycle_rule WHERE rule_set_id = rules;
            history_km := (km / 12500) * 12500;
            event_id := md5(s || ':history:' || i)::uuid;
            INSERT INTO vsm.service_event VALUES
            (s,event_id,t,rules,'IS100',horizon - interval '2 days',horizon - interval '2 days' + interval '5 minutes',history_km,model_source);
            INSERT INTO vsm.service_credit VALUES (s,event_id,rules,'IS100',history_km,model_source);
            IF mode = 1 THEN
                INSERT INTO vsm.cleaning_counter VALUES (s,t,horizon,0,horizon - interval '1 hour','SYNTHETIC',model_source);
                IF i >= 35 THEN
                    INSERT INTO vsm.train_presence VALUES
                    (s,md5(s || ':static-presence:' || i)::uuid,t,location,horizon,horizon + interval '14 days','SYNTHETIC',model_source);
                    INSERT INTO vsm.train_occupancy VALUES
                    (s,md5(s || ':static-occupancy:' || i)::uuid,t,
                     CASE WHEN i>39 THEN 'RESERVE' ELSE 'UNAVAILABLE' END,
                     horizon,horizon + CASE WHEN i>39 THEN interval '14 days' ELSE interval '1 day' END,
                     'SYNTHETIC',model_source);
                    IF i <= 39 THEN
                        INSERT INTO vsm.frozen_work VALUES
                        (s,md5(s || ':frozen:' || i)::uuid,t,'SPB-PATH-' || (i-34),'MODEL-FROZEN-' || i,
                         horizon,horizon + interval '2 hours','SYNTHETIC',model_source);
                    END IF;
                    CONTINUE;
                END IF;
            END IF;
            starts_moscow := location='MOSCOW';
            FOR d IN 0..13 LOOP
                FOR leg IN 1..3 LOOP
                    dep := horizon + d * interval '1 day' + (6 + (leg-1)*6) * interval '1 hour';
                    arr := dep + interval '120 minutes';
                    from_city := CASE WHEN (starts_moscow <> (mod(d*3+leg-1,2)=1)) THEN 'MOSCOW' ELSE 'SPB_DEPOT' END;
                    to_city := CASE WHEN from_city='MOSCOW' THEN 'SPB_DEPOT' ELSE 'MOSCOW' END;
                    trip_id := md5(s || ':trip:' || i || ':' || d || ':' || leg)::uuid;
                    INSERT INTO vsm.fixed_trip VALUES
                    (s,trip_id,t,CASE WHEN mode=1 THEN 'MODEL-PAIR-' || pair_no ELSE 'MODEL-SINGLE-' || i END
                        || '-D' || (d+1) || '-R' || leg,dep,arr,670,from_city,to_city,model_source);
                    next_dep := CASE WHEN leg=3 THEN horizon + (d+1)*interval '1 day' + interval '6 hours'
                                     ELSE dep + interval '6 hours' END;
                    present_from := arr + interval '30 minutes';
                    present_to := LEAST(next_dep - interval '55 minutes',horizon + interval '14 days');
                    IF mode=1 AND mod(d*3+leg,4)=0 THEN
                        INSERT INTO vsm.train_occupancy VALUES
                        (s,md5(trip_id || ':cleaning')::uuid,t,'CLEANING',present_from,
                         present_from + interval '150 minutes','SYNTHETIC',model_source);
                    END IF;
                    INSERT INTO vsm.train_presence VALUES
                    (s,md5(trip_id || ':presence')::uuid,t,to_city,present_from,present_to,'SYNTHETIC',model_source);
                END LOOP;
            END LOOP;
        END LOOP;
        PERFORM vsm.capture_snapshot(s,md5(s || ':initial-snapshot')::uuid);
    END LOOP;
END
$case$;
