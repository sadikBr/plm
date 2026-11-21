-- Product and manufacturing information (roadmap 6.12): dimensions, tolerances, datums, finishes
-- and notes on a CAD model's iteration, grouped in views. Kept as one document per iteration,
-- carried forward like the model's other data.
create table cad_pmi (
    iteration_id uuid        primary key references object_iteration (id) on delete cascade,
    tenant_id    uuid        not null,
    views        jsonb       not null,
    -- Where it came from: a sidecar file's name, or null when authored in the viewer.
    source       text,
    updated_at   timestamptz not null,
    updated_by   text        not null
);
