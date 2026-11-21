-- Search (roadmap 3.6).

-- Objects waiting to be (re)indexed: queued with the change that touched them, drained by the indexer.
create table search_pending (
    object_id uuid        primary key,
    tenant_id uuid        not null,
    deleted   boolean     not null default false,
    queued_at timestamptz not null
);

-- The index of the built-in (Postgres) engine; with OpenSearch, documents go there instead.
create table search_document (
    object_id   uuid        primary key,
    tenant_id   uuid        not null,
    context_id  uuid        not null,
    type_id     uuid        not null,
    state       text        not null,
    number      text        not null,
    name        text        not null,
    attributes  jsonb       not null,
    -- Everything searchable, as text: number, name, values, file names, file contents.
    body        text        not null,
    document    tsvector    not null,
    modified_at timestamptz not null,
    indexed_at  timestamptz not null
);

create index search_document_fts on search_document using gin (document);
create index search_document_tenant on search_document (tenant_id, modified_at desc);
create index search_document_attrs on search_document using gin (attributes jsonb_path_ops);

create table saved_search (
    id         uuid        primary key,
    tenant_id  uuid        not null,
    owner      text        not null,
    name       text        not null,
    -- The query: text and filters, as the search page sends them.
    query      jsonb       not null,
    -- Everyone sees it (each still only finds what they may read).
    shared     boolean     not null default false,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (tenant_id, owner, name)
);
