CREATE TABLE skill_search_index_state (
    index_id VARCHAR(64) PRIMARY KEY,
    state VARCHAR(32) NOT NULL,
    revision BIGINT NOT NULL,
    source_hash VARCHAR(256) NOT NULL,
    indexed_at TIMESTAMPTZ,
    reason_code VARCHAR(128) NOT NULL DEFAULT '',
    CONSTRAINT skill_search_index_state_singleton CHECK (index_id = 'default'),
    CONSTRAINT skill_search_index_state_revision_nonnegative CHECK (revision >= 0)
);

CREATE TABLE skill_search_documents (
    skill_id VARCHAR(128) NOT NULL,
    name VARCHAR(512) NOT NULL,
    description VARCHAR(4000) NOT NULL,
    tags JSONB NOT NULL,
    team VARCHAR(256) NOT NULL,
    category VARCHAR(256) NOT NULL,
    status VARCHAR(32) NOT NULL,
    risk VARCHAR(32) NOT NULL,
    last_updated TIMESTAMPTZ,
    published_at TIMESTAMPTZ,
    latest_version VARCHAR(128) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    owner_team_id VARCHAR(128) NOT NULL,
    search_tokens TEXT NOT NULL,
    CONSTRAINT skill_search_documents_pk PRIMARY KEY (skill_id),
    CONSTRAINT skill_search_documents_tags_array CHECK (jsonb_typeof(tags) = 'array')
);

CREATE INDEX skill_search_documents_search_tokens
    ON skill_search_documents USING GIN (to_tsvector('simple', search_tokens));
CREATE INDEX skill_search_documents_filters
    ON skill_search_documents (category, status, risk);

INSERT INTO skill_search_index_state (index_id, state, revision, source_hash, indexed_at, reason_code)
VALUES ('default', 'NOT_READY', 0, '', NULL, '');
