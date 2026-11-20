-- Roadmap 1.12: context templates and shared teams.

-- A shared team is a named group of people with roles, maintained once and attached to any number
-- of contexts. Its members hold their role in every context it is attached to (and below).
create table shared_team (
    id          uuid primary key,
    tenant_id   uuid        not null references tenant (id),
    name        text        not null,
    description text,
    created_at  timestamptz not null,
    created_by  text        not null,
    unique (tenant_id, name)
);

create table shared_team_member (
    team_id      uuid        not null references shared_team (id) on delete cascade,
    tenant_id    uuid        not null,
    principal_id text        not null,
    role         text        not null,
    added_at     timestamptz not null,
    added_by     text        not null,
    primary key (team_id, principal_id),
    foreign key (tenant_id, role) references team_role (tenant_id, key)
);

create table context_shared_team (
    context_id  uuid        not null references context (id),
    team_id     uuid        not null references shared_team (id) on delete cascade,
    tenant_id   uuid        not null,
    attached_at timestamptz not null,
    attached_by text        not null,
    primary key (context_id, team_id)
);

create index context_shared_team_team_idx on context_shared_team (team_id);

-- Every role a principal holds directly in a context: its own team plus attached shared teams.
-- Permission checks join this view instead of context_member; Postgres inlines it.
create view team_membership as
select tenant_id, context_id, principal_id, role, added_at, added_by, null::uuid as shared_team_id
from context_member
union all
select cst.tenant_id, cst.context_id, stm.principal_id, stm.role, stm.added_at, stm.added_by, stm.team_id
from context_shared_team cst
join shared_team_member stm on stm.team_id = cst.team_id;

-- A context template: what a new product, library, project or organization starts with.
-- content = {policies: [{role, type (name), state, effect, permissions}], members: [{principalId, role}],
--            sharedTeams: [name]}; validated by the access module.
create table context_template (
    id          uuid primary key,
    tenant_id   uuid        not null references tenant (id),
    name        text        not null,
    kind        text        not null check (kind in ('ORGANIZATION', 'PRODUCT', 'LIBRARY', 'PROJECT')),
    description text,
    content     jsonb       not null,
    created_at  timestamptz not null,
    created_by  text        not null,
    updated_at  timestamptz not null,
    unique (tenant_id, kind, name)
);
