-- Table views (roadmap 3.7): columns, filters and sort of an object table, saved by name.
create table table_view (
    id         uuid        primary key,
    tenant_id  uuid        not null,
    owner      text        not null,
    -- Which table: "objects" (context contents) for now.
    table_key  text        not null,
    name       text        not null,
    definition jsonb       not null,
    shared     boolean     not null default false,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    unique (tenant_id, owner, table_key, name)
);
