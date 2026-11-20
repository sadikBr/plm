-- Operation rules (roadmap 2.5): refuse an operation (check-in, set state, revise...) when a CEL
-- condition holds, with a message. These are the site's own; extensions declare theirs in their
-- manifest.
create table operation_rule (
    id          uuid        primary key,
    tenant_id   uuid        not null references tenant (id),
    name        text        not null,
    operations  jsonb       not null,
    types       jsonb       not null default '[]',
    condition   text        not null,
    message     text        not null,
    active      boolean     not null default true,
    updated_at  timestamptz not null,
    updated_by  text        not null,
    unique (tenant_id, name)
);
