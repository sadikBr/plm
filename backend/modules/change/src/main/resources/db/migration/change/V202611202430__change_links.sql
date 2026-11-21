-- Change objects' relations (roadmap 5.8): what they affect and produce, and how they chain
-- (a change request addresses problem reports, a change notice implements requests and is carried
-- out by its change tasks).
create table change_link (
    id         uuid primary key,
    tenant_id  uuid        not null,
    change_id  uuid        not null,
    target_id  uuid        not null,
    role       text        not null check (role in ('AFFECTED', 'RESULTING', 'END_ITEM', 'ADDRESSES', 'IMPLEMENTS', 'TASK')),
    -- The target's version when it was linked.
    version    text,
    note       text,
    created_at timestamptz not null,
    created_by text        not null,
    unique (change_id, target_id, role)
);
create index change_link_target_idx on change_link (target_id);
