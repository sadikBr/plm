-- Calls of extension actions that run on the server (webhooks, roadmap 2.4): what was called, by
-- whom, on what, and how it went. The administrator's log of an extension's activity.
create table action_invocation (
    id            uuid        primary key,
    -- Insertion order: calls in the same instant still list newest first.
    seq           bigserial   not null,
    tenant_id     uuid        not null references tenant (id),
    action_id     text        not null,
    extension_id  text        not null,
    object_id     uuid        not null,
    object_number text        not null,
    actor         text        not null,
    at            timestamptz not null,
    status        text        not null check (status in ('OK', 'FAILED', 'ERROR', 'TIMEOUT')),
    http_status   integer,
    duration_ms   bigint      not null,
    message       text
);
create index action_invocation_extension_idx on action_invocation (tenant_id, extension_id, seq desc);
