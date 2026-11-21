-- Conversion (roadmap 6.6): each CAD document version's STEP file is converted to glTF for the
-- viewer. The viewable itself is kept with the file (vault_derived, kind viewable.glb).
create table cad_conversion (
    iteration_id uuid primary key references object_iteration (id) on delete cascade,
    tenant_id    uuid        not null,
    master_id    uuid        not null,
    content_id   uuid        not null,
    -- QUEUED, RUNNING, READY, FAILED
    status       text        not null,
    error        text,
    job_id       uuid,
    meshes       int,
    triangles    int,
    updated_at   timestamptz not null
);
create index cad_conversion_master on cad_conversion (master_id);
