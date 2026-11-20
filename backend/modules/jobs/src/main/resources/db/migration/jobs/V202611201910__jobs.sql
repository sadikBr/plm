-- Background jobs and schedules (roadmap 3.1).
create table job (
    id               uuid        primary key,
    seq              bigserial   not null,
    tenant_id        uuid        not null,
    type             text        not null,
    title            text        not null,
    payload          jsonb       not null default '{}',
    status           text        not null check (status in ('QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    -- Who it runs as: the person who submitted it, or the schedule's owner.
    submitted_by     text        not null,
    run_as_admin     boolean     not null default false,
    schedule_id      uuid,
    progress_done    integer,
    progress_total   integer,
    progress_message text,
    result           jsonb,
    error            text,
    attempts         integer     not null default 0,
    max_attempts     integer     not null default 3,
    cancel_requested boolean     not null default false,
    run_at           timestamptz not null,
    -- While running: until when the worker holds it. Past that, the job is taken back.
    lease_until      timestamptz,
    worker           text,
    created_at       timestamptz not null,
    started_at       timestamptz,
    finished_at      timestamptz
);

create index job_due on job (run_at) where status = 'QUEUED';
create index job_running on job (lease_until) where status = 'RUNNING';
create index job_list on job (tenant_id, seq desc);
create index job_mine on job (tenant_id, submitted_by, seq desc);

create table job_log (
    job_id  uuid        not null references job (id) on delete cascade,
    seq     bigserial   not null,
    at      timestamptz not null,
    level   text        not null,
    message text        not null,
    primary key (job_id, seq)
);

create table job_schedule (
    tenant_id    uuid        not null,
    id           uuid        primary key,
    name         text        not null,
    type         text        not null,
    payload      jsonb       not null default '{}',
    -- Five fields (minute hour day month weekday), in time_zone.
    cron         text        not null,
    time_zone    text        not null default 'UTC',
    enabled      boolean     not null default true,
    run_as       text        not null,
    run_as_admin boolean     not null default false,
    next_run_at  timestamptz,
    last_run_at  timestamptz,
    last_job_id  uuid,
    created_at   timestamptz not null,
    created_by   text        not null,
    updated_at   timestamptz not null,
    updated_by   text        not null,
    unique (tenant_id, name)
);

create index job_schedule_due on job_schedule (next_run_at) where enabled;
