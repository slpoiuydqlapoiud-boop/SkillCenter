alter table skill_lifecycle_version_projection
    add column security_status text not null default 'NOT_SCANNED'
        check (security_status in ('PASSED', 'BLOCKED', 'NOT_SCANNED')),
    add column security_scanner_id text not null default 'legacy-compatible'
        check (security_scanner_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    add column security_scanner_version text not null default ''
        check (security_scanner_version = '' or security_scanner_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$');

create table skill_lifecycle_version_security_finding_projection (
    skill_id text not null,
    version text not null,
    finding_index integer not null check (finding_index >= 0),
    finding_code text not null check (finding_code ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    finding_path text not null check (length(finding_path) between 1 and 512 and finding_path !~ '[[:cntrl:]]'),
    finding_severity text not null check (finding_severity in ('INFO', 'LOW', 'MEDIUM', 'HIGH')),
    primary key (skill_id, version, finding_index),
    foreign key (skill_id, version) references skill_lifecycle_version_projection (skill_id, version)
);

create index skill_lifecycle_security_finding_lookup_idx
    on skill_lifecycle_version_security_finding_projection (skill_id, version, finding_index);

update skill_lifecycle_projection_meta
   set schema_version = 3
 where projection_key = 'skill-lifecycle';
