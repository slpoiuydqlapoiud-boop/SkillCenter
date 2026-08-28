CREATE TABLE skill_optimization_work_items (
    work_item_id varchar(128) PRIMARY KEY,
    skill_id varchar(128) NOT NULL,
    source_version varchar(128) NOT NULL,
    suggestion_id varchar(128) NOT NULL,
    status varchar(32) NOT NULL,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    payload jsonb NOT NULL
);

CREATE INDEX skill_optimization_work_items_skill_version_idx
    ON skill_optimization_work_items (skill_id, source_version, updated_at DESC);

CREATE INDEX skill_optimization_work_items_status_idx
    ON skill_optimization_work_items (status, updated_at DESC);

CREATE UNIQUE INDEX skill_optimization_work_items_active_business_key_key
    ON skill_optimization_work_items (skill_id, source_version, suggestion_id)
    WHERE status NOT IN ('COMPLETED', 'ABANDONED');
