-- Roadmap 9.10: file sites (vault replicas near remote offices), each person's preferred site,
-- the copies each site holds, and the parts of resumable multipart uploads.
create table file_site (
    id         uuid primary key,
    tenant_id  uuid        not null references tenant (id),
    name       text        not null,
    -- Where its copies are kept (a directory the server can write, e.g. a mounted share).
    location   text        not null,
    -- Its own file server's address, when it runs one; null: served by this server.
    base_url   text,
    active     boolean     not null default true,
    created_at timestamptz not null,
    unique (tenant_id, name)
);

create table blob_replica (
    blob_key      text        not null,
    site_id       uuid        not null references file_site (id) on delete cascade,
    status        text        not null check (status in ('READY', 'FAILED')),
    error         text,
    replicated_at timestamptz not null,
    primary key (blob_key, site_id)
);

create table file_site_preference (
    tenant_id    uuid not null,
    principal_id text not null,
    site_id      uuid not null references file_site (id) on delete cascade,
    primary key (tenant_id, principal_id)
);

create table upload_part (
    upload_id uuid   not null,
    part      int    not null check (part between 1 and 10000),
    size      bigint not null,
    sha256    text   not null,
    primary key (upload_id, part)
);
