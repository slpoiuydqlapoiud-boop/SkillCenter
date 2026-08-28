CREATE TABLE skill_governance_review (
    review_id text PRIMARY KEY,
    package_id text NOT NULL,
    skill_id text NOT NULL,
    version text NOT NULL,
    status text NOT NULL,
    submitted_by text NOT NULL,
    submitted_at timestamptz,
    reviewed_by text,
    reviewed_at timestamptz,
    reason text,
    risk_level text NOT NULL,
    security_reviewed_by text,
    security_reviewed_at timestamptz,
    security_reason text,
    security_evidence jsonb NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT skill_governance_review_security_object
        CHECK (jsonb_typeof(security_evidence) = 'object')
);

CREATE INDEX skill_governance_review_status_idx
    ON skill_governance_review (status, risk_level, submitted_at);

CREATE INDEX skill_governance_review_skill_version_idx
    ON skill_governance_review (skill_id, version);
