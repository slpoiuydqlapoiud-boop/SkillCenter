CREATE TABLE skill_governance_version (
    package_id text PRIMARY KEY,
    skill_id text NOT NULL,
    version text NOT NULL,
    status text NOT NULL,
    sha256 text NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    artifact_path text NOT NULL,
    uploaded_by text NOT NULL,
    uploaded_at timestamptz,
    published_by text NOT NULL,
    published_at timestamptz,
    review_id text,
    status_reason text NOT NULL,
    replacement_version text NOT NULL,
    status_changed_by text NOT NULL,
    status_changed_at timestamptz,
    risk_level text NOT NULL,
    security_evidence jsonb NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT skill_governance_version_identity UNIQUE (skill_id, version),
    CONSTRAINT skill_governance_version_security_object
        CHECK (jsonb_typeof(security_evidence) = 'object')
);

CREATE INDEX skill_governance_version_lookup_idx
    ON skill_governance_version (skill_id, status, version);
