-- Roadmap 9.9: what is audited, per site, and for how long.
create table audit_config (
    tenant_id      uuid primary key references tenant (id),
    -- Event kinds left out, as type prefixes (e.g. `jobs.`, `notifications.`).
    excluded       jsonb       not null default '[]',
    -- Entries older than this are purged; null: kept for ever.
    retention_days int check (retention_days is null or retention_days >= 30),
    updated_at     timestamptz not null,
    updated_by     text        not null
);

-- The log stays append-only, except for a purge of entries past the retention period, inside the
-- transaction that sets plm.audit_purge_before (the purge itself is audited).
create or replace function audit_log_append_only() returns trigger
    language plpgsql as
$$
begin
    if tg_op = 'DELETE' and current_setting('plm.audit_purge_before', true) is not null
       and current_setting('plm.audit_purge_before', true) <> ''
       and old.occurred_at < current_setting('plm.audit_purge_before', true)::timestamptz then
        return old;
    end if;
    raise exception 'audit_log is append-only' using errcode = 'P0001', hint = 'PLM_AUDIT_APPEND_ONLY';
end;
$$;
