CREATE TABLE release_records (
    release_id text PRIMARY KEY,
    skill_id text NOT NULL,
    version text NOT NULL,
    sha256 text NOT NULL,
    target_environment text NOT NULL,
    gate_snapshot jsonb NOT NULL,
    source_assessment_id text NOT NULL,
    rollback_of_release_id text NOT NULL,
    rollback_target_version text NOT NULL,
    rollback_target_release_id text NOT NULL,
    rollback_assessment_id text NOT NULL,
    idempotency_key text NOT NULL,
    status text NOT NULL,
    requested_by text NOT NULL,
    requested_at timestamptz NOT NULL,
    approved_by text NOT NULL,
    approved_at timestamptz,
    status_reason text NOT NULL,
    target_reference text NOT NULL,
    started_at timestamptz,
    completed_at timestamptz,
    updated_by text NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT release_records_idempotency_key_key UNIQUE (idempotency_key),
    CONSTRAINT release_records_gate_snapshot_object CHECK (jsonb_typeof(gate_snapshot) = 'object'),
    CONSTRAINT release_records_target_environment_check CHECK (target_environment IN ('STAGING', 'PRODUCTION')),
    CONSTRAINT release_records_status_check CHECK (status IN (
        'REQUESTED', 'APPROVED', 'PROMOTING', 'PROMOTED', 'REJECTED',
        'ROLLBACK_REVIEW', 'ROLLING_BACK', 'ROLLED_BACK', 'FAILED'))
);

CREATE UNIQUE INDEX release_records_active_business_key_key
    ON release_records (skill_id, version, target_environment)
    WHERE status NOT IN ('PROMOTED', 'REJECTED', 'ROLLED_BACK', 'FAILED');
