-- The release gate (roadmap 5.13): parts and documents reach the gated states only through an
-- approved change notice or promotion request. On by default.
create table release_gate (
    tenant_id  uuid primary key,
    enabled    boolean     not null,
    states     jsonb       not null,
    updated_at timestamptz not null,
    updated_by text        not null
);
insert into release_gate (tenant_id, enabled, states, updated_at, updated_by)
select id, true, '["RELEASED"]', now(), 'system' from tenant;
