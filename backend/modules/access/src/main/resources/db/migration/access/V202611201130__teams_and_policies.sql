-- Access control (ADR 0005).
--
--   context_closure  every (ancestor, descendant) pair, including (c, c). Policies and team roles
--                    inherit down the tree, and the closure makes that a join instead of recursion.
--   context_member   team: principal X has role R in context C (and, by inheritance, below C).
--   access_policy    role R in context C (and below) grants permission bits on objects of type T
--                    (null = all types) in lifecycle state S (null = all states).
--
-- Permission bits: READ=1 CREATE=2 MODIFY=4 REVISE=8 MANAGE=16.

create table context_closure (
    tenant_id     uuid not null references tenant (id),
    ancestor_id   uuid not null references context (id),
    descendant_id uuid not null references context (id),
    depth         int  not null check (depth >= 0),
    primary key (ancestor_id, descendant_id)
);

create index context_closure_descendant_idx on context_closure (descendant_id, ancestor_id);

-- Backfill for contexts created before this migration.
insert into context_closure (tenant_id, ancestor_id, descendant_id, depth)
with recursive tree (tenant_id, ancestor_id, descendant_id, depth) as (
    select tenant_id, id, id, 0 from context
    union all
    select t.tenant_id, t.ancestor_id, c.id, t.depth + 1
    from tree t join context c on c.parent_id = t.descendant_id
)
select tenant_id, ancestor_id, descendant_id, depth from tree;

create table context_member (
    tenant_id    uuid        not null references tenant (id),
    context_id   uuid        not null references context (id),
    principal_id text        not null,
    role         text        not null check (role in ('VIEWER', 'MEMBER', 'MANAGER')),
    added_at     timestamptz not null,
    added_by     text        not null,
    primary key (context_id, principal_id)
);

create index context_member_principal_idx on context_member (tenant_id, principal_id);

create table access_policy (
    id          uuid primary key,
    tenant_id   uuid        not null references tenant (id),
    context_id  uuid        not null references context (id),
    role        text        not null check (role in ('VIEWER', 'MEMBER', 'MANAGER')),
    type_id     uuid references type_def (id),
    state       text,
    permissions int         not null check (permissions > 0 and permissions < 32),
    created_at  timestamptz not null default now(),
    unique nulls not distinct (context_id, role, type_id, state)
);

create index access_policy_context_idx on access_policy (context_id, role);

-- Default policies on the site context: inherited by every context of the default tenant.
insert into access_policy (id, tenant_id, context_id, role, permissions)
values ('00000000-0000-7000-8000-000000000201', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000011', 'VIEWER', 1),
       ('00000000-0000-7000-8000-000000000202', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000011', 'MEMBER', 15),
       ('00000000-0000-7000-8000-000000000203', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000011', 'MANAGER', 31);
