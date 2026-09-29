-- Task 1 only: explicit source facts. This migration grants no solver/D2 approval.
CREATE TABLE vsm.train_release (
    scenario_id uuid NOT NULL,
    id uuid NOT NULL,
    train_id uuid NOT NULL,
    frozen_work_id uuid,
    available_from timestamptz NOT NULL,
    location text NOT NULL CHECK (btrim(location) <> ''),
    basis text NOT NULL CHECK (basis IN ('ACCEPTED','FORECAST','DEMO_ASSUMPTION')),
    accepted_at timestamptz,
    acceptance_document text,
    confirmation_status text NOT NULL,
    source text NOT NULL CHECK (btrim(source) <> ''),
    recorded_by text NOT NULL CHECK (btrim(recorded_by) <> ''),
    PRIMARY KEY (scenario_id,id),
    UNIQUE (scenario_id,train_id),
    FOREIGN KEY (scenario_id,train_id) REFERENCES vsm.train(scenario_id,id),
    FOREIGN KEY (scenario_id,frozen_work_id) REFERENCES vsm.frozen_work(scenario_id,id),
    CHECK ((basis='ACCEPTED' AND confirmation_status='CONFIRMED' AND accepted_at IS NOT NULL
            AND accepted_at<=available_from AND acceptance_document IS NOT NULL AND btrim(acceptance_document)<>'')
        OR (basis='FORECAST' AND confirmation_status='UNCONFIRMED' AND accepted_at IS NULL AND acceptance_document IS NULL)
        OR (basis='DEMO_ASSUMPTION' AND confirmation_status='SYNTHETIC' AND accepted_at IS NULL AND acceptance_document IS NULL))
);
CREATE TABLE vsm.urgent_work_rule (
    scenario_id uuid NOT NULL REFERENCES vsm.scenario(id),
    id uuid NOT NULL,
    work_kind text NOT NULL CHECK (btrim(work_kind)<>''),
    rule_version text NOT NULL CHECK (btrim(rule_version)<>''),
    duration_minutes integer NOT NULL CHECK (duration_minutes>0),
    confirmation_status text NOT NULL CHECK (confirmation_status IN ('CONFIRMED','UNCONFIRMED','SYNTHETIC')),
    source text NOT NULL CHECK (btrim(source)<>''),
    PRIMARY KEY (scenario_id,id),
    UNIQUE (scenario_id,work_kind)
);
CREATE TABLE vsm.urgent_work_rule_resource (
    scenario_id uuid NOT NULL,
    rule_id uuid NOT NULL,
    resource_id text NOT NULL,
    PRIMARY KEY (scenario_id,rule_id,resource_id),
    FOREIGN KEY (scenario_id,rule_id) REFERENCES vsm.urgent_work_rule(scenario_id,id),
    FOREIGN KEY (scenario_id,resource_id) REFERENCES vsm.resource(scenario_id,id)
);
CREATE FUNCTION vsm.check_train_release_work() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE work vsm.frozen_work; city text;
BEGIN
    IF NEW.frozen_work_id IS NOT NULL THEN
        SELECT * INTO STRICT work FROM vsm.frozen_work WHERE scenario_id=NEW.scenario_id AND id=NEW.frozen_work_id;
        SELECT location INTO city FROM vsm.resource WHERE scenario_id=NEW.scenario_id AND id=work.resource_id;
        IF work.train_id<>NEW.train_id OR NEW.available_from<work.ends_at OR NEW.location<>city
           OR (NEW.accepted_at IS NOT NULL AND NEW.accepted_at<work.ends_at) THEN
            RAISE EXCEPTION 'release must follow the named work of this train at its depot' USING ERRCODE='23514';
        END IF;
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER release_work BEFORE INSERT OR UPDATE ON vsm.train_release
    FOR EACH ROW EXECUTE FUNCTION vsm.check_train_release_work();
DO $$ DECLARE tab text; BEGIN
    FOREACH tab IN ARRAY ARRAY['train_release','urgent_work_rule','urgent_work_rule_resource'] LOOP
        EXECUTE format('CREATE TRIGGER registered_source BEFORE INSERT OR UPDATE OR DELETE ON vsm.%I FOR EACH ROW EXECUTE FUNCTION vsm.guard_registered_source()',tab);
        EXECUTE format('CREATE TRIGGER no_truncate_registered_source BEFORE TRUNCATE ON vsm.%I FOR EACH STATEMENT EXECUTE FUNCTION vsm.reject_mutation()',tab);
    END LOOP;
