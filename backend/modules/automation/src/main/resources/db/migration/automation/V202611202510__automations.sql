-- No-code automations (roadmap 5.16): an event, a condition, actions; and what each run did.
create table automation (
    id          uuid primary key,
    tenant_id   uuid        not null,
    name        text        not null,
    description text,
    active      boolean     not null,
    -- The event type, e.g. versioning.ObjectStateChanged.v1.
    event       text        not null,
    -- Type names (subtypes included); empty: any.
    types       jsonb       not null default '[]',
    -- A CEL condition over event, object and user; null: always.
    condition   text,
    actions     jsonb       not null,
    updated_at  timestamptz not null,
    updated_by  text        not null,
    unique (tenant_id, name)
);

create table automation_run (
    id            uuid primary key,
    tenant_id     uuid        not null,
    automation_id uuid        not null references automation (id) on delete cascade,
    ran_at        timestamptz not null,
    event_id      uuid        not null,
    event_type    text        not null,
    object_id     uuid,
    object_number text,
    -- SUCCEEDED, FAILED (a step failed; later steps were skipped).
    outcome       text        not null,
    steps         jsonb       not null,
    duration_ms   int         not null
);
create index automation_run_idx on automation_run (automation_id, ran_at desc);
