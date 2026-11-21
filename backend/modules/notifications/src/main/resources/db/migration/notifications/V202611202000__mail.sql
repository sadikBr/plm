-- Mail the platform sends (roadmap 3.8): queued with the change that caused it, sent in the background.
create table mail_outbox (
    id         uuid        primary key,
    seq        bigserial   not null,
    tenant_id  uuid        not null,
    recipients jsonb       not null,
    subject    text        not null,
    text_body  text        not null,
    html_body  text,
    -- Why it was sent, e.g. "report:<id>".
    origin     text,
    status     text        not null check (status in ('PENDING', 'SENT', 'FAILED')),
    attempts   integer     not null default 0,
    error      text,
    created_at timestamptz not null,
    sent_at    timestamptz
);

create index mail_outbox_pending on mail_outbox (created_at) where status = 'PENDING';
