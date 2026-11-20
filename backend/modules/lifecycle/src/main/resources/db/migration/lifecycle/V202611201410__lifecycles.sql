-- Lifecycle templates (roadmap 1.6, ADR 0017).
--
--   lifecycle_template    a named state machine, e.g. "Basic"
--   lifecycle_state       its states; `editable` = may be checked out, `revisable` = may be revised from
--   lifecycle_transition  allowed state changes (set state follows these unless a site admin overrides)
--   type_lifecycle        which template a type uses; subtypes inherit it

create table lifecycle_template (
    id            uuid primary key,
    tenant_id     uuid        not null references tenant (id),
    name          text        not null,
    labels        jsonb       not null,
    initial_state text        not null,
    system        boolean     not null default false,
    created_at    timestamptz not null default now(),
    unique (tenant_id, name)
);

create table lifecycle_state (
    template_id uuid    not null references lifecycle_template (id),
    key         text    not null check (key ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    labels      jsonb   not null,
    editable    boolean not null default false,
    revisable   boolean not null default false,
    sort_order  int     not null,
    primary key (template_id, key)
);

create table lifecycle_transition (
    template_id uuid not null,
    from_state  text not null,
    to_state    text not null,
    primary key (template_id, from_state, to_state),
    foreign key (template_id, from_state) references lifecycle_state (template_id, key),
    foreign key (template_id, to_state) references lifecycle_state (template_id, key),
    check (from_state <> to_state)
);

create table type_lifecycle (
    type_id     uuid primary key references type_def (id),
    tenant_id   uuid not null references tenant (id),
    template_id uuid not null references lifecycle_template (id)
);

-- Out of the box: "Basic", used by Part and Document.
--   In Work -> Under Review -> Released -> Obsolete, with Canceled as a side exit.
--   Only In Work is editable; only Released can be revised.
insert into lifecycle_template (id, tenant_id, name, labels, initial_state, system)
values ('00000000-0000-7000-8000-000000000301', '00000000-0000-7000-8000-000000000001', 'Basic',
        '{"en": "Basic"}', 'IN_WORK', true);

insert into lifecycle_state (template_id, key, labels, editable, revisable, sort_order)
values ('00000000-0000-7000-8000-000000000301', 'IN_WORK', '{"en": "In Work"}', true, false, 1),
       ('00000000-0000-7000-8000-000000000301', 'UNDER_REVIEW', '{"en": "Under Review"}', false, false, 2),
       ('00000000-0000-7000-8000-000000000301', 'RELEASED', '{"en": "Released"}', false, true, 3),
       ('00000000-0000-7000-8000-000000000301', 'OBSOLETE', '{"en": "Obsolete"}', false, false, 4),
       ('00000000-0000-7000-8000-000000000301', 'CANCELED', '{"en": "Canceled"}', false, false, 5);

insert into lifecycle_transition (template_id, from_state, to_state)
values ('00000000-0000-7000-8000-000000000301', 'IN_WORK', 'UNDER_REVIEW'),
       ('00000000-0000-7000-8000-000000000301', 'UNDER_REVIEW', 'IN_WORK'),
       ('00000000-0000-7000-8000-000000000301', 'UNDER_REVIEW', 'RELEASED'),
       ('00000000-0000-7000-8000-000000000301', 'IN_WORK', 'CANCELED'),
       ('00000000-0000-7000-8000-000000000301', 'RELEASED', 'OBSOLETE');

insert into type_lifecycle (type_id, tenant_id, template_id)
values ('00000000-0000-7000-8000-000000000101', '00000000-0000-7000-8000-000000000001', '00000000-0000-7000-8000-000000000301'),
       ('00000000-0000-7000-8000-000000000102', '00000000-0000-7000-8000-000000000001', '00000000-0000-7000-8000-000000000301');
