-- Tenants own every row in the system (ADR 0009).
create table tenant (
    id         uuid primary key,
    name       text        not null unique,
    created_at timestamptz not null default now()
);

insert into tenant (id, name) values ('00000000-0000-7000-8000-000000000001', 'default');
