-- Roadmap 9.8: retention may purge old iterations of an object. While a purge of that object runs
-- (plm.purging_master set in its transaction), its checked-in iterations may be deleted, and the
-- ones that stay may have based_on_id re-pointed past the removed ones; nothing else may change.
create or replace function object_iteration_protect_history() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' and current_setting('plm.deleting_master', true) = old.master_id::text then
        return old;
    end if;
    if current_setting('plm.purging_master', true) = old.master_id::text then
        if tg_op = 'DELETE' then
            return old;
        end if;
        if (to_jsonb(new) - 'based_on_id' - 'state') is not distinct from (to_jsonb(old) - 'based_on_id' - 'state') then
            return new;
        end if;
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
