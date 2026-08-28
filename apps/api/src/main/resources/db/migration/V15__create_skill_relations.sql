CREATE TABLE skill_relations (
    relation_id varchar(128) PRIMARY KEY,
    source_skill_id varchar(128) NOT NULL,
    source_version varchar(512) NOT NULL,
    target_skill_id varchar(128) NOT NULL,
    target_version varchar(512) NOT NULL,
    relation_type varchar(32) NOT NULL,
    status varchar(32) NOT NULL,
    declared_by varchar(128) NOT NULL,
    declared_at timestamptz NOT NULL,
    retired_by varchar(128),
    retired_at timestamptz,
    status_reason varchar(512),
    CONSTRAINT skill_relations_distinct_endpoint CHECK (
        source_skill_id <> target_skill_id OR source_version <> target_version
    ),
    CONSTRAINT skill_relations_retirement_pair CHECK (
        (status = 'ACTIVE' AND retired_by IS NULL AND retired_at IS NULL)
        OR (status = 'RETIRED' AND retired_by IS NOT NULL AND retired_at IS NOT NULL)
    )
);

CREATE UNIQUE INDEX skill_relations_active_business_key
    ON skill_relations (source_skill_id, source_version, target_skill_id, target_version, relation_type)
    WHERE status = 'ACTIVE';
CREATE INDEX skill_relations_source_lookup
    ON skill_relations (source_skill_id, source_version, declared_at, relation_id);
CREATE INDEX skill_relations_target_lookup
    ON skill_relations (target_skill_id, target_version, declared_at, relation_id);
