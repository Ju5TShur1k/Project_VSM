-- Internal D1 source format, deliberately distinct from F2 ScenarioSnapshot 1.1.
-- Canonicalization is versioned PostgreSQL JSONB text, NOT RFC 8785/JCS.
-- convert_to is catalogued STABLE; fixing UTF8 and requiring UTF8 database encoding
-- makes this particular composition deterministic for stored JSONB text.
DO $$ BEGIN
    IF current_setting('server_encoding') <> 'UTF8' THEN
        RAISE EXCEPTION 'D1 canonical snapshots require a UTF8 database';
    END IF;
END $$;
CREATE FUNCTION vsm.canonical_sha256(value jsonb) RETURNS text
LANGUAGE sql IMMUTABLE STRICT SET search_path = pg_catalog AS $$
    SELECT encode(sha256(convert_to(value::text, 'UTF8')), 'hex');
$$;
CREATE TABLE vsm.scenario_snapshot (
    id uuid PRIMARY KEY,
    scenario_id uuid NOT NULL REFERENCES vsm.scenario(id),
    schema_version text NOT NULL DEFAULT 'd1-source-1.0' CHECK (schema_version = 'd1-source-1.0'),
    canonicalization text NOT NULL DEFAULT 'pg-jsonb-text-v1' CHECK (canonicalization = 'pg-jsonb-text-v1'),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    canonical_payload text GENERATED ALWAYS AS (payload::text) STORED,
    snapshot_hash text GENERATED ALWAYS AS (vsm.canonical_sha256(payload)) STORED,
    captured_at timestamptz NOT NULL DEFAULT now(),
    CHECK (payload->>'scenarioId' IS NOT NULL AND payload->>'scenarioId' = scenario_id::text),
    CHECK (payload->>'schemaVersion' IS NOT NULL AND payload->>'schemaVersion' = schema_version),
    CHECK (payload->>'canonicalization' IS NOT NULL AND payload->>'canonicalization' = canonicalization),
    UNIQUE (scenario_id, snapshot_hash)
);
CREATE TRIGGER immutable_snapshot BEFORE UPDATE OR DELETE ON vsm.scenario_snapshot
    FOR EACH ROW EXECUTE FUNCTION vsm.reject_mutation();
CREATE TRIGGER no_truncate_snapshot BEFORE TRUNCATE ON vsm.scenario_snapshot
    FOR EACH STATEMENT EXECUTE FUNCTION vsm.reject_mutation();

-- One SQL statement gives all subqueries the same MVCC statement snapshot.
-- Explicit ordering of arrays; UTC for timestamp serialization; no capture timestamp in hash.
CREATE FUNCTION vsm.source_payload_base(p_scenario_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SET timezone = 'UTC' SET datestyle = 'ISO, YMD' AS $$
    SELECT jsonb_build_object(
        'schemaVersion', 'd1-source-1.0',
        'canonicalization', 'pg-jsonb-text-v1',
        'scenarioId', s.id,
        'scenario', to_jsonb(s),
        'ruleSets', COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id)
            FROM vsm.rule_set r WHERE r.id = s.rule_set_id OR r.id IN
                (SELECT e.rule_set_id FROM vsm.service_event e WHERE e.scenario_id = s.id
                 UNION SELECT b.rule_set_id FROM vsm.cycle_baseline b WHERE b.scenario_id = s.id)), '[]'::jsonb),
        'cycleRules', COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.rule_set_id, r.code COLLATE "C")
            FROM vsm.cycle_rule r WHERE r.rule_set_id = s.rule_set_id OR r.rule_set_id IN
                (SELECT e.rule_set_id FROM vsm.service_event e WHERE e.scenario_id = s.id
                 UNION SELECT b.rule_set_id FROM vsm.cycle_baseline b WHERE b.scenario_id = s.id)), '[]'::jsonb),
        'trains', COALESCE((SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) FROM vsm.train t WHERE t.scenario_id = s.id), '[]'::jsonb),
        'odometerReadings', COALESCE((SELECT jsonb_agg(to_jsonb(o) ORDER BY o.train_id, o.observed_at) FROM vsm.odometer_reading o WHERE o.scenario_id = s.id), '[]'::jsonb),
        'resources', COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.id COLLATE "C") FROM vsm.resource r WHERE r.scenario_id = s.id), '[]'::jsonb),
        'resourceAvailability', COALESCE((SELECT jsonb_agg(to_jsonb(a) ORDER BY a.id) FROM vsm.resource_availability a WHERE a.scenario_id = s.id), '[]'::jsonb),
        'cycleResources', COALESCE((SELECT jsonb_agg(to_jsonb(r) ORDER BY r.cycle_code COLLATE "C", r.resource_id COLLATE "C") FROM vsm.cycle_resource r WHERE r.scenario_id = s.id), '[]'::jsonb),
        'cycleBaselines', COALESCE((SELECT jsonb_agg(to_jsonb(b) ORDER BY b.train_id, b.rule_set_id, b.cycle_code COLLATE "C") FROM vsm.cycle_baseline b WHERE b.scenario_id = s.id), '[]'::jsonb),
        'fixedTrips', COALESCE((SELECT jsonb_agg(to_jsonb(t) ORDER BY t.id) FROM vsm.fixed_trip t WHERE t.scenario_id = s.id), '[]'::jsonb),
        'serviceEvents', COALESCE((SELECT jsonb_agg(to_jsonb(e) ORDER BY e.id) FROM vsm.service_event e WHERE e.scenario_id = s.id), '[]'::jsonb),
        'serviceCredits', COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.service_event_id, c.covered_cycle_code COLLATE "C") FROM vsm.service_credit c WHERE c.scenario_id = s.id), '[]'::jsonb)
    ) FROM vsm.scenario s WHERE s.id = p_scenario_id;
$$;

CREATE FUNCTION vsm.source_payload(p_scenario_id uuid)
RETURNS jsonb LANGUAGE sql STABLE AS $$
    SELECT vsm.source_payload_base(p_scenario_id);
$$;

CREATE FUNCTION vsm.capture_snapshot(p_scenario_id uuid, p_snapshot_id uuid)
RETURNS vsm.scenario_snapshot LANGUAGE plpgsql AS $$
DECLARE
    source jsonb;
    result vsm.scenario_snapshot;
BEGIN
    -- A single STABLE source query reads all source tables consistently.
    SELECT vsm.source_payload(p_scenario_id) INTO source;
    IF source IS NULL THEN
        RAISE EXCEPTION 'unknown scenario %', p_scenario_id USING ERRCODE = '22023';
    END IF;
    INSERT INTO vsm.scenario_snapshot (id, scenario_id, payload)
    VALUES (p_snapshot_id, p_scenario_id, source)
    ON CONFLICT (scenario_id, snapshot_hash) DO NOTHING
    RETURNING * INTO result;
    IF result.id IS NULL THEN
        SELECT * INTO STRICT result FROM vsm.scenario_snapshot
        WHERE scenario_id = p_scenario_id AND snapshot_hash = vsm.canonical_sha256(source);
    END IF;
    RETURN result;
END;
$$;
