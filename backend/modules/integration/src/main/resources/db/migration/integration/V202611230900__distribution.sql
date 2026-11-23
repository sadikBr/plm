-- Roadmap 9.5: distribution to ERP systems. A target is an ERP system and plant, with the
-- plant-specific attributes it needs; rules say which objects go there when they reach a state;
-- messages wait in an outbox until sent (or fetched) and acknowledged.
create table distribution_target (
    id          uuid primary key,
    tenant_id   uuid        not null references tenant (id),
    name        text        not null,
    system      text        not null,
    plant       text        not null,
    -- Where messages are POSTed; null: the ERP fetches them.
    endpoint    text,
    secret      text        not null,
    -- [{name, label, required, defaultValue}]
    attributes  jsonb       not null default '[]',
    active      boolean     not null default true,
    created_at  timestamptz not null,
    unique (tenant_id, name)
);

create table distribution_rule (
    id         uuid primary key,
    target_id  uuid not null references distribution_target (id) on delete cascade,
    type_id    uuid not null,
    -- null: any context
    context_id uuid,
    state      text not null default 'RELEASED'
);

-- An object's standing at a target: its plant values and where its latest message is.
create table object_distribution (
    tenant_id    uuid        not null,
    object_id    uuid        not null,
    target_id    uuid        not null references distribution_target (id) on delete cascade,
    plant_values jsonb       not null default '{}',
    status       text        not null check (status in ('NOT_SENT', 'PENDING', 'SENT', 'ACKNOWLEDGED', 'ERROR')),
    version      text,
    erp_key      text,
    message      text,
    updated_at   timestamptz not null,
    primary key (object_id, target_id)
);

create table distribution_message (
    id           uuid primary key,
    tenant_id    uuid        not null,
    target_id    uuid        not null references distribution_target (id) on delete cascade,
    object_id    uuid        not null,
    version      text        not null,
    payload      jsonb       not null,
    status       text        not null check (status in ('PENDING', 'SENT', 'ACKNOWLEDGED', 'REJECTED', 'FAILED')),
    attempts     int         not null default 0,
    next_attempt timestamptz not null,
    last_error   text,
    created_at   timestamptz not null,
    sent_at      timestamptz,
    answered_at  timestamptz,
    answer       text
);
create index distribution_message_due on distribution_message (status, next_attempt);
create index distribution_message_object on distribution_message (object_id, created_at desc);
