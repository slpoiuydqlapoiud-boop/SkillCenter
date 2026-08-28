CREATE TABLE skill_scopes (
    skill_id varchar(128) PRIMARY KEY,
    visibility varchar(32) NOT NULL,
    owner_team_id varchar(128),
    maintainer_user_ids jsonb NOT NULL,
    revision integer NOT NULL CONSTRAINT skill_scopes_revision CHECK (revision >= 1),
    declared_by varchar(128) NOT NULL,
    declared_at timestamptz NOT NULL,
    updated_by varchar(128) NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT skill_scopes_maintainers_array CHECK (jsonb_typeof(maintainer_user_ids) = 'array'),
    CONSTRAINT skill_scopes_updated_at CHECK (updated_at >= declared_at)
);

CREATE INDEX skill_scopes_owner_team ON skill_scopes (owner_team_id);
