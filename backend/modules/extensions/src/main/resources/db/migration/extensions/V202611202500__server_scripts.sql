-- Sandboxed server scripts (roadmap 5.14): JavaScript for validation, numbering and automation.
create table server_script (
    id          uuid primary key,
    tenant_id   uuid        not null,
    name        text        not null,
    purpose     text        not null check (purpose in ('VALIDATION', 'NUMBERING', 'AUTOMATION')),
    description text,
    source      text        not null,
    -- Type names (subtypes included); empty: every type.
    types       jsonb       not null default '[]',
    -- For validation scripts: the operations, e.g. object.check-in.
    operations  jsonb       not null default '[]',
    active      boolean     not null,
    timeout_ms  int         not null,
    updated_at  timestamptz not null,
    updated_by  text        not null,
    unique (tenant_id, name)
);

-- Each run, for troubleshooting; old runs are trimmed.
create table server_script_run (
    id          uuid primary key,
    tenant_id   uuid        not null,
    script_id   uuid        not null references server_script (id) on delete cascade,
    ran_at      timestamptz not null,
    actor       text        not null,
    trigger     text        not null,
    ok          boolean     not null,
    duration_ms int         not null,
    instructions bigint     not null,
    result      jsonb,
    error       text,
    logs        jsonb       not null default '[]'
);
create index server_script_run_idx on server_script_run (script_id, ran_at desc);
