-- Roadmap 1.8: team roles become data, policies can deny, policies apply to subtypes.

create table team_role (
    tenant_id   uuid        not null references tenant (id),
    key         text        not null check (key ~ '^[A-Z][A-Z0-9_]{1,39}$'),
    labels      jsonb       not null,
    description text,
    -- Shipped out of the box: can be relabelled, never deactivated.
    system      boolean     not null default false,
    -- Inactive roles can't be given to anyone new; existing members and policies keep working.
    active      boolean     not null default true,
    sort_order  int         not null default 0,
    created_at  timestamptz not null default now(),
    primary key (tenant_id, key)
);

insert into team_role (tenant_id, key, labels, description, system, sort_order)
values ('00000000-0000-7000-8000-000000000001', 'VIEWER', '{"en": "Viewer"}', 'Can see the context and its objects.', true, 10),
       ('00000000-0000-7000-8000-000000000001', 'MEMBER', '{"en": "Member"}', 'Creates and works on objects.', true, 20),
       ('00000000-0000-7000-8000-000000000001', 'MANAGER', '{"en": "Manager"}', 'Manages the team and moves objects through their lifecycle.', true, 30);

alter table context_member drop constraint context_member_role_check;
alter table context_member add constraint context_member_role_fk
    foreign key (tenant_id, role) references team_role (tenant_id, key);

-- ALLOW grants permission bits; DENY takes them away again, whatever any ALLOW says (on this
-- context, an ancestor or a descendant). Site administrators are not subject to policies.
alter table access_policy drop constraint access_policy_role_check;
alter table access_policy add constraint access_policy_role_fk
    foreign key (tenant_id, role) references team_role (tenant_id, key);
alter table access_policy add column effect text not null default 'ALLOW' check (effect in ('ALLOW', 'DENY'));
alter table access_policy add column created_by text not null default 'system';
alter table access_policy drop constraint access_policy_context_id_role_type_id_state_key;
alter table access_policy add constraint access_policy_rule_key
    unique nulls not distinct (context_id, role, type_id, state, effect);
