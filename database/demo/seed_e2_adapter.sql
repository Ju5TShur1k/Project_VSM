-- Optional second SYNTHETIC scenario for D1's narrow E2 adapter.
-- Requires seed_synthetic.sql, without seed_operational.sql for this scenario.
-- It deliberately contains no reserve/cleaning/frozen facts; not an E3 fleet plan.
INSERT INTO vsm.scenario(id, name, rule_set_id, horizon_start, horizon_end, provenance)
SELECT '20000000-0000-0000-0000-000000000002', 'D1 E2 adapter handoff', rule_set_id,
       horizon_start, horizon_end, 'SYNTHETIC E2 adapter; reserve not modelled'
FROM vsm.scenario WHERE id = '20000000-0000-0000-0000-000000000001';
INSERT INTO vsm.train
SELECT '20000000-0000-0000-0000-000000000002',
       '30000000-0000-0000-0000-000000000002', 'EVS-SYN-E2', status, location, source
FROM vsm.train WHERE scenario_id = '20000000-0000-0000-0000-000000000001';
INSERT INTO vsm.odometer_reading
SELECT '20000000-0000-0000-0000-000000000002',
       '30000000-0000-0000-0000-000000000002', observed_at, odometer_km, source
FROM vsm.odometer_reading WHERE scenario_id = '20000000-0000-0000-0000-000000000001';
INSERT INTO vsm.resource
SELECT '20000000-0000-0000-0000-000000000002', id, name, location, source
FROM vsm.resource WHERE scenario_id = '20000000-0000-0000-0000-000000000001';
INSERT INTO vsm.resource_availability
SELECT '20000000-0000-0000-0000-000000000002', id, resource_id, starts_at, ends_at, source
FROM vsm.resource_availability WHERE scenario_id = '20000000-0000-0000-0000-000000000001';
INSERT INTO vsm.cycle_resource
SELECT '20000000-0000-0000-0000-000000000002', rule_set_id, cycle_code, resource_id, source
FROM vsm.cycle_resource WHERE scenario_id = '20000000-0000-0000-0000-000000000001';
INSERT INTO vsm.cycle_baseline
SELECT '20000000-0000-0000-0000-000000000002',
       '30000000-0000-0000-0000-000000000002', rule_set_id, cycle_code,
       credited_nominal_km, recorded_at, source
FROM vsm.cycle_baseline WHERE scenario_id = '20000000-0000-0000-0000-000000000001';
INSERT INTO vsm.fixed_trip
SELECT '20000000-0000-0000-0000-000000000002', id,
       '30000000-0000-0000-0000-000000000002', label,
       departure_at, arrival_at, distance_km, origin, destination, source
FROM vsm.fixed_trip WHERE scenario_id = '20000000-0000-0000-0000-000000000001';
INSERT INTO vsm.service_event
SELECT '20000000-0000-0000-0000-000000000002', id,
       '30000000-0000-0000-0000-000000000002', rule_set_id, performed_cycle_code,
       completed_at, accepted_at, actual_odometer_km, source
FROM vsm.service_event WHERE scenario_id = '20000000-0000-0000-0000-000000000001';
INSERT INTO vsm.service_credit
SELECT '20000000-0000-0000-0000-000000000002', service_event_id,
       rule_set_id, covered_cycle_code, credited_nominal_km, source
FROM vsm.service_credit WHERE scenario_id = '20000000-0000-0000-0000-000000000001';
INSERT INTO vsm.train_presence VALUES
('20000000-0000-0000-0000-000000000002', '80000000-0000-0000-0000-000000000005',
 '30000000-0000-0000-0000-000000000002', 'TEST_DEPOT',
 '2028-07-01T00:50:00+03:00', '2028-07-01T01:30:00+03:00', 'SYNTHETIC',
 'synthetic checked train presence for E2 adapter');
