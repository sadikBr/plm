-- Product structure (roadmap 4.1): a parent part iteration uses child part masters. Which iteration
-- of the child is meant is decided when the structure is read (configuration specifications, 4.2).
create table usage_link (
    id                  uuid        primary key,
    tenant_id           uuid        not null,
    parent_iteration_id uuid        not null references object_iteration (id) on delete cascade,
    child_master_id     uuid        not null references object_master (id),
    -- Order of the lines: 10, 20, 30... by default.
    line_number         int         not null check (line_number > 0),
    -- The balloon number on drawings; free text.
    find_number         text,
    quantity            numeric     not null check (quantity > 0),
    -- A key of the unitOfUse value list (each, kg, m...).
    unit                text        not null,
    notes               text,
    created_at          timestamptz not null,
    created_by          text        not null,
    constraint usage_link_line unique (parent_iteration_id, line_number) deferrable initially deferred
);

create index usage_link_parent on usage_link (parent_iteration_id, line_number);
-- Where-used (4.6) and "is it used anywhere" (deletes).
create index usage_link_child on usage_link (child_master_id);
