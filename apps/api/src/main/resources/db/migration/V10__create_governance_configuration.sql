CREATE TABLE skill_governance_team (
    team_id text PRIMARY KEY,
    name text NOT NULL,
    description text,
    owner_user_id text,
    member_user_ids jsonb NOT NULL,
    status text NOT NULL,
    created_at timestamptz,
    updated_at timestamptz,
    CONSTRAINT skill_governance_team_members_array
        CHECK (jsonb_typeof(member_user_ids) = 'array')
);

CREATE INDEX skill_governance_team_status_idx
    ON skill_governance_team (status, team_id);

CREATE TABLE skill_governance_role_binding (
    user_id text PRIMARY KEY,
    role text NOT NULL,
    team_id text,
    status text NOT NULL,
    changed_by text,
    changed_at timestamptz
);

CREATE INDEX skill_governance_role_binding_team_idx
    ON skill_governance_role_binding (team_id, status, role);

CREATE TABLE skill_governance_category (
    code text PRIMARY KEY,
    display_name text NOT NULL,
    description text,
    sort_order integer NOT NULL,
    status text NOT NULL,
    updated_by text,
    updated_at timestamptz
);

CREATE INDEX skill_governance_category_status_idx
    ON skill_governance_category (status, sort_order, code);

CREATE TABLE skill_governance_tag (
    code text PRIMARY KEY,
    display_name text NOT NULL,
    description text,
    sort_order integer NOT NULL,
    status text NOT NULL,
    updated_by text,
    updated_at timestamptz
);

CREATE INDEX skill_governance_tag_status_idx
    ON skill_governance_tag (status, sort_order, code);

CREATE TABLE skill_governance_collection (
    collection_id text PRIMARY KEY,
    name text NOT NULL,
    description text,
    owner_team_id text,
    visibility text NOT NULL,
    skill_ids jsonb NOT NULL,
    sort_order integer NOT NULL,
    status text NOT NULL,
    updated_by text,
    updated_at timestamptz,
    CONSTRAINT skill_governance_collection_skills_array
        CHECK (jsonb_typeof(skill_ids) = 'array')
);

CREATE INDEX skill_governance_collection_status_idx
    ON skill_governance_collection (status, sort_order, collection_id);

CREATE TABLE skill_governance_policy (
    policy_key text PRIMARY KEY,
    policy_version integer NOT NULL,
    page_size_options jsonb NOT NULL,
    max_page_size integer NOT NULL,
    minimum_client_version text NOT NULL,
    default_collection_visibility text NOT NULL,
    updated_by text,
    updated_at timestamptz,
    CONSTRAINT skill_governance_policy_options_array
        CHECK (jsonb_typeof(page_size_options) = 'array')
);
