-- Master / Version / Iteration (ADR 0002).
--
--   object_master     stable identity (number, name) of a versioned object
--   object_iteration  one row per iteration (A.1, A.2, B.1 ...). Checked-in rows are immutable.
--                     At most one WORKING row (the checked-out working copy) per master.

create sequence object_number_seq;

create table object_master (
    id                  uuid primary key,
    tenant_id           uuid        not null references tenant (id),
    type_id             uuid        not null references type_def (id),
    context_id          uuid        not null,
    number              text        not null,
    name                text        not null,
    latest_iteration_id uuid        not null,
    created_at          timestamptz not null,
    created_by          text        not null,
    unique (tenant_id, number),
    unique (id, tenant_id),
    foreign key (context_id, tenant_id) references context (id, tenant_id)
);

create index object_master_context_idx on object_master (context_id);

create table object_iteration (
    id             uuid primary key,
    tenant_id      uuid        not null,
    master_id      uuid        not null,
    -- Monotonic insertion order: the reliable way to order history.
    seq            bigint generated always as identity,
    revision       text        not null,
    iteration      int         not null check (iteration > 0),
    status         text        not null check (status in ('CHECKED_IN', 'WORKING')),
    -- Lifecycle state. The only column that may change after check-in (lifecycle transitions, roadmap 1.x).
    state          text        not null default 'IN_WORK',
    attrs          jsonb       not null default '{}',
    based_on_id    uuid references object_iteration (id),
    comment        text,
    created_at     timestamptz not null,
    created_by     text        not null,
    checked_in_at  timestamptz,
    checked_out_by text,
    foreign key (master_id, tenant_id) references object_master (id, tenant_id),
    unique (master_id, revision, iteration),
    check ((status = 'WORKING') = (checked_out_by is not null)),
    check ((status = 'CHECKED_IN') = (checked_in_at is not null))
);

-- Only one working copy per object. Backs up the row lock taken by the service.
create unique index object_iteration_one_working_copy on object_iteration (master_id) where status = 'WORKING';
create index object_iteration_history_idx on object_iteration (master_id, seq desc);

-- The master is inserted pointing at its first iteration, so check the pointer at commit time.
alter table object_master
    add constraint object_master_latest_iteration_fk
        foreign key (latest_iteration_id) references object_iteration (id) deferrable initially deferred;

-- Defence in depth: the database refuses to change history, whatever the application does.
create function object_iteration_protect_history() returns trigger
    language plpgsql as
$$
begin
    if old.status = 'CHECKED_IN' then
        if tg_op = 'DELETE' then
            raise exception 'iteration % is checked in and cannot be deleted', old.id
                using errcode = 'P0001', hint = 'PLM_IMMUTABLE_ITERATION';
        end if;
        if (to_jsonb(new) - 'state') is distinct from (to_jsonb(old) - 'state') then
            raise exception 'iteration % is checked in and cannot be modified', old.id
                using errcode = 'P0001', hint = 'PLM_IMMUTABLE_ITERATION';
        end if;
    end if;
    return case when tg_op = 'DELETE' then old else new end;
end;
$$;

create trigger object_iteration_protect_history
    before update or delete on object_iteration
    for each row execute function object_iteration_protect_history();
