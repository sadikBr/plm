-- Family tables (roadmap 6.3): a generic model's version holds a table of parameters; each row is
-- an instance, a CAD document of its own that shares the generic's file.
create table cad_family (
    iteration_id uuid primary key references object_iteration (id) on delete cascade,
    tenant_id    uuid  not null,
    -- [{"name": "length", "label": "Length", "unit": "mm"}...]
    columns      jsonb not null
);

create table cad_family_row (
    id                 uuid primary key,
    tenant_id          uuid  not null,
    iteration_id       uuid  not null references object_iteration (id) on delete cascade,
    instance_master_id uuid  not null references object_master (id),
    "values"           jsonb not null,
    position           int   not null,
    unique (iteration_id, instance_master_id)
);
create index cad_family_row_instance on cad_family_row (instance_master_id);
