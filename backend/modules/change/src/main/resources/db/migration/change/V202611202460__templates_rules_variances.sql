-- Change templates, association rules and variances (roadmap 5.10).
create table change_template (
    id          uuid primary key,
    tenant_id   uuid        not null,
    name        text        not null,
    type_id     uuid        not null,
    title       text,
    description text,
    -- For a change notice: the tasks it starts with.
    tasks       jsonb       not null default '[]',
    active      boolean     not null default true,
    updated_at  timestamptz not null,
    unique (tenant_id, name)
);

-- When a change type has rules for a role, only the listed target types (and subtypes) may be linked.
create table change_association_rule (
    id             uuid primary key,
    tenant_id      uuid        not null,
    change_type_id uuid        not null,
    role           text        not null,
    target_type_id uuid        not null,
    created_at     timestamptz not null,
    unique (tenant_id, change_type_id, role, target_type_id)
);

create table variance (
    change_id   uuid primary key,
    tenant_id   uuid not null,
    kind        text not null check (kind in ('DEVIATION', 'WAIVER')),
    valid_until date,
    quantity    int check (quantity > 0),
    reason      text
);
