-- E3 source facts only. No inferred cleaning, release or dynamic reserve policy.
CREATE TABLE vsm.train_presence (
    scenario_id uuid NOT NULL,
    id uuid NOT NULL,
    train_id uuid NOT NULL,
    location text NOT NULL CHECK (btrim(location) <> ''),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    confirmation_status text NOT NULL CHECK (confirmation_status IN ('CONFIRMED', 'UNCONFIRMED', 'SYNTHETIC')),
    source text NOT NULL CHECK (btrim(source) <> ''),
    PRIMARY KEY (scenario_id, id),
    FOREIGN KEY (scenario_id, train_id) REFERENCES vsm.train(scenario_id, id),
    CHECK (ends_at > starts_at),
    EXCLUDE USING gist (scenario_id WITH =, train_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
);

-- Additional external occupancy; fixed trips and frozen work are separate tables.
-- Multiple reasons for unavailability may legitimately overlap.
CREATE TABLE vsm.train_occupancy (
    scenario_id uuid NOT NULL,
    id uuid NOT NULL,
    train_id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('RESERVE', 'CLEANING', 'UNAVAILABLE', 'OTHER')),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    confirmation_status text NOT NULL CHECK (confirmation_status IN ('CONFIRMED', 'UNCONFIRMED', 'SYNTHETIC')),
    source text NOT NULL CHECK (btrim(source) <> ''),
    PRIMARY KEY (scenario_id, id),
    FOREIGN KEY (scenario_id, train_id) REFERENCES vsm.train(scenario_id, id),
    CHECK (ends_at > starts_at)
);

CREATE TABLE vsm.cleaning_counter (
    scenario_id uuid NOT NULL,
    train_id uuid NOT NULL,
    observed_at timestamptz NOT NULL,
    completed_trips_since_cleaning integer NOT NULL CHECK (completed_trips_since_cleaning >= 0),
    last_cleaning_accepted_at timestamptz,
    confirmation_status text NOT NULL CHECK (confirmation_status IN ('CONFIRMED', 'UNCONFIRMED', 'SYNTHETIC')),
    source text NOT NULL CHECK (btrim(source) <> ''),
    PRIMARY KEY (scenario_id, train_id, observed_at),
    FOREIGN KEY (scenario_id, train_id) REFERENCES vsm.train(scenario_id, id),
    CHECK (last_cleaning_accepted_at IS NULL OR last_cleaning_accepted_at <= observed_at)
);

CREATE TABLE vsm.frozen_work (
    scenario_id uuid NOT NULL,
    id uuid NOT NULL,
    train_id uuid NOT NULL,
    resource_id text NOT NULL,
    external_work_id text NOT NULL CHECK (btrim(external_work_id) <> ''),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    confirmation_status text NOT NULL CHECK (confirmation_status IN ('CONFIRMED', 'UNCONFIRMED', 'SYNTHETIC')),
    source text NOT NULL CHECK (btrim(source) <> ''),
    PRIMARY KEY (scenario_id, id),
    UNIQUE (scenario_id, external_work_id),
    FOREIGN KEY (scenario_id, train_id) REFERENCES vsm.train(scenario_id, id),
    FOREIGN KEY (scenario_id, resource_id) REFERENCES vsm.resource(scenario_id, id),
    CHECK (ends_at > starts_at),
    EXCLUDE USING gist (scenario_id WITH =, train_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&),
    EXCLUDE USING gist (scenario_id WITH =, resource_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
);

-- Same initial unpublished source schema; apply V1-V3 as one foundation release.
CREATE OR REPLACE FUNCTION vsm.source_payload(p_scenario_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SET timezone = 'UTC' SET datestyle = 'ISO, YMD' AS $$
    SELECT vsm.source_payload_base(p_scenario_id) || jsonb_build_object(
        'trainPresence', COALESCE((SELECT jsonb_agg(to_jsonb(p) ORDER BY p.id) FROM vsm.train_presence p WHERE p.scenario_id = p_scenario_id), '[]'::jsonb),
        'trainOccupancy', COALESCE((SELECT jsonb_agg(to_jsonb(o) ORDER BY o.id) FROM vsm.train_occupancy o WHERE o.scenario_id = p_scenario_id), '[]'::jsonb),
        'cleaningCounters', COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.train_id, c.observed_at) FROM vsm.cleaning_counter c WHERE c.scenario_id = p_scenario_id), '[]'::jsonb),
        'frozenWork', COALESCE((SELECT jsonb_agg(to_jsonb(w) ORDER BY w.id) FROM vsm.frozen_work w WHERE w.scenario_id = p_scenario_id), '[]'::jsonb)
    );
$$;
