-- Initialization rules per type (roadmap 1.5): numbering and revision schemes.

create table numbering_scheme (
    id         uuid primary key,
    tenant_id  uuid        not null references tenant (id),
    name       text        not null,
    prefix     text        not null default '',
    padding    int         not null check (padding between 1 and 18),
    suffix     text        not null default '',
    -- The counter. Incremented with UPDATE ... RETURNING, so concurrent creators serialize on the row.
    next_value bigint      not null check (next_value >= 0),
    created_at timestamptz not null default now(),
    unique (tenant_id, name)
);

-- Overrides per type; null columns inherit from the parent type.
create table type_rule (
    type_id             uuid primary key references type_def (id),
    tenant_id           uuid not null references tenant (id),
    numbering_scheme_id uuid references numbering_scheme (id),
    revision_scheme     text check (revision_scheme in ('ALPHABETIC_ASME', 'ALPHABETIC', 'NUMERIC_FROM_1', 'NUMERIC_FROM_0')),
    allow_manual_number boolean
);

-- Out of the box: ASME letter revisions, manual numbers allowed, the system 10-digit sequence.
insert into type_rule (type_id, tenant_id, revision_scheme, allow_manual_number)
values ('00000000-0000-7000-8000-000000000101', '00000000-0000-7000-8000-000000000001', 'ALPHABETIC_ASME', true),
       ('00000000-0000-7000-8000-000000000102', '00000000-0000-7000-8000-000000000001', 'ALPHABETIC_ASME', true);
