-- Presentation fields are independent of immutable operational facts.
ALTER TABLE vsm.change_request ADD COLUMN number bigint GENERATED ALWAYS AS IDENTITY UNIQUE;
CREATE TABLE vsm.request_client_metadata (
    request_id uuid PRIMARY KEY REFERENCES vsm.change_request(id),
    client_request_id text NOT NULL,
    base_snapshot_hash text NOT NULL,
    body jsonb NOT NULL CHECK (jsonb_typeof(body)='object'),
    body_hash text GENERATED ALWAYS AS (vsm.canonical_sha256(body)) STORED
);
CREATE TRIGGER append_only BEFORE UPDATE OR DELETE ON vsm.request_client_metadata
    FOR EACH ROW EXECUTE FUNCTION vsm.reject_mutation();
CREATE TRIGGER no_truncate BEFORE TRUNCATE ON vsm.request_client_metadata
    FOR EACH STATEMENT EXECUTE FUNCTION vsm.reject_mutation();
ALTER TABLE vsm.urgent_work_requirement DROP CONSTRAINT urgent_work_requirement_urgency_check;
ALTER TABLE vsm.urgent_work_requirement ADD CHECK
    (urgency IN ('CRITICAL','HIGH','NORMAL','IMMEDIATE','WITHIN_24H','WITHIN_HORIZON'));
