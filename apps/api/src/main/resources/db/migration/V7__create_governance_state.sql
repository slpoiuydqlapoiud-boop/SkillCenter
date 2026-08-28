CREATE TABLE skill_governance_state (
    state_key varchar(64) PRIMARY KEY,
    state jsonb NOT NULL,
    revision bigint NOT NULL CHECK (revision >= 0),
    updated_at timestamptz NOT NULL,
    CONSTRAINT skill_governance_state_object CHECK (jsonb_typeof(state) = 'object')
);
