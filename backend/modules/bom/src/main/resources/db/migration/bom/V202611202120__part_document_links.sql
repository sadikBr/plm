-- Part-document links (roadmap 4.7), on the part's iteration like its structure:
--   DESCRIBED_BY  a specific iteration of a document (the version that describes this part);
--   REFERENCES    a document master, always read at its latest.
create table part_document_link (
    id                    uuid        primary key,
    tenant_id             uuid        not null,
    part_iteration_id     uuid        not null references object_iteration (id) on delete cascade,
    kind                  text        not null check (kind in ('DESCRIBED_BY', 'REFERENCES')),
    document_master_id    uuid        not null references object_master (id) on delete cascade,
    -- DESCRIBED_BY only.
    document_iteration_id uuid        references object_iteration (id) on delete cascade,
    created_at            timestamptz not null,
    created_by            text        not null,
    unique (part_iteration_id, kind, document_master_id),
    check ((kind = 'DESCRIBED_BY') = (document_iteration_id is not null))
);

create index part_document_link_part on part_document_link (part_iteration_id);
create index part_document_link_document on part_document_link (document_master_id);

-- Which document types may be linked to which part types, and how. No rule for a part type (or
-- its ancestors): any document may be linked to it.
create table part_document_rule (
    id               uuid primary key,
    tenant_id        uuid not null,
    part_type_id     uuid not null references type_def (id),
    document_type_id uuid not null references type_def (id),
    kind             text not null check (kind in ('DESCRIBED_BY', 'REFERENCES')),
    unique (tenant_id, part_type_id, document_type_id, kind)
);
