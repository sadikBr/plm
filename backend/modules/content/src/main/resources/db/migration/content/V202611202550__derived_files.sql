-- Files made from a stored file (roadmap 6.6): a viewable (.glb) of a CAD model, a PDF... Keyed by
-- the source blob, so a file carried unchanged into a new version keeps them.
create table vault_derived (
    blob_key     text        not null references vault_blob (key) on delete cascade,
    kind         text        not null,
    derived_key  text        not null,
    content_type text        not null,
    size         bigint      not null,
    created_at   timestamptz not null,
    primary key (blob_key, kind)
);
