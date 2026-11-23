-- Roadmap 9.2: the files of a bulk load (the sheet and its zip), kept until its job has run.
create table bulk_load (
    id           uuid primary key,
    tenant_id    uuid        not null references tenant (id),
    context_id   uuid        not null,
    sheet_name   text        not null,
    sheet        bytea       not null,
    archive_name text,
    archive      bytea,
    created_at   timestamptz not null,
    created_by   text        not null
);
