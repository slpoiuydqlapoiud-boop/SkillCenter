create table skill_lifecycle_projection_meta (
    projection_key text primary key,
    schema_version integer not null check (schema_version >= 0),
    revision bigint not null check (revision >= 0),
    source_sha256 text not null,
    source_generated_at timestamptz not null,
    imported_at timestamptz not null,
    skill_count integer not null check (skill_count >= 0),
    version_count integer not null check (version_count >= 0),
    release_count integer not null check (release_count >= 0),
    scope_count integer not null check (scope_count >= 0),
    relation_count integer not null check (relation_count >= 0)
);

create table skill_lifecycle_skill_projection (
    skill_id text primary key,
    latest_version text not null,
    latest_status text not null,
    version_count integer not null check (version_count >= 0),
    published_version_count integer not null check (published_version_count >= 0),
    active_release_count integer not null check (active_release_count >= 0),
    visibility text not null,
    owner_team_id text not null,
    scope_revision integer not null check (scope_revision >= 0),
    source_sha256 text not null,
    updated_at timestamptz not null
);

create table skill_lifecycle_version_projection (
    skill_id text not null,
    version text not null,
    package_id text not null,
    status text not null,
    sha256 text not null,
    size_bytes bigint not null check (size_bytes >= 0),
    uploaded_by text not null,
    uploaded_at timestamptz not null,
    published_at timestamptz null,
    risk_level text not null,
    source_sha256 text not null,
    updated_at timestamptz not null,
    primary key (skill_id, version),
    foreign key (skill_id) references skill_lifecycle_skill_projection (skill_id)
);

create table skill_lifecycle_release_projection (
    release_id text primary key,
    skill_id text not null,
    version text not null,
    target_environment text not null,
    status text not null,
    gate_outcome text not null,
    sha256 text not null,
    requested_at timestamptz not null,
    approved_at timestamptz null,
    updated_at timestamptz not null,
    source_assessment_id text not null,
    rollback_of_release_id text not null,
    rollback_target_version text not null,
    rollback_target_release_id text not null,
    foreign key (skill_id, version) references skill_lifecycle_version_projection (skill_id, version)
);

create index skill_lifecycle_release_projection_lookup_idx
    on skill_lifecycle_release_projection (skill_id, version, target_environment, release_id);

create table skill_lifecycle_scope_projection (
    skill_id text primary key,
    visibility text not null,
    owner_team_id text not null,
    maintainer_count integer not null check (maintainer_count >= 0),
    scope_revision integer not null check (scope_revision >= 0),
    declared_at timestamptz not null,
    updated_at timestamptz not null,
    foreign key (skill_id) references skill_lifecycle_skill_projection (skill_id)
);

create table skill_lifecycle_relation_projection (
    relation_id text primary key,
    source_skill_id text not null,
    source_version text not null,
    target_skill_id text not null,
    target_version text not null,
    relation_type text not null,
    status text not null,
    declared_at timestamptz not null,
    retired_at timestamptz null,
    foreign key (source_skill_id, source_version) references skill_lifecycle_version_projection (skill_id, version),
    foreign key (target_skill_id, target_version) references skill_lifecycle_version_projection (skill_id, version)
);

insert into skill_lifecycle_projection_meta (
    projection_key,
    schema_version,
    revision,
    source_sha256,
    source_generated_at,
    imported_at,
    skill_count,
    version_count,
    release_count,
    scope_count,
    relation_count
) values (
    'skill-lifecycle',
    2,
    0,
    repeat('0', 64),
    current_timestamp,
    current_timestamp,
    0,
    0,
    0,
    0,
    0
);
