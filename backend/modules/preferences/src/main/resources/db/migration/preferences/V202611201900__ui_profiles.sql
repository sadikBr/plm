-- Profiles (roadmap 2.8): which actions and UI areas a group of users sees. Visibility only:
-- access rules still decide what anyone may do.
create table ui_profile (
    tenant_id      uuid        not null,
    id             uuid        primary key,
    name           text        not null,
    description    text,
    hidden_actions jsonb       not null default '[]',
    hidden_areas   jsonb       not null default '[]',
    created_at     timestamptz not null,
    created_by     text        not null,
    updated_at     timestamptz not null,
    updated_by     text        not null,
    unique (tenant_id, name)
);

create table ui_profile_member (
    profile_id  uuid not null references ui_profile (id) on delete cascade,
    tenant_id   uuid not null,
    -- USER (a principal id) or SHARED_TEAM (a shared team id)
    member_kind text not null check (member_kind in ('USER', 'SHARED_TEAM')),
    member_id   text not null,
    primary key (profile_id, member_kind, member_id)
);

create index ui_profile_member_lookup on ui_profile_member (tenant_id, member_kind, member_id);
