-- Process assignments (roadmap 5.5): which template runs for which type and when, per context.
-- A context's assignments apply to everything under it unless a nearer context assigns the same
-- type and trigger.
create table process_assignment (
    id          uuid primary key,
    tenant_id   uuid        not null,
    context_id  uuid        not null,
    type_id     uuid        not null,
    trigger     text        not null check (trigger in ('ON_CREATE', 'ON_STATE', 'MANUAL')),
    state       text,
    template_id uuid        not null references process_template (id),
    active      boolean     not null default true,
    created_at  timestamptz not null,
    created_by  text        not null,
    check ((trigger = 'ON_STATE') = (state is not null))
);
create index process_assignment_context_idx on process_assignment (tenant_id, context_id);
