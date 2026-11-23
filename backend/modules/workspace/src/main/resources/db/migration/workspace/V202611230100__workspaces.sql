-- Personal workspaces (roadmap 8.1): the objects a person works on, and what their computer has.
create table workspace (
    id          uuid        primary key,
    tenant_id   uuid        not null,
    owner       text        not null,
    name        text        not null,
    -- Where new files become objects.
    context_id  uuid        not null,
    folder_id   uuid,
    description text,
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    unique (tenant_id, owner, name)
);

create table workspace_item (
    workspace_id    uuid        not null references workspace (id) on delete cascade,
    object_id       uuid        not null,
    -- The iteration whose file the person's computer holds (what they last brought down).
    base_iteration  uuid,
    -- The SHA-256 of the file on their computer, as their client last reported it; null: unknown.
    local_sha256    text,
    local_seen_at   timestamptz,
    added_at        timestamptz not null,
    primary key (workspace_id, object_id)
);

-- Files from the person's computer that aren't objects yet.
create table workspace_file (
    id           uuid        primary key,
    workspace_id uuid        not null references workspace (id) on delete cascade,
    file_name    text        not null,
    upload_id    uuid        not null,
    size         bigint,
    sha256       text,
    added_at     timestamptz not null,
    unique (workspace_id, file_name)
);

-- What happened in a workspace, newest first.
create table workspace_event (
    id           uuid        primary key,
    workspace_id uuid        not null references workspace (id) on delete cascade,
    at           timestamptz not null,
    kind         text        not null,
    object_id    uuid,
    number       text,
    detail       text
);

create index workspace_owner on workspace (tenant_id, owner);
create index workspace_event_ws on workspace_event (workspace_id, at desc);
