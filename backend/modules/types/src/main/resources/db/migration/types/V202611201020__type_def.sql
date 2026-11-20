-- Type definitions (ADR 0004). Built-in base types are seeded; admins add subtypes at runtime.
create table type_def (
    id           uuid primary key,
    tenant_id    uuid        not null references tenant (id),
    parent_id    uuid references type_def (id),
    name         text        not null,
    display_name text        not null,
    base_class   text        not null check (base_class in ('DOCUMENT', 'PART')),
    attr_schema  jsonb       not null default '{}',
    created_at   timestamptz not null default now(),
    unique (tenant_id, name)
);

create index type_def_parent_idx on type_def (parent_id);

insert into type_def (id, tenant_id, parent_id, name, display_name, base_class)
values ('00000000-0000-7000-8000-000000000101', '00000000-0000-7000-8000-000000000001', null,
        'Document', 'Document', 'DOCUMENT'),
       ('00000000-0000-7000-8000-000000000102', '00000000-0000-7000-8000-000000000001', null,
        'Part', 'Part', 'PART');
