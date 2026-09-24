\set ON_ERROR_STOP on
BEGIN;
\ir ../demo/seed_synthetic.sql
\ir ../demo/seed_operational.sql

CREATE FUNCTION pg_temp.expect_failure(statement text, expected_state text) RETURNS void LANGUAGE plpgsql AS $$
BEGIN
    BEGIN
        EXECUTE statement;
    EXCEPTION WHEN OTHERS THEN
        IF SQLSTATE = expected_state THEN RETURN; END IF;
        RAISE;
    END;
    RAISE EXCEPTION 'Expected SQLSTATE %, statement succeeded: %', expected_state, statement;
END;
$$;

SELECT pg_temp.expect_failure($q$UPDATE vsm.odometer_reading SET odometer_km = -1$q$, '23514');
SELECT pg_temp.expect_failure($q$UPDATE vsm.fixed_trip SET train_id = '99999999-0000-0000-0000-000000000001'$q$, '23503');
SELECT pg_temp.expect_failure($q$UPDATE vsm.fixed_trip SET arrival_at = departure_at$q$, '23514');
SELECT pg_temp.expect_failure($q$UPDATE vsm.fixed_trip SET departure_at = '2028-07-01T00:30:00+03:00'
 WHERE label = 'R2'$q$, '23P01');
SELECT pg_temp.expect_failure($q$UPDATE vsm.cycle_rule SET duration_minutes = 99$q$, '55000');
SELECT pg_temp.expect_failure($q$UPDATE vsm.cleaning_counter SET completed_trips_since_cleaning = -1$q$, '23514');
SELECT pg_temp.expect_failure($q$UPDATE vsm.train_presence SET starts_at = '2028-07-01T00:10:00+03:00'
 WHERE id = '80000000-0000-0000-0000-000000000002'$q$, '23P01');
SELECT pg_temp.expect_failure($q$UPDATE vsm.frozen_work SET resource_id = 'UNKNOWN'$q$, '23503');
SELECT pg_temp.expect_failure($q$SELECT vsm.capture_snapshot('99999999-0000-0000-0000-000000000001',gen_random_uuid())$q$, '22023');
SELECT pg_temp.expect_failure($q$INSERT INTO vsm.cycle_rule VALUES
 ('10000000-0000-0000-0000-000000000001','IS510',75000,1000,600,3,'test')$q$, '55000');

-- Boundary touching is legal: [start,end).
UPDATE vsm.fixed_trip SET departure_at = '2028-07-01T00:50:00+03:00' WHERE label = 'R2';
UPDATE vsm.fixed_trip SET departure_at = '2028-07-01T01:30:00+03:00' WHERE label = 'R2';

SELECT id FROM vsm.capture_snapshot('20000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000001');
SELECT pg_temp.expect_failure($q$UPDATE vsm.scenario_snapshot SET payload = '{}'::jsonb$q$, '55000');
SELECT pg_temp.expect_failure($q$DELETE FROM vsm.scenario_snapshot$q$, '55000');
SELECT pg_temp.expect_failure($q$TRUNCATE vsm.scenario_snapshot$q$, '55000');

-- Reordering source rows and changing the session timezone must not affect hash.
CREATE TEMP TABLE saved_trips AS TABLE vsm.fixed_trip;
DELETE FROM vsm.fixed_trip;
INSERT INTO vsm.fixed_trip SELECT * FROM saved_trips ORDER BY id DESC;
SET LOCAL timezone = 'Pacific/Honolulu';
SELECT id FROM vsm.capture_snapshot('20000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000002');
DO $$ BEGIN
    IF (SELECT count(*) FROM vsm.scenario_snapshot) <> 1 THEN
        RAISE EXCEPTION 'Unchanged content did not deduplicate';
    END IF;
    IF EXISTS (SELECT 1 FROM vsm.scenario_snapshot
       WHERE snapshot_hash <> encode(sha256(convert_to(canonical_payload, 'UTF8')), 'hex')) THEN
        RAISE EXCEPTION 'Hash does not match canonical bytes';
    END IF;
END $$;

UPDATE vsm.fixed_trip SET distance_km = 671 WHERE label = 'R1';
SELECT id FROM vsm.capture_snapshot('20000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000003');
DO $$ BEGIN
    IF (SELECT count(DISTINCT snapshot_hash) FROM vsm.scenario_snapshot) <> 2 THEN
        RAISE EXCEPTION 'Changed source did not change hash';
    END IF;
    IF (SELECT (payload->'fixedTrips'->0->>'distance_km')::bigint FROM vsm.scenario_snapshot
        WHERE id = '70000000-0000-0000-0000-000000000001') <> 670 THEN
        RAISE EXCEPTION 'Old snapshot changed with source';
    END IF;
    IF NOT EXISTS (SELECT 1 FROM vsm.service_credit c JOIN vsm.service_event e
        ON e.scenario_id = c.scenario_id AND e.id = c.service_event_id
        WHERE c.credited_nominal_km > e.actual_odometer_km) THEN
        RAISE EXCEPTION 'Early maintenance credit was not stored';
    END IF;
END $$;
UPDATE vsm.cleaning_counter SET completed_trips_since_cleaning = 1;
SELECT id FROM vsm.capture_snapshot('20000000-0000-0000-0000-000000000001', '70000000-0000-0000-0000-000000000004');
DO $$ BEGIN
    IF (SELECT count(DISTINCT snapshot_hash) FROM vsm.scenario_snapshot) <> 3 THEN
        RAISE EXCEPTION 'E3 counter did not change hash';
    END IF;
    IF (SELECT jsonb_array_length(payload->'frozenWork') FROM vsm.scenario_snapshot
        WHERE id = '70000000-0000-0000-0000-000000000001') <> 1 THEN
        RAISE EXCEPTION 'Frozen work missing from snapshot';
    END IF;
END $$;
ROLLBACK;
\echo D1 database constraints and snapshot checks PASSED; fixture rolled back.
