CREATE TABLE skill_quality_evidence_state (
    aggregate_key text PRIMARY KEY,
    document_schema_version integer NOT NULL,
    revision bigint NOT NULL,
    payload jsonb NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT skill_quality_evidence_state_revision_non_negative CHECK (revision >= 0),
    CONSTRAINT skill_quality_evidence_state_payload_object CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT skill_quality_evidence_state_singleton_key CHECK (aggregate_key = 'quality-evidence')
);