END $$;
ALTER TABLE vsm.change_request DROP CONSTRAINT change_request_kind_check;
ALTER TABLE vsm.change_request ADD CONSTRAINT change_request_kind_check CHECK (kind IN
    ('TRIP_CHANGE','TRIP_ADD','TRIP_CANCEL','URGENT_MAINTENANCE','RESOURCE_OUTAGE','RULE_CHANGE','TRAIN_RELEASE','URGENT_RULE_CHANGE'));

-- Preserve every historical stored snapshot and byte-for-byte output for sources
-- with no new facts. Existing Flyway migrations and their checksums stay intact.
ALTER FUNCTION vsm.source_payload(uuid) RENAME TO source_payload_before_e3_facts;
CREATE FUNCTION vsm.source_payload(p_scenario_id uuid) RETURNS jsonb
LANGUAGE sql STABLE SET timezone='UTC' SET datestyle='ISO, YMD' AS $$
    SELECT vsm.source_payload_before_e3_facts(p_scenario_id)
    || CASE WHEN EXISTS (SELECT 1 FROM vsm.train_release WHERE scenario_id=p_scenario_id)
       OR EXISTS (SELECT 1 FROM vsm.urgent_work_rule WHERE scenario_id=p_scenario_id)
       THEN jsonb_build_object('e3SourceFactsVersion','e3-source-facts-1.0') ELSE '{}'::jsonb END
    || CASE WHEN EXISTS (SELECT 1 FROM vsm.train_release WHERE scenario_id=p_scenario_id)
       THEN jsonb_build_object('trainReleases',(SELECT jsonb_agg(to_jsonb(r) ORDER BY r.train_id)
          FROM vsm.train_release r WHERE r.scenario_id=p_scenario_id)) ELSE '{}'::jsonb END
    || CASE WHEN EXISTS (SELECT 1 FROM vsm.urgent_work_rule WHERE scenario_id=p_scenario_id)
       THEN jsonb_build_object('urgentWorkRules',(SELECT jsonb_agg(to_jsonb(r) || jsonb_build_object(
          'resource_ids',COALESCE((SELECT jsonb_agg(m.resource_id ORDER BY m.resource_id COLLATE "C")
             FROM vsm.urgent_work_rule_resource m WHERE m.scenario_id=r.scenario_id AND m.rule_id=r.id),'[]'::jsonb))
          ORDER BY r.work_kind COLLATE "C") FROM vsm.urgent_work_rule r WHERE r.scenario_id=p_scenario_id))
       || CASE WHEN EXISTS (SELECT 1 FROM vsm.urgent_work_requirement WHERE scenario_id=p_scenario_id)
          THEN jsonb_build_object('urgentWorkRequirements',(SELECT jsonb_agg(to_jsonb(w) || jsonb_build_object(
             'rule_id',r.id,'rule_version',r.rule_version,'duration_minutes',r.duration_minutes,
             'resource_ids',COALESCE((SELECT jsonb_agg(m.resource_id ORDER BY m.resource_id COLLATE "C")
                FROM vsm.urgent_work_rule_resource m WHERE m.scenario_id=r.scenario_id AND m.rule_id=r.id),'[]'::jsonb),
             'rule_confirmation_status',r.confirmation_status,'rule_source',r.source,
             'rule_status',CASE WHEN r.id IS NULL THEN 'MISSING_RULE'
                WHEN r.confirmation_status='UNCONFIRMED' THEN 'UNCONFIRMED_RULE' ELSE 'READY' END,
             'latest_end_at',COALESCE(w.deadline_at,(SELECT horizon_end FROM vsm.scenario WHERE id=p_scenario_id)),
             'blocks_next_departure',COALESCE(w.urgency IN ('IMMEDIATE','CRITICAL'),false)) ORDER BY w.id)
             FROM vsm.urgent_work_requirement w LEFT JOIN vsm.urgent_work_rule r
               ON r.scenario_id=w.scenario_id AND r.work_kind=w.work_kind WHERE w.scenario_id=p_scenario_id))
          ELSE '{}'::jsonb END
       ELSE '{}'::jsonb END;
$$;
