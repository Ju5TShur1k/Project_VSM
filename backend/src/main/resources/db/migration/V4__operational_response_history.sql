-- Separate advisory execution history. D1 snapshots and fixed trips are never edited.
CREATE TABLE vsm.operational_session (
    id uuid PRIMARY KEY,
    source_snapshot_id uuid NOT NULL REFERENCES vsm.scenario_snapshot(id),
    current_version integer NOT NULL DEFAULT 0 CHECK (current_version >= 0),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE vsm.operational_revision (
    session_id uuid NOT NULL REFERENCES vsm.operational_session(id),
    version integer NOT NULL CHECK (version >= 0),
    actor text NOT NULL CHECK (btrim(actor) <> ''),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    state_hash text GENERATED ALWAYS AS (vsm.canonical_sha256(payload)) STORED,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (session_id, version)
);
CREATE TRIGGER immutable_operational_revision BEFORE UPDATE OR DELETE ON vsm.operational_revision
    FOR EACH ROW EXECUTE FUNCTION vsm.reject_mutation();
CREATE TRIGGER no_truncate_operational_revision BEFORE TRUNCATE ON vsm.operational_revision
    FOR EACH STATEMENT EXECUTE FUNCTION vsm.reject_mutation();
