-- Event subscriptions and webhook deliveries (roadmap 2.6). The site's subscriptions are kept here;
-- extensions declare theirs in their manifest. A delivery is queued in the same transaction as the
-- event (so none is lost), then sent, retried with backoff, and finally dead-lettered.

create table event_subscription (
    id            uuid        primary key,
    tenant_id     uuid        not null references tenant (id),
    name          text        not null,
    events        jsonb       not null,
    filter        text,
    url           text        not null,
    secret        text        not null,
    max_attempts  integer     not null default 8,
    active        boolean     not null default true,
    updated_at    timestamptz not null,
    updated_by    text        not null,
    unique (tenant_id, name)
);

create table webhook_delivery (
    id                uuid        primary key,
    seq               bigserial   not null,
    tenant_id         uuid        not null references tenant (id),
    -- A UUID for the site's subscriptions, `<extension>.<subscription>` for an extension's.
    subscription_id   text        not null,
    subscription_name text        not null,
    source            text        not null,
    event_id          uuid        not null,
    event_type        text        not null,
    aggregate_id      uuid        not null,
    url               text        not null,
    body              jsonb       not null,
    status            text        not null check (status in ('PENDING', 'DELIVERED', 'DEAD')),
    attempts          integer     not null default 0,
    max_attempts      integer     not null,
    next_attempt_at   timestamptz,
    last_http_status  integer,
    last_error        text,
    duration_ms       bigint,
    created_at        timestamptz not null,
    delivered_at      timestamptz
);
create index webhook_delivery_due_idx on webhook_delivery (next_attempt_at) where status = 'PENDING';
create index webhook_delivery_log_idx on webhook_delivery (tenant_id, seq desc);
