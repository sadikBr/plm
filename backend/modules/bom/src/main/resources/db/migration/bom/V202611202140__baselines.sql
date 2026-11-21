-- Baselines (roadmap 4.10): named, frozen sets of exact iterations, e.g. "what we shipped to the
-- customer on 3 March". Also a configuration specification: a structure read "as in this baseline".
create table baseline (
    id          uuid        primary key,
    tenant_id   uuid        not null,
    number      text        not null,
    name        text        not null,
    description text,
    -- Who may see it: those who can read this context.
    context_id  uuid        not null,
    -- OPEN (members can change) or FROZEN (they can't, ever).
    status      text        not null default 'OPEN' check (status in ('OPEN', 'FROZEN')),
    created_at  timestamptz not null,
    created_by  text        not null,
    frozen_at   timestamptz,
    frozen_by   text,
    unique (tenant_id, number)
);

create table baseline_member (
    baseline_id  uuid not null references baseline (id) on delete cascade,
    master_id    uuid not null references object_master (id),
    iteration_id uuid not null references object_iteration (id),
    primary key (baseline_id, master_id)
);

create index baseline_member_master on baseline_member (master_id);
create sequence baseline_number_seq;
