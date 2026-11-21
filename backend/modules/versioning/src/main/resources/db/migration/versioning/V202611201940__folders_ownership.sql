-- Folders, ownership, reservation and delete rules (roadmap 3.4).

create table folder (
    id         uuid        primary key,
    tenant_id  uuid        not null,
    context_id uuid        not null,
    -- Null: at the top of its context.
    parent_id  uuid references folder (id),
    name       text        not null,
    created_at timestamptz not null,
    created_by text        not null,
    foreign key (context_id, tenant_id) references context (id, tenant_id)
);

create unique index folder_name_unique
    on folder (context_id, coalesce(parent_id, '00000000-0000-0000-0000-000000000000'::uuid), lower(name));
create index folder_parent_idx on folder (context_id, parent_id);

alter table object_master
    add column folder_id   uuid references folder (id),
    -- Null: its creator.
    add column owner       text,
    -- Reserved: nobody else may check it out until it's released.
    add column reserved_by text,
    add column reserved_at timestamptz;

create index object_master_folder_idx on object_master (folder_id);

-- What may be deleted, and by whom (one row per tenant; none: the defaults).
create table delete_rule (
    tenant_id  uuid        primary key,
    -- Lifecycle states objects may be deleted in; empty: any state.
    states     jsonb       not null,
    -- OWNER, MANAGERS (MANAGE on the context). Site administrators always may.
    who        jsonb       not null,
    updated_at timestamptz not null,
    updated_by text        not null
);

-- Deleting an object removes its history too: allowed only for the object being deleted, inside
-- the transaction that deletes it (the application sets plm.deleting_master first).
create or replace function object_iteration_protect_history() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' and current_setting('plm.deleting_master', true) = old.master_id::text then
        return old;
    end if;
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
