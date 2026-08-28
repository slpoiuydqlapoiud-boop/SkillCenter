CREATE TABLE skill_benchmarks (
    benchmark_id varchar(128) PRIMARY KEY,
    skill_id varchar(256) NOT NULL,
    experiment_id varchar(128),
    created_at timestamptz NOT NULL,
    payload jsonb NOT NULL,
    CONSTRAINT skill_benchmarks_payload_object
        CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX skill_benchmarks_lookup
    ON skill_benchmarks (skill_id, created_at DESC, benchmark_id);

CREATE UNIQUE INDEX skill_benchmarks_experiment
    ON skill_benchmarks (experiment_id)
    WHERE experiment_id IS NOT NULL;
