-- Part ↔ CAD association (roadmap 6.4): a part's version names the CAD documents that describe it:
-- one owning model (its geometry and structure come from it) and contributing ones.
create table cad_association (
    id                uuid primary key,
    tenant_id         uuid not null,
    part_iteration_id uuid not null references object_iteration (id) on delete cascade,
    cad_master_id     uuid not null references object_master (id),
    kind              text not null check (kind in ('OWNER', 'CONTRIBUTING')),
    unique (part_iteration_id, cad_master_id)
);
create unique index cad_association_one_owner on cad_association (part_iteration_id) where kind = 'OWNER';
create index cad_association_cad on cad_association (cad_master_id);
