-- Extension packages (roadmap 2.3), installed per tenant from a manifest. The manifest is kept as
-- sent (source) and as parsed; settings the administrator filled in are in config, secret ones apart
-- in secrets, which the API never returns.

create table extension (
    tenant_id     uuid        not null references tenant (id),
    id            text        not null,
    name          text        not null,
    version       text        not null,
    manifest      jsonb       not null,
    source        text        not null,
    config        jsonb       not null default '{}',
    secrets       jsonb       not null default '{}',
    enabled       boolean     not null default true,
    installed_at  timestamptz not null,
    installed_by  text        not null,
    updated_at    timestamptz not null,
    updated_by    text        not null,
    primary key (tenant_id, id)
);
