-- Document templates (roadmap 3.3): objects whose latest version new documents start from.
create table document_template (
    tenant_id       uuid        not null,
    id              uuid        primary key,
    -- The template object (the versioning module's object_master).
    master_id       uuid        not null,
    name            text        not null,
    description     text,
    -- Set: usable only inside this organization; null: everywhere.
    organization_id uuid,
    active          boolean     not null default true,
    created_at      timestamptz not null,
    created_by      text        not null,
    updated_at      timestamptz not null,
    updated_by      text        not null,
    unique (tenant_id, master_id),
    unique (tenant_id, name)
);
