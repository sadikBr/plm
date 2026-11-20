-- Context hierarchy: SITE -> ORGANIZATION -> PRODUCT | LIBRARY | PROJECT (ADR 0005).
-- Every managed object lives in exactly one context; access policies attach to contexts.
create table context (
    id         uuid primary key,
    tenant_id  uuid        not null references tenant (id),
    parent_id  uuid references context (id),
    kind       text        not null check (kind in ('SITE', 'ORGANIZATION', 'PRODUCT', 'LIBRARY', 'PROJECT')),
    name       text        not null,
    created_at timestamptz not null,
    created_by text        not null,
    check ((kind = 'SITE') = (parent_id is null)),
    unique nulls not distinct (tenant_id, parent_id, name),
    unique (id, tenant_id)
);

create unique index context_one_site_per_tenant on context (tenant_id) where kind = 'SITE';
create index context_parent_idx on context (parent_id);

insert into context (id, tenant_id, parent_id, kind, name, created_at, created_by)
values ('00000000-0000-7000-8000-000000000011', '00000000-0000-7000-8000-000000000001', null,
        'SITE', 'Site', now(), 'system');
