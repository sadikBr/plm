-- Roadmap 1.16: type properties, dependent value lists, global attributes. (Admin-defined units use
-- the existing unit_of_measure table.)

-- Type properties. labels: translations of the display name ({"fr": "Pièce"}); display_name stays
-- the English one. Non-instantiable types are abstract: they group subtypes and can't be created.
alter table type_def add column description text;
alter table type_def add column labels jsonb not null default '{}';
alter table type_def add column icon text check (icon ~ '^[a-z][a-z-]{0,29}$');
alter table type_def add column instantiable boolean not null default true;

-- Dependent value lists: an ENUMERATION attribute whose allowed values depend on another
-- enumeration attribute's value. value_filter = {"<parent value key>": ["<allowed key>", ...]}.
alter table attribute_def add column depends_on text;
alter table attribute_def add column value_filter jsonb;

-- Global attributes: defined once (type_id null, global true) and used by any number of types.
alter table attribute_def add column global boolean not null default false;
alter table attribute_def alter column type_id drop not null;
alter table attribute_def add constraint attribute_def_global_check check (global = (type_id is null));
create unique index attribute_def_global_name_idx on attribute_def (tenant_id, lower(name)) where global;

create table attribute_usage (
    attribute_id uuid        not null references attribute_def (id),
    type_id      uuid        not null references type_def (id),
    tenant_id    uuid        not null references tenant (id),
    added_at     timestamptz not null default now(),
    primary key (attribute_id, type_id)
);
create index attribute_usage_type_idx on attribute_usage (type_id);
