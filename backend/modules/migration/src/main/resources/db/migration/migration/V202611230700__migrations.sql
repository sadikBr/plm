-- Roadmap 9.3: migrations from other PLM systems. A migration keeps its package (a zip with a
-- manifest.json and the files) and its mappings; each legacy object's outcome is kept so a run
-- that stopped can be resumed without bringing anything in twice.
create table migration (
    id          uuid primary key,
    tenant_id   uuid        not null references tenant (id),
    name        text        not null,
    context_id  uuid        not null,
    file_name   text        not null,
    package     bytea       not null,
    mappings    jsonb       not null default '{}',
    last_job_id uuid,
    created_at  timestamptz not null,
    created_by  text        not null
);

create table migration_item (
    migration_id uuid        not null references migration (id) on delete cascade,
    legacy_id    text        not null,
    number       text        not null,
    master_id    uuid,
    -- DONE: the object and its history are in; FAILED: see message.
    status       text        not null check (status in ('DONE', 'FAILED')),
    links_done   boolean     not null default false,
    message      text,
    updated_at   timestamptz not null,
    primary key (migration_id, legacy_id)
);
