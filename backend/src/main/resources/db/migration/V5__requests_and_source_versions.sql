-- A source version is a separate scenario. Neither old facts nor old snapshots are overwritten.
CREATE TABLE vsm.scenario_version (
    scenario_id uuid PRIMARY KEY REFERENCES vsm.scenario(id),
    root_id uuid NOT NULL REFERENCES vsm.scenario(id),
    version integer NOT NULL CHECK (version >= 0),
    parent_scenario_id uuid REFERENCES vsm.scenario(id),
    snapshot_id uuid NOT NULL UNIQUE REFERENCES vsm.scenario_snapshot(id),
    created_by text NOT NULL CHECK (btrim(created_by) <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE (root_id, version),
    CHECK ((version=0 AND parent_scenario_id IS NULL AND root_id=scenario_id)
        OR (version>0 AND parent_scenario_id IS NOT NULL))
);
CREATE TABLE vsm.scenario_head (
    root_id uuid PRIMARY KEY REFERENCES vsm.scenario(id),
    scenario_id uuid NOT NULL UNIQUE REFERENCES vsm.scenario_version(scenario_id),
    version integer NOT NULL CHECK (version>=0)
);
CREATE TABLE vsm.change_request (
    id uuid PRIMARY KEY,
    root_id uuid NOT NULL REFERENCES vsm.scenario(id),
    base_scenario_id uuid NOT NULL REFERENCES vsm.scenario_version(scenario_id),
    new_scenario_id uuid NOT NULL UNIQUE REFERENCES vsm.scenario_version(scenario_id),
    snapshot_id uuid NOT NULL REFERENCES vsm.scenario_snapshot(id),
    kind text NOT NULL CHECK (kind IN ('TRIP_CHANGE','TRIP_ADD','TRIP_CANCEL','URGENT_MAINTENANCE','RESOURCE_OUTAGE','RULE_CHANGE')),
    train_id uuid,
    trip_id uuid,
    reason text NOT NULL CHECK (btrim(reason)<>''),
    source text NOT NULL CHECK (btrim(source)<>''),
    reported_by text NOT NULL CHECK (btrim(reported_by)<>''),
    reported_at timestamptz NOT NULL DEFAULT now(),
    idempotency_key text NOT NULL CHECK (btrim(idempotency_key)<>''),
    command jsonb NOT NULL CHECK (jsonb_typeof(command)='object'),
    command_hash text GENERATED ALWAYS AS (vsm.canonical_sha256(command)) STORED,
    UNIQUE (reported_by,idempotency_key)
);
CREATE TABLE vsm.request_event (
    sequence bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_id uuid NOT NULL REFERENCES vsm.change_request(id),
    status text NOT NULL CHECK (status IN ('RECEIVED','IN_CALCULATION','CALCULATED','VALIDATED','APPROVED','ERROR')),
    actor text NOT NULL CHECK (btrim(actor)<>''),
    job_id uuid,
    plan_id uuid,
    code text,
    message text,
    recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX request_event_latest ON vsm.request_event(request_id,sequence DESC);
CREATE TABLE vsm.urgent_work_requirement (
    scenario_id uuid NOT NULL,
    id uuid NOT NULL,
    train_id uuid NOT NULL,
    problem_type text NOT NULL CHECK (btrim(problem_type)<>''),
    detected_at timestamptz NOT NULL,
    not_before_current_trip_end boolean NOT NULL,
    earliest_start_at timestamptz NOT NULL,
    deadline_at timestamptz,
    urgency text CHECK (urgency IN ('CRITICAL','HIGH','NORMAL')),
    work_kind text NOT NULL CHECK (btrim(work_kind)<>''),
    source text NOT NULL CHECK (btrim(source)<>''),
    PRIMARY KEY (scenario_id,id),
    FOREIGN KEY (scenario_id,train_id) REFERENCES vsm.train(scenario_id,id),
    CHECK (earliest_start_at>=detected_at),
    CHECK (deadline_at IS NULL OR deadline_at>earliest_start_at),
    CHECK (deadline_at IS NOT NULL OR urgency IS NOT NULL)
);
CREATE TABLE vsm.resource_outage (
    scenario_id uuid NOT NULL,
    id uuid NOT NULL,
    resource_id text NOT NULL,
    starts_at timestamptz NOT NULL,
    ends_at timestamptz NOT NULL,
    source text NOT NULL CHECK (btrim(source)<>''),
    PRIMARY KEY (scenario_id,id),
    FOREIGN KEY (scenario_id,resource_id) REFERENCES vsm.resource(scenario_id,id),
    CHECK (ends_at>starts_at)
);
CREATE OR REPLACE FUNCTION vsm.source_payload(p_scenario_id uuid)
RETURNS jsonb LANGUAGE sql STABLE SET timezone='UTC' SET datestyle='ISO, YMD' AS $$
    SELECT vsm.source_payload_base(p_scenario_id) || jsonb_build_object(
        'trainPresence', COALESCE((SELECT jsonb_agg(to_jsonb(p) ORDER BY p.id) FROM vsm.train_presence p WHERE p.scenario_id=p_scenario_id),'[]'::jsonb),
        'trainOccupancy', COALESCE((SELECT jsonb_agg(to_jsonb(o) ORDER BY o.id) FROM vsm.train_occupancy o WHERE o.scenario_id=p_scenario_id),'[]'::jsonb),
        'cleaningCounters', COALESCE((SELECT jsonb_agg(to_jsonb(c) ORDER BY c.train_id,c.observed_at) FROM vsm.cleaning_counter c WHERE c.scenario_id=p_scenario_id),'[]'::jsonb),
        'frozenWork', COALESCE((SELECT jsonb_agg(to_jsonb(w) ORDER BY w.id) FROM vsm.frozen_work w WHERE w.scenario_id=p_scenario_id),'[]'::jsonb)
    ) || CASE WHEN EXISTS (SELECT 1 FROM vsm.urgent_work_requirement WHERE scenario_id=p_scenario_id)
        THEN jsonb_build_object('urgentWorkRequirements',(SELECT jsonb_agg(to_jsonb(w) ORDER BY w.id)
            FROM vsm.urgent_work_requirement w WHERE w.scenario_id=p_scenario_id)) ELSE '{}'::jsonb END
      || CASE WHEN EXISTS (SELECT 1 FROM vsm.resource_outage WHERE scenario_id=p_scenario_id)
        THEN jsonb_build_object('resourceOutages',(SELECT jsonb_agg(to_jsonb(o) ORDER BY o.id)
            FROM vsm.resource_outage o WHERE o.scenario_id=p_scenario_id)) ELSE '{}'::jsonb END;
$$;
CREATE FUNCTION vsm.guard_registered_source() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE sid uuid;
BEGIN
    IF TG_TABLE_NAME='scenario' THEN
        sid := CASE WHEN TG_OP='DELETE' THEN OLD.id ELSE NEW.id END;
    ELSE
        sid := CASE WHEN TG_OP='DELETE' THEN OLD.scenario_id ELSE NEW.scenario_id END;
    END IF;
    IF EXISTS (SELECT 1 FROM vsm.scenario_version WHERE scenario_id=sid) THEN
        RAISE EXCEPTION 'registered source is immutable; create a scenario version' USING ERRCODE='55000';
    END IF;
    IF TG_OP='UPDATE' AND TG_TABLE_NAME<>'scenario' THEN
        IF EXISTS (SELECT 1 FROM vsm.scenario_version WHERE scenario_id=OLD.scenario_id) THEN
            RAISE EXCEPTION 'registered source is immutable; create a scenario version' USING ERRCODE='55000';
        END IF;
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;
DO $$ DECLARE tab text; BEGIN
    FOREACH tab IN ARRAY ARRAY['scenario','train','odometer_reading','resource','resource_availability',
        'cycle_resource','cycle_baseline','fixed_trip','service_event','service_credit','train_presence',
        'train_occupancy','cleaning_counter','frozen_work','urgent_work_requirement','resource_outage'] LOOP
        EXECUTE format('CREATE TRIGGER registered_source BEFORE INSERT OR UPDATE OR DELETE ON vsm.%I FOR EACH ROW EXECUTE FUNCTION vsm.guard_registered_source()',tab);
        EXECUTE format('CREATE TRIGGER no_truncate_registered_source BEFORE TRUNCATE ON vsm.%I FOR EACH STATEMENT EXECUTE FUNCTION vsm.reject_mutation()',tab);
    END LOOP;
    FOREACH tab IN ARRAY ARRAY['scenario_version','change_request','request_event'] LOOP
        EXECUTE format('CREATE TRIGGER append_only BEFORE UPDATE OR DELETE ON vsm.%I FOR EACH ROW EXECUTE FUNCTION vsm.reject_mutation()',tab);
        EXECUTE format('CREATE TRIGGER no_truncate BEFORE TRUNCATE ON vsm.%I FOR EACH STATEMENT EXECUTE FUNCTION vsm.reject_mutation()',tab);
    END LOOP;
END $$;
