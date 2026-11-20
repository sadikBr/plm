-- Directory of users seen through the identity provider (Keycloak). The id is the OIDC `sub`
-- claim: stable even if the username or email changes. Updated on sign-in.
create table principal (
    id           text        not null,
    tenant_id    uuid        not null references tenant (id),
    username     text        not null,
    display_name text        not null,
    email        text,
    first_seen   timestamptz not null,
    last_seen    timestamptz not null,
    primary key (tenant_id, id)
);

create unique index principal_username_idx on principal (tenant_id, lower(username));
