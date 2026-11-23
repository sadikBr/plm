-- Roadmap 9.7: security labels (export control, proprietary...) with ordered values, set on
-- objects; people hold clearances; agreements grant named people time-limited access to named
-- objects or contexts. Checked in every access check and list filter (DefaultAccessService).
create table security_label (
    id          uuid primary key,
    tenant_id   uuid        not null references tenant (id),
    key         text        not null,
    name        text        not null,
    description text,
    -- [{key, label}], least restrictive first; values are only ever appended.
    "values"    jsonb       not null,
    created_at  timestamptz not null,
    unique (tenant_id, key)
);

-- An object's value for a label; rank = the value's position (1 = least restrictive).
create table object_label (
    -- No foreign key: objects live in the versioning module (as with object shares).
    object_id  uuid        not null,
    label_id   uuid        not null references security_label (id) on delete cascade,
    tenant_id  uuid        not null,
    value_key  text        not null,
    value_rank int         not null,
    set_at     timestamptz not null,
    set_by     text        not null,
    primary key (object_id, label_id)
);
create index object_label_label on object_label (label_id);

-- Cleared up to a value (and everything below it).
create table clearance (
    tenant_id    uuid        not null,
    principal_id text        not null,
    label_id     uuid        not null references security_label (id) on delete cascade,
    value_key    text        not null,
    value_rank   int         not null,
    expires_at   timestamptz,
    granted_at   timestamptz not null,
    granted_by   text        not null,
    primary key (principal_id, label_id)
);

create table agreement (
    id          uuid primary key,
    tenant_id   uuid        not null references tenant (id),
    name        text        not null,
    description text,
    label_id    uuid        not null references security_label (id) on delete cascade,
    value_key   text        not null,
    value_rank  int         not null,
    valid_from  timestamptz not null,
    valid_to    timestamptz,
    active      boolean     not null default true,
    created_at  timestamptz not null,
    created_by  text        not null
);

create table agreement_principal (
    agreement_id uuid not null references agreement (id) on delete cascade,
    principal_id text not null,
    primary key (agreement_id, principal_id)
);

-- What it covers: objects, or contexts (and everything below them).
create table agreement_scope (
    agreement_id uuid not null references agreement (id) on delete cascade,
    object_id    uuid,
    context_id   uuid,
    check ((object_id is null) <> (context_id is null))
);
create index agreement_scope_agreement on agreement_scope (agreement_id);
