-- Transactional outbox (ADR 0008). Rows are written in the same transaction as the change they
-- describe, then relayed to Kafka by the `events` module and deleted after a retention period.
create table outbox_event (
    seq            bigint generated always as identity primary key,
    id             uuid        not null unique,
    tenant_id      uuid        not null references tenant (id),
    event_type     text        not null,
    aggregate_type text        not null,
    aggregate_id   uuid        not null,
    occurred_at    timestamptz not null,
    actor          text        not null,
    payload        jsonb       not null,
    published_at   timestamptz,
    attempts       int         not null default 0,
    last_error     text
);

create index outbox_event_pending_idx on outbox_event (seq) where published_at is null;
create index outbox_event_published_idx on outbox_event (published_at) where published_at is not null;
