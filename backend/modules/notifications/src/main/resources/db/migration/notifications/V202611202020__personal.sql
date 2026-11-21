-- Personal tracking (roadmap 3.9): favorites, recent items, subscriptions, notifications, digests.

create table favorite (
    tenant_id    uuid        not null,
    principal_id text        not null,
    -- OBJECT, CONTEXT, FOLDER, REPORT
    kind         text        not null,
    target_id    uuid        not null,
    label        text        not null,
    created_at   timestamptz not null,
    primary key (tenant_id, principal_id, kind, target_id)
);

create table recent_visit (
    tenant_id    uuid        not null,
    principal_id text        not null,
    object_id    uuid        not null,
    visited_at   timestamptz not null,
    primary key (tenant_id, principal_id, object_id)
);

create index recent_visit_latest on recent_visit (tenant_id, principal_id, visited_at desc);

create table subscription (
    tenant_id    uuid        not null,
    principal_id text        not null,
    -- OBJECT, FOLDER, CONTEXT
    kind         text        not null,
    target_id    uuid        not null,
    label        text        not null,
    -- Whether they were a site administrator when subscribing: notices respect what they may read.
    site_admin   boolean     not null default false,
    created_at   timestamptz not null,
    primary key (tenant_id, principal_id, kind, target_id)
);

create index subscription_target on subscription (tenant_id, kind, target_id);

create table notification (
    id           uuid        primary key,
    seq          bigserial   not null,
    tenant_id    uuid        not null,
    principal_id text        not null,
    title        text        not null,
    body         text,
    -- Where it leads in the UI, e.g. /objects/<id>.
    link         text,
    object_id    uuid,
    -- Who caused it.
    actor        text,
    created_at   timestamptz not null,
    read_at      timestamptz,
    -- Included in a mail (immediately or in a digest).
    mailed_at    timestamptz
);

create index notification_inbox on notification (tenant_id, principal_id, seq desc);
create index notification_unread on notification (tenant_id, principal_id) where read_at is null;

create table notification_setting (
    tenant_id      uuid        not null,
    principal_id   text        not null,
    -- NONE, IMMEDIATE, DAILY
    mail           text        not null default 'DAILY',
    last_digest_at timestamptz,
    primary key (tenant_id, principal_id)
);

-- The daily digest, every morning.
insert into job_schedule (tenant_id, id, name, type, payload, cron, time_zone, enabled, run_as, run_as_admin,
                          next_run_at, created_at, created_by, updated_at, updated_by)
select t.id, '00000000-0000-7000-8000-00000000a003', 'Daily notification digest', 'notifications.digest', '{}',
       '30 6 * * *', 'UTC', true, 'system', true, now() + interval '1 hour', now(), 'system', now(), 'system'
from tenant t where t.id = '00000000-0000-7000-8000-000000000001';
