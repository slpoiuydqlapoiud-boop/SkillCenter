CREATE TABLE department_platform_state (
    state_key VARCHAR(64) NOT NULL,
    revision BIGINT NOT NULL,
    state JSON NOT NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (state_key),
    CONSTRAINT department_platform_state_revision_nonnegative CHECK (revision >= 0)
);
