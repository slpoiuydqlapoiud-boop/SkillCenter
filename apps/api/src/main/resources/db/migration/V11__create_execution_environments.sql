CREATE TABLE skill_execution_environment (
    environment_id text NOT NULL,
    kind text NOT NULL,
    version text NOT NULL,
    status text NOT NULL,
    capabilities jsonb NOT NULL,
    adapter_provider_id text NOT NULL,
    config_reference text NOT NULL,
    created_by text NOT NULL,
    created_at timestamptz NOT NULL,
    updated_by text NOT NULL,
    updated_at timestamptz NOT NULL,
    revision integer NOT NULL,
    payload jsonb NOT NULL,
    CONSTRAINT skill_execution_environment_pk PRIMARY KEY (kind, environment_id),
    CONSTRAINT skill_execution_environment_capabilities_array
        CHECK (jsonb_typeof(capabilities) = 'array'),
    CONSTRAINT skill_execution_environment_revision_positive
        CHECK (revision >= 1)
);

CREATE INDEX skill_execution_environment_status_idx
    ON skill_execution_environment (kind, status, environment_id);
