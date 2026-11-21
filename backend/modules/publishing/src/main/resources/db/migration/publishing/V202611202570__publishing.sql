-- Publishing (roadmap 6.7): which types publish a PDF, when; the PDFs published; watermarks.
create table publish_rule (
    id         uuid primary key,
    tenant_id  uuid        not null,
    name       text        not null,
    -- Type names (subtypes included); empty: every type.
    types      jsonb       not null default '[]',
    -- CHECK_IN, or STATE (when an object reaches [state]).
    trigger    text        not null check (trigger in ('CHECK_IN', 'STATE')),
    state      text,
    -- Stamp the pages with number, version and state.
    watermark  boolean     not null,
    active     boolean     not null,
    updated_at timestamptz not null,
    updated_by text        not null,
    unique (tenant_id, name)
);

create table publish_settings (
    tenant_id           uuid primary key,
    -- PDFs downloaded through the platform carry who downloaded them, and when.
    watermark_downloads boolean     not null,
    updated_at          timestamptz not null,
    updated_by          text        not null
);
insert into publish_settings (tenant_id, watermark_downloads, updated_at, updated_by)
select id, true, now(), 'system' from tenant;

create table publication (
    id           uuid primary key,
    tenant_id    uuid        not null,
    master_id    uuid        not null,
    iteration_id uuid        not null references object_iteration (id) on delete cascade,
    rule_id      uuid references publish_rule (id) on delete set null,
    -- QUEUED, READY, FAILED
    status       text        not null,
    -- FILE (its PDF, stamped) or DATASHEET (generated)
    source       text,
    file_name    text,
    pdf          bytea,
    pages        int,
    error        text,
    created_at   timestamptz not null,
    created_by   text        not null
);
create index publication_master on publication (master_id, created_at desc);
