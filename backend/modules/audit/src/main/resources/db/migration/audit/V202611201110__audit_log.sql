-- Append-only audit trail (ADR 0008). One row per domain event, written in the same transaction
-- as the change. The database rejects updates and deletes.
create table audit_log (
    seq            bigint generated always as identity primary key,
    event_id       uuid        not null unique,
    tenant_id      uuid        not null references tenant (id),
    occurred_at    timestamptz not null,
    actor          text        not null,
    action         text        not null,
    aggregate_type text        not null,
    aggregate_id   uuid        not null,
    details        jsonb       not null
);

create index audit_log_aggregate_idx on audit_log (tenant_id, aggregate_id, seq desc);
create index audit_log_actor_idx on audit_log (tenant_id, actor, seq desc);

create function audit_log_append_only() returns trigger
    language plpgsql as
$$
begin
    raise exception 'audit_log is append-only' using errcode = 'P0001', hint = 'PLM_AUDIT_APPEND_ONLY';
end;
$$;

create trigger audit_log_append_only
    before update or delete on audit_log
    for each row execute function audit_log_append_only();

-- TRUNCATE bypasses row triggers, so block it too.
create trigger audit_log_no_truncate
    before truncate on audit_log
    for each statement execute function audit_log_append_only();
