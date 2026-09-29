CREATE TABLE vsm.api_scenario (
    id uuid PRIMARY KEY,
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object'),
    created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE vsm.planning_job (
    id uuid PRIMARY KEY,
    scenario_id uuid NOT NULL REFERENCES vsm.api_scenario(id),
    source_snapshot_id uuid REFERENCES vsm.scenario_snapshot(id),
    request jsonb NOT NULL,
    actor text NOT NULL,
    idempotency_key text,
    request_hash text GENERATED ALWAYS AS (vsm.canonical_sha256(request)) STORED,
    status text NOT NULL CHECK (status IN ('QUEUED','RUNNING','SUCCEEDED','FAILED')),
    solver_status text,
    plan_id uuid,
    error text,
    lease_owner uuid,
    lease_until timestamptz,
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts>=0),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (actor,idempotency_key)
);
CREATE INDEX planning_queue ON vsm.planning_job(status,created_at);
CREATE TABLE vsm.plan (
    id uuid PRIMARY KEY,
    scenario_id uuid NOT NULL REFERENCES vsm.api_scenario(id),
    source_snapshot_id uuid REFERENCES vsm.scenario_snapshot(id),
    snapshot_hash text NOT NULL,
    version integer NOT NULL CHECK (version>=0),
    status text NOT NULL CHECK (status IN ('DRAFT','APPROVED')),
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    approved_at timestamptz
);
ALTER TABLE vsm.planning_job ADD FOREIGN KEY (plan_id) REFERENCES vsm.plan(id);
ALTER TABLE vsm.request_event ADD FOREIGN KEY (job_id) REFERENCES vsm.planning_job(id);
ALTER TABLE vsm.request_event ADD FOREIGN KEY (plan_id) REFERENCES vsm.plan(id);
CREATE TABLE vsm.plan_selection (
    root_id uuid PRIMARY KEY,
    latest_draft_id uuid REFERENCES vsm.plan(id),
    effective_plan_id uuid REFERENCES vsm.plan(id),
    updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE vsm.plan_history (
    plan_id uuid NOT NULL REFERENCES vsm.plan(id),
    version integer NOT NULL,
    actor text NOT NULL,
    reason text NOT NULL,
    payload jsonb NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (plan_id,version)
);
CREATE TRIGGER immutable_plan_history BEFORE UPDATE OR DELETE ON vsm.plan_history
    FOR EACH ROW EXECUTE FUNCTION vsm.reject_mutation();
CREATE TRIGGER no_truncate_plan_history BEFORE TRUNCATE ON vsm.plan_history
    FOR EACH STATEMENT EXECUTE FUNCTION vsm.reject_mutation();
CREATE TABLE vsm.incident_message (
    id uuid PRIMARY KEY,
    payload jsonb NOT NULL,
    reported_at timestamptz NOT NULL DEFAULT now()
);
CREATE TRIGGER immutable_incident_message BEFORE UPDATE OR DELETE ON vsm.incident_message
    FOR EACH ROW EXECUTE FUNCTION vsm.reject_mutation();
CREATE TRIGGER no_truncate_incident_message BEFORE TRUNCATE ON vsm.incident_message
    FOR EACH STATEMENT EXECUTE FUNCTION vsm.reject_mutation();
