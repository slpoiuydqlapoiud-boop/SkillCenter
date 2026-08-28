CREATE INDEX skill_search_refresh_events_created_at_sequence
    ON skill_search_refresh_events (created_at, event_seq);
