CREATE TABLE skill_search_refresh_event_consumers (
    consumer_id VARCHAR(128) NOT NULL,
    last_event_seq BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT skill_search_refresh_event_consumers_pk PRIMARY KEY (consumer_id),
    CONSTRAINT skill_search_refresh_event_consumers_seq_nonnegative CHECK (last_event_seq >= 0)
);
