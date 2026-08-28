CREATE TABLE skill_search_refresh_events (
    event_seq BIGSERIAL NOT NULL,
    event_id VARCHAR(512) NOT NULL,
    skill_id VARCHAR(128) NOT NULL,
    source_revision BIGINT NOT NULL,
    reason_code VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT skill_search_refresh_events_pk PRIMARY KEY (event_id),
    CONSTRAINT skill_search_refresh_events_seq_unique UNIQUE (event_seq),
    CONSTRAINT skill_search_refresh_events_revision_nonnegative CHECK (source_revision >= 0)
);

CREATE INDEX skill_search_refresh_events_sequence
    ON skill_search_refresh_events (event_seq);
