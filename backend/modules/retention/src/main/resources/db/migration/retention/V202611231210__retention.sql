-- Roadmap 9.8: retention rules (per type and state: after so long, purge old iterations or archive),
-- legal holds that freeze objects or whole contexts, and archives (self-contained, restorable).
create table retention_rule (
    id         uuid primary key,
    tenant_id  uuid        not null references tenant (id),
    name       text        not null,
    type_id    uuid        not null,
    -- null: any state
    state      text,
    -- How long since the latest check-in before the rule applies.
    age_days   int         not null check (age_days >= 0),
    action     text        not null check (action in ('PURGE_ITERATIONS', 'ARCHIVE')),
    active     boolean     not null default true,
    created_at timestamptz not null,
    created_by text        not null
);

create table legal_hold (
    id          uuid primary key,
    tenant_id   uuid        not null references tenant (id),
    name        text        not null,
    reason      text,
    created_at  timestamptz not null,
    created_by  text        not null,
    released_at timestamptz,
    released_by text
);

create table legal_hold_item (
    hold_id    uuid not null references legal_hold (id) on delete cascade,
    object_id  uuid,
    context_id uuid,
    check ((object_id is null) <> (context_id is null))
);
create index legal_hold_item_object on legal_hold_item (object_id);
create index legal_hold_item_context on legal_hold_item (context_id);

create table archive (
    id          uuid primary key,
    tenant_id   uuid        not null references tenant (id),
    object_id   uuid        not null,
    number      text        not null,
    name        text        not null,
    type_id     uuid        not null,
    context_id  uuid        not null,
    package     bytea       not null,
    iterations  int         not null,
    files       int         not null,
    archived_at timestamptz not null,
    archived_by text        not null,
    restored_at timestamptz,
    restored_by text
);
create index archive_number on archive (tenant_id, number);
