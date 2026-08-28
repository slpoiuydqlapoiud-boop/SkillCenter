CREATE TABLE skill_optimization_experiments (
    experiment_id varchar(128) PRIMARY KEY,
    work_item_id varchar(128) NOT NULL,
    skill_id varchar(256) NOT NULL,
    status varchar(32) NOT NULL,
    updated_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    CONSTRAINT skill_optimization_experiments_payload_object
        CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX skill_optimization_experiments_lookup
    ON skill_optimization_experiments (skill_id, status, updated_at DESC);

CREATE UNIQUE INDEX skill_optimization_experiments_active_work_item
    ON skill_optimization_experiments (work_item_id)
    WHERE status IN ('QUEUED', 'RUNNING');

CREATE TABLE skill_optimization_experiment_observations (
    observation_id varchar(128) PRIMARY KEY,
    experiment_id varchar(128) NOT NULL,
    captured_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    CONSTRAINT skill_optimization_experiment_observations_payload_object
        CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX skill_optimization_experiment_observations_lookup
    ON skill_optimization_experiment_observations (experiment_id, captured_at DESC);

CREATE TABLE skill_optimization_experiment_assessments (
    assessment_id varchar(128) PRIMARY KEY,
    experiment_id varchar(128) NOT NULL,
    assessed_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    CONSTRAINT skill_optimization_experiment_assessments_payload_object
        CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX skill_optimization_experiment_assessments_lookup
    ON skill_optimization_experiment_assessments (experiment_id, assessed_at DESC);
