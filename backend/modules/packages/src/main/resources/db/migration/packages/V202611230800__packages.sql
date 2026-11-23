-- Roadmap 9.4: packages (a chosen set of objects under a configuration specification, with what
-- to include) and their deliveries to people outside (a download that expires, and who got it).
create table data_package (
    id          uuid primary key,
    tenant_id   uuid        not null references tenant (id),
    name        text        not null,
    description text,
    root_ids    jsonb       not null,
    spec        text        not null check (spec in ('LATEST', 'RELEASED')),
    contents    jsonb       not null,
    created_at  timestamptz not null,
    created_by  text        not null,
    updated_at  timestamptz not null
);

create table delivery (
    id           uuid primary key,
    tenant_id    uuid        not null references tenant (id),
    package_id   uuid        not null references data_package (id) on delete cascade,
    recipient    text        not null,
    email        text,
    message      text,
    token        text        not null unique,
    expires_at   timestamptz not null,
    status       text        not null check (status in ('PREPARING', 'READY', 'FAILED', 'REVOKED')),
    file         bytea,
    file_name    text,
    size         bigint,
    members      int,
    error        text,
    created_at   timestamptz not null,
    created_by   text        not null
);
create index delivery_package_idx on delivery (package_id);

create table delivery_download (
    delivery_id uuid        not null references delivery (id) on delete cascade,
    at          timestamptz not null,
    address     text,
    user_agent  text
);
create index delivery_download_idx on delivery_download (delivery_id, at);
