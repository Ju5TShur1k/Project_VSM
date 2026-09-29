-- A planner's proposal waits for the dispatcher. Only an approved proposal becomes a
-- change_request (a new source version); a rejected one never touches source facts.
CREATE TABLE vsm.request_proposal (
    id uuid PRIMARY KEY,
    number bigint GENERATED ALWAYS AS IDENTITY UNIQUE,
    root_id uuid NOT NULL,
    client_request_id text NOT NULL,
    proposed_by text NOT NULL,
    proposed_at timestamptz NOT NULL DEFAULT now(),
    body jsonb NOT NULL CHECK (jsonb_typeof(body) = 'object'),
    UNIQUE (proposed_by, client_request_id)
);

CREATE TABLE vsm.request_review (
    proposal_id uuid PRIMARY KEY REFERENCES vsm.request_proposal(id),
    decision text NOT NULL CHECK (decision IN ('APPROVED', 'REJECTED')),
    reviewed_by text NOT NULL,
    reviewed_at timestamptz NOT NULL DEFAULT now(),
    comment text NOT NULL DEFAULT '',
    request_id uuid REFERENCES vsm.change_request(id)
);

-- An uploaded schedule CSV is one source version with many trip changes.
ALTER TABLE vsm.change_request DROP CONSTRAINT change_request_kind_check;
ALTER TABLE vsm.change_request ADD CONSTRAINT change_request_kind_check CHECK (kind IN
    ('TRIP_CHANGE','TRIP_ADD','TRIP_CANCEL','URGENT_MAINTENANCE','RESOURCE_OUTAGE','RULE_CHANGE',
     'TRAIN_RELEASE','URGENT_RULE_CHANGE','SCHEDULE_IMPORT'));

CREATE TRIGGER append_only BEFORE UPDATE OR DELETE ON vsm.request_proposal
    FOR EACH ROW EXECUTE FUNCTION vsm.reject_mutation();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON vsm.request_proposal
    FOR EACH STATEMENT EXECUTE FUNCTION vsm.reject_mutation();
CREATE TRIGGER append_only BEFORE UPDATE OR DELETE ON vsm.request_review
    FOR EACH ROW EXECUTE FUNCTION vsm.reject_mutation();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON vsm.request_review
    FOR EACH STATEMENT EXECUTE FUNCTION vsm.reject_mutation();
