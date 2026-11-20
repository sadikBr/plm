-- Validation rules (roadmap 2.2): a CEL condition an object of the type (or a subtype) must meet to
-- be created or checked in, and the message shown when it doesn't. Optionally tied to the attribute
-- the message is about, so forms can show it next to that field.

create table type_validation_rule (
    id          uuid        primary key,
    tenant_id   uuid        not null references tenant (id),
    type_id     uuid        not null references type_def (id),
    name        text        not null,
    condition   text        not null,
    message     text        not null,
    attribute   text,
    active      boolean     not null default true,
    sort_order  integer     not null default 0,
    created_at  timestamptz not null,
    created_by  text        not null,
    updated_at  timestamptz not null,
    updated_by  text        not null,
    unique (tenant_id, type_id, name)
);
