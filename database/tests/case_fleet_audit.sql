-- Read-only source audit. Run AFTER explicitly loading the case datasets.
-- Zero violations is data consistency only, NOT approval of an E3 plan.
SELECT s.id,s.name,s.horizon_start,s.horizon_end,
       (SELECT count(*) FROM vsm.train t WHERE t.scenario_id=s.id) trains,
       (SELECT count(*) FROM vsm.fixed_trip t WHERE t.scenario_id=s.id) trips,
       (SELECT count(*) FROM vsm.scenario_snapshot p WHERE p.scenario_id=s.id) snapshots
FROM vsm.scenario s WHERE s.id IN
 (md5('vsm-case-v1:scenario:1')::uuid,md5('vsm-case-v1:scenario:2')::uuid,md5('vsm-case-v1:scenario:3')::uuid)
ORDER BY s.name;

WITH trips AS (
    SELECT t.*,lag(destination) OVER(partition by scenario_id,train_id order by departure_at) previous_city
    FROM vsm.fixed_trip t WHERE scenario_id=md5('vsm-case-v1:scenario:1')::uuid
)
SELECT 'route continuity' check_name,count(*) violations FROM trips
WHERE previous_city IS NOT NULL AND previous_city<>origin
UNION ALL
SELECT 'cleaning overlaps trip',count(*) FROM vsm.train_occupancy o JOIN vsm.fixed_trip t
ON (o.scenario_id,o.train_id)=(t.scenario_id,t.train_id)
WHERE o.scenario_id=md5('vsm-case-v1:scenario:1')::uuid AND o.kind='CLEANING'
AND tstzrange(o.starts_at,o.ends_at,'[)') && tstzrange(t.departure_at,t.arrival_at,'[)')
UNION ALL
SELECT 'presence overlaps trip',count(*) FROM vsm.train_presence p JOIN vsm.fixed_trip t
ON (p.scenario_id,p.train_id)=(t.scenario_id,t.train_id)
WHERE p.scenario_id=md5('vsm-case-v1:scenario:1')::uuid
AND tstzrange(p.starts_at,p.ends_at,'[)') && tstzrange(t.departure_at,t.arrival_at,'[)')
UNION ALL
SELECT 'paired trips have other than 2 trains',count(*) FROM (
 SELECT label,departure_at,arrival_at,origin,destination,count(*) n FROM vsm.fixed_trip
 WHERE scenario_id=md5('vsm-case-v1:scenario:1')::uuid GROUP BY 1,2,3,4,5
) pairs WHERE n<>2
UNION ALL
SELECT 'snapshot SHA-256 mismatch',count(*) FROM vsm.scenario_snapshot
WHERE scenario_id IN (md5('vsm-case-v1:scenario:1')::uuid,md5('vsm-case-v1:scenario:2')::uuid,md5('vsm-case-v1:scenario:3')::uuid)
AND snapshot_hash<>encode(sha256(convert_to(canonical_payload,'UTF8')),'hex');

SELECT t.external_id,t.status,t.location,o.odometer_km initial_km,
       coalesce(sum(f.distance_km),0) trip_km,o.odometer_km+coalesce(sum(f.distance_km),0) final_km
FROM vsm.train t JOIN vsm.odometer_reading o ON (t.scenario_id,t.id)=(o.scenario_id,o.train_id)
LEFT JOIN vsm.fixed_trip f ON (t.scenario_id,t.id)=(f.scenario_id,f.train_id)
WHERE t.scenario_id=md5('vsm-case-v1:scenario:1')::uuid
GROUP BY t.external_id,t.status,t.location,o.odometer_km ORDER BY t.external_id;

SELECT location,count(*) reserve_trains FROM vsm.train
WHERE scenario_id=md5('vsm-case-v1:scenario:1')::uuid AND status='RESERVE' GROUP BY location ORDER BY location;
