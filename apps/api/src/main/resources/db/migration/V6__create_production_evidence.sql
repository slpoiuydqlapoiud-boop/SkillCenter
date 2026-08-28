CREATE TABLE skill_production_evidence (
    evidence_id varchar(64) PRIMARY KEY,
    status varchar(16) NOT NULL,
    owner_user_id varchar(80) NOT NULL,
    verified_at timestamptz,
    expires_at timestamptz,
    evidence_ref varchar(120) NOT NULL,
    summary varchar(240) NOT NULL,
    revision bigint NOT NULL,
    updated_by varchar(80) NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE INDEX skill_production_evidence_status_idx
    ON skill_production_evidence (status, expires_at);
