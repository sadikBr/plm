-- Roadmap 9.1: users and groups an identity provider pushes over SCIM 2.0. A group is mirrored as
-- a site-wide shared team (managed by the directory) whose members hold the group's role.
create table scim_user (
    id           uuid primary key,
    tenant_id    uuid        not null references tenant (id),
    principal_id text        not null,
    external_id  text,
    user_name    text        not null,
    display_name text,
    given_name   text,
    family_name  text,
    email        text,
    active       boolean     not null,
    created_at   timestamptz not null,
    updated_at   timestamptz not null,
    version      int         not null default 1
);
create unique index scim_user_name_idx on scim_user (tenant_id, lower(user_name));
create index scim_user_principal_idx on scim_user (tenant_id, principal_id);

create table scim_group (
    id           uuid primary key,
    tenant_id    uuid        not null references tenant (id),
    external_id  text,
    display_name text        not null,
    -- The role the group's members hold through its shared team.
    role         text        not null default 'MEMBER',
    created_at   timestamptz not null,
    updated_at   timestamptz not null,
    version      int         not null default 1
);
create unique index scim_group_name_idx on scim_group (tenant_id, lower(display_name));

create table scim_group_member (
    group_id uuid not null references scim_group (id) on delete cascade,
    user_id  uuid not null references scim_user (id) on delete cascade,
    primary key (group_id, user_id)
);
create index scim_group_member_user_idx on scim_group_member (user_id);
