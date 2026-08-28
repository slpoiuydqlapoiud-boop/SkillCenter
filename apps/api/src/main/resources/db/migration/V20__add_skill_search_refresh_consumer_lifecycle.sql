ALTER TABLE skill_search_refresh_event_consumers
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN last_seen_at TIMESTAMPTZ,
    ADD COLUMN retired_at TIMESTAMPTZ;

UPDATE skill_search_refresh_event_consumers
SET last_seen_at = updated_at
WHERE last_seen_at IS NULL;

ALTER TABLE skill_search_refresh_event_consumers
    ALTER COLUMN last_seen_at SET NOT NULL,
    ADD CONSTRAINT skill_search_refresh_event_consumers_status_valid
        CHECK (status IN ('ACTIVE', 'RETIRED')),
    ADD CONSTRAINT skill_search_refresh_event_consumers_retirement_consistent
        CHECK ((status = 'ACTIVE' AND retired_at IS NULL)
            OR (status = 'RETIRED' AND retired_at IS NOT NULL));

CREATE INDEX skill_search_refresh_event_consumers_lifecycle
    ON skill_search_refresh_event_consumers (status, last_seen_at, last_event_seq);
