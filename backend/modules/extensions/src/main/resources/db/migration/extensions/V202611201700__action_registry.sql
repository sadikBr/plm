-- Action registry (roadmap 2.1). Actions are declared in code (built-ins) and, later, by extension
-- packages. Administrators adjust them per tenant: hide, move between toolbar and menu, reorder,
-- relabel, restrict to types or states. Only those adjustments are stored; null keeps the default.

create table action_config (
    tenant_id   uuid        not null references tenant (id),
    action_id   text        not null,
    hidden      boolean     not null default false,
    placement   text        check (placement in ('OBJECT_TOOLBAR', 'OBJECT_MORE')),
    sort_order  integer,
    label       text,
    -- Type names (subtypes included) and state keys; null = the action's own default.
    types       jsonb,
    states      jsonb,
    updated_at  timestamptz not null,
    updated_by  text        not null,
    primary key (tenant_id, action_id)
);
