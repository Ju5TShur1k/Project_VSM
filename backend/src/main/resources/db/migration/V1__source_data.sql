-- Source facts, not a copy of F2's prepared ScenarioSnapshot.
CREATE SCHEMA vsm;
CREATE EXTENSION IF NOT EXISTS btree_gist;

CREATE TABLE vsm.rule_set (
    id uuid PRIMARY KEY,
    version text NOT NULL UNIQUE CHECK (btrim(version) <> ''),
    source text NOT NULL CHECK (btrim(source) <> ''),
    confirmation_status text NOT NULL CHECK (confirmation_status IN ('SYNTHETIC', 'UNCONFIRMED', 'CONFIRMED')),
    mileage_policy text NOT NULL CHECK (mileage_policy IN ('ABSOLUTE_GRID', 'FROM_LAST_SERVICE', 'UNCONFIRMED')),
    tolerance_basis text NOT NULL CHECK (tolerance_basis IN ('INTERVAL', 'NOMINAL_MILESTONE', 'UNCONFIRMED')),
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE vsm.cycle_rule (
    rule_set_id uuid NOT NULL REFERENCES vsm.rule_set(id),
    code text NOT NULL CHECK (btrim(code) <> ''),
    interval_km bigint NOT NULL CHECK (interval_km > 0),
    tolerance_basis_points integer NOT NULL CHECK (tolerance_basis_points BETWEEN 0 AND 9999),
    duration_minutes integer NOT NULL CHECK (duration_minutes > 0),
    rank integer NOT NULL CHECK (rank >= 0),
    source text NOT NULL CHECK (btrim(source) <> ''),
    PRIMARY KEY (rule_set_id, code)
);

CREATE TABLE vsm.scenario (
    id uuid PRIMARY KEY,
    name text NOT NULL CHECK (btrim(name) <> ''),
    rule_set_id uuid NOT NULL REFERENCES vsm.rule_set(id),
    horizon_start timestamptz NOT NULL,
    horizon_end timestamptz NOT NULL,
    provenance text NOT NULL CHECK (btrim(provenance) <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    CHECK (horizon_end > horizon_start),
    UNIQUE (id, rule_set_id)
);

CREATE TABLE vsm.train (
    scenario_id uuid NOT NULL REFERENCES vsm.scenario(id),
    id uuid NOT NULL,
    external_id text NOT NULL CHECK (btrim(external_id) <> ''),
    status text NOT NULL CHECK (status IN ('AVAILABLE', 'IN_SERVICE', 'MAINTENANCE', 'FAILED', 'RESERVE', 'UNKNOWN')),
    location text,
    source text NOT NULL CHECK (btrim(source) <> ''),
    PRIMARY KEY (scenario_id, id),
    UNIQUE (scenario_id, external_id)
);

CREATE TABLE vsm.odometer_reading (
    scenario_id uuid NOT NULL,
    train_id uuid NOT NULL,
    observed_at timestamptz NOT NULL,
    odometer_km bigint NOT NULL CHECK (odometer_km >= 0),
    source text NOT NULL CHECK (btrim(source) <> ''),
    PRIMARY KEY (scenario_id, train_id, observed_at),
    FOREIGN KEY (scenario_id, train_id) REFERENCES vsm.train(scenario_id, id)
);

CREATE TABLE vsm.resource (
    scenario_id uuid NOT NULL REFERENCES vsm.scenario(id),
    id text NOT NULL CHECK (btrim(id) <> ''),
    name text NOT NULL CHECK (btrim(name) <> ''),
    location text,
    source text NOT NULL CHECK (btrim(source) <> ''),
    PRIMARY KEY (scenario_id, id)
);

-- Explicit availability only. Missing intervals NEVER mean available 24/7.
CREATE TABLE vsm.resource_availability (
    scenario_id uuid NOT NULL,
    id uuid NOT NULL,
    resource_id text NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    source text NOT NULL CHECK (btrim(source) <> ''),
    PRIMARY KEY (scenario_id, id),
    FOREIGN KEY (scenario_id, resource_id) REFERENCES vsm.resource(scenario_id, id),
    CHECK (ends_at > starts_at),
    EXCLUDE USING gist (scenario_id WITH =, resource_id WITH =, tstzrange(starts_at, ends_at, '[)') WITH &&)
);

CREATE TABLE vsm.cycle_resource (
    scenario_id uuid NOT NULL,
    rule_set_id uuid NOT NULL,
    cycle_code text NOT NULL,
    resource_id text NOT NULL,
    source text NOT NULL CHECK (btrim(source) <> ''),
    PRIMARY KEY (scenario_id, cycle_code, resource_id),
    FOREIGN KEY (scenario_id, rule_set_id) REFERENCES vsm.scenario(id, rule_set_id),
    FOREIGN KEY (rule_set_id, cycle_code) REFERENCES vsm.cycle_rule(rule_set_id, code),
    FOREIGN KEY (scenario_id, resource_id) REFERENCES vsm.resource(scenario_id, id)
);

-- Explicit starting credit, e.g. documented commissioning baseline at 0 km.
-- Missing rows are unknown, never implicitly zero.
CREATE TABLE vsm.cycle_baseline (
    scenario_id uuid NOT NULL,
    train_id uuid NOT NULL,
    rule_set_id uuid NOT NULL,
    cycle_code text NOT NULL,
    credited_nominal_km bigint NOT NULL CHECK (credited_nominal_km >= 0),
    recorded_at timestamptz NOT NULL,
    source text NOT NULL CHECK (btrim(source) <> ''),
    PRIMARY KEY (scenario_id, train_id, rule_set_id, cycle_code),
    FOREIGN KEY (scenario_id, train_id) REFERENCES vsm.train(scenario_id, id),
    FOREIGN KEY (rule_set_id, cycle_code) REFERENCES vsm.cycle_rule(rule_set_id, code)
);

CREATE TABLE vsm.fixed_trip (
    scenario_id uuid NOT NULL,
    id uuid NOT NULL,
    train_id uuid NOT NULL,
    label text NOT NULL CHECK (btrim(label) <> ''),
    departure_at timestamptz NOT NULL,
    arrival_at timestamptz NOT NULL,
    distance_km bigint NOT NULL CHECK (distance_km > 0),
    origin text,
    destination text,
    source text NOT NULL CHECK (btrim(source) <> ''),
    PRIMARY KEY (scenario_id, id),
    FOREIGN KEY (scenario_id, train_id) REFERENCES vsm.train(scenario_id, id),
    CHECK (arrival_at > departure_at),
    EXCLUDE USING gist (scenario_id WITH =, train_id WITH =, tstzrange(departure_at, arrival_at, '[)') WITH &&)
);

-- Only accepted, completed work belongs here. Planned work is a separate F1/F2 concern.
CREATE TABLE vsm.service_event (
    scenario_id uuid NOT NULL,
    id uuid NOT NULL,
    train_id uuid NOT NULL,
    rule_set_id uuid NOT NULL,
    performed_cycle_code text NOT NULL,
    completed_at timestamptz NOT NULL,
    accepted_at timestamptz NOT NULL,
    actual_odometer_km bigint NOT NULL CHECK (actual_odometer_km >= 0),
    source text NOT NULL CHECK (btrim(source) <> ''),
    PRIMARY KEY (scenario_id, id),
    UNIQUE (scenario_id, id, rule_set_id),
    FOREIGN KEY (scenario_id, train_id) REFERENCES vsm.train(scenario_id, id),
    FOREIGN KEY (rule_set_id, performed_cycle_code) REFERENCES vsm.cycle_rule(rule_set_id, code),
    CHECK (accepted_at >= completed_at)
);

-- Keep historical rule versions: they need not match the current scenario rule set.
-- An early IS200 can be performed at 24000 km and credit the nominal 25000 km milestone.
CREATE TABLE vsm.service_credit (
    scenario_id uuid NOT NULL,
    service_event_id uuid NOT NULL,
    rule_set_id uuid NOT NULL,
    covered_cycle_code text NOT NULL,
    credited_nominal_km bigint NOT NULL CHECK (credited_nominal_km >= 0),
    source text NOT NULL CHECK (btrim(source) <> ''),
    PRIMARY KEY (scenario_id, service_event_id, covered_cycle_code),
    FOREIGN KEY (scenario_id, service_event_id, rule_set_id) REFERENCES vsm.service_event(scenario_id, id, rule_set_id),
    FOREIGN KEY (rule_set_id, covered_cycle_code) REFERENCES vsm.cycle_rule(rule_set_id, code)
);

CREATE INDEX service_event_train_time ON vsm.service_event(scenario_id, train_id, accepted_at);

-- Versions are append-only, including their rules. Corrections require a new version.
CREATE FUNCTION vsm.reject_mutation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; create a new version', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;
CREATE TRIGGER immutable_rule_set BEFORE UPDATE OR DELETE ON vsm.rule_set
    FOR EACH ROW EXECUTE FUNCTION vsm.reject_mutation();
CREATE TRIGGER immutable_cycle_rule BEFORE UPDATE OR DELETE ON vsm.cycle_rule
    FOR EACH ROW EXECUTE FUNCTION vsm.reject_mutation();

CREATE FUNCTION vsm.guard_rule_extension() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    -- Serialize the check with concurrent references to the rule set.
    PERFORM 1 FROM vsm.rule_set WHERE id = NEW.rule_set_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM vsm.scenario WHERE rule_set_id = NEW.rule_set_id)
       OR EXISTS (SELECT 1 FROM vsm.service_event WHERE rule_set_id = NEW.rule_set_id)
       OR EXISTS (SELECT 1 FROM vsm.cycle_baseline WHERE rule_set_id = NEW.rule_set_id) THEN
        RAISE EXCEPTION 'rule set is in use; create a new version' USING ERRCODE = '55000';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER no_extend_used_rules BEFORE INSERT ON vsm.cycle_rule
    FOR EACH ROW EXECUTE FUNCTION vsm.guard_rule_extension();
CREATE TRIGGER no_truncate_rule_set BEFORE TRUNCATE ON vsm.rule_set
    FOR EACH STATEMENT EXECUTE FUNCTION vsm.reject_mutation();
CREATE TRIGGER no_truncate_cycle_rule BEFORE TRUNCATE ON vsm.cycle_rule
    FOR EACH STATEMENT EXECUTE FUNCTION vsm.reject_mutation();
