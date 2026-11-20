-- Service accounts and API tokens (roadmap 2.7). A service account is a principal like a user: it
-- joins teams and access rules apply to it. Tokens are stored hashed (SHA-256); the token itself is
-- shown once, when issued.

alter table principal add column kind text not null default 'USER' check (kind in ('USER', 'SERVICE'));

create table service_account (
    tenant_id     uuid        not null references tenant (id),
    id            text        not null,
    description   text,
    site_admin    boolean     not null default false,
    active        boolean     not null default true,
    -- Set for the account an extension gets at install.
    extension_id  text,
    created_at    timestamptz not null,
    created_by    text        not null,
    primary key (tenant_id, id),
    foreign key (tenant_id, id) references principal (tenant_id, id)
);

create table api_token (
    id            uuid        primary key,
    tenant_id     uuid        not null references tenant (id),
    -- The service account, or for DELEGATED tokens the user they act as.
    principal_id  text        not null,
    kind          text        not null check (kind in ('API_TOKEN', 'CLIENT_SECRET', 'ACCESS', 'DELEGATED')),
    name          text        not null,
    token_hash    text        not null unique,
    prefix        text        not null,
    scopes        jsonb       not null,
    extension_id  text,
    expires_at    timestamptz,
    last_used_at  timestamptz,
    created_at    timestamptz not null,
    created_by    text        not null,
    revoked_at    timestamptz
);
create index api_token_principal_idx on api_token (tenant_id, principal_id);
