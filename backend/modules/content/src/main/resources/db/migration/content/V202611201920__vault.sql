-- The file vault (roadmap 3.2): stored files (blobs), uploads in progress, and the content of
-- object iterations (a primary file and attachments).

-- A stored file. Never changed once written; shared by every iteration that has it.
create table vault_blob (
    key           text        primary key,
    tenant_id     uuid        not null,
    size          bigint      not null,
    sha256        text        not null,
    content_type  text        not null,
    -- A small PNG of it, when one could be made.
    thumbnail_key text,
    -- NONE (not an image or PDF), PENDING, READY, FAILED
    thumbnail     text        not null default 'NONE',
    created_at    timestamptz not null,
    created_by    text        not null
);

-- An upload ticket: the client sends the bytes to a signed URL, then attaches the upload.
create table vault_upload (
    id            uuid        primary key,
    tenant_id     uuid        not null,
    created_by    text        not null,
    file_name     text        not null,
    content_type  text        not null,
    declared_size bigint,
    -- Set once the bytes arrived.
    blob_key      text references vault_blob (key),
    expires_at    timestamptz not null,
    created_at    timestamptz not null,
    used_at       timestamptz
);

create table content_item (
    id           uuid        primary key,
    tenant_id    uuid        not null,
    master_id    uuid        not null,
    -- The iteration it belongs to (the versioning module's object_iteration).
    iteration_id uuid        not null references object_iteration (id) on delete cascade,
    -- PRIMARY (one per iteration) or ATTACHMENT
    role         text        not null check (role in ('PRIMARY', 'ATTACHMENT')),
    file_name    text        not null,
    description  text,
    blob_key     text        not null references vault_blob (key),
    -- The item it was carried over from (check-out, revise), null when uploaded here.
    copied_from  uuid,
    added_at     timestamptz not null,
    added_by     text        not null
);

create unique index content_one_primary on content_item (iteration_id) where role = 'PRIMARY';
create index content_by_iteration on content_item (iteration_id);
create index content_by_blob on content_item (blob_key);
