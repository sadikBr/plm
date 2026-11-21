-- Reports, scheduled reports and alerts, dashboards and home pages (roadmap 3.8).

create table report (
    id           uuid        primary key,
    tenant_id    uuid        not null,
    owner        text        not null,
    -- Runs with the owner's access (and administrator rights if they had them when saving).
    owner_admin  boolean     not null default false,
    name         text        not null,
    description  text,
    -- The search it runs (text and filters), the columns it shows, how it groups for its chart.
    query        jsonb       not null,
    columns      jsonb       not null,
    group_by     text,
    shared       boolean     not null default false,
    -- Scheduled: five-field cron in time_zone; null: run on demand only.
    cron         text,
    time_zone    text        not null default 'UTC',
    next_run_at  timestamptz,
    -- Who gets it by mail: principal ids or addresses.
    recipients   jsonb       not null default '[]',
    -- When a scheduled run sends: ALWAYS, ANY (something found), MORE_THAN (threshold), NEW (new matches).
    alert        text        not null default 'ALWAYS',
    threshold    integer,
    last_run_at  timestamptz,
    last_total   integer,
    last_ids     jsonb,
    created_at   timestamptz not null,
    updated_at   timestamptz not null,
    unique (tenant_id, owner, name)
);

create index report_due on report (next_run_at) where cron is not null;

create table report_run (
    id         uuid        primary key,
    report_id  uuid        not null references report (id) on delete cascade,
    ran_at     timestamptz not null,
    total      integer     not null,
    new_count  integer     not null default 0,
    -- Mailed, and to how many.
    sent_to    integer     not null default 0,
    note       text
);

create index report_run_by_report on report_run (report_id, ran_at desc);

create table dashboard (
    id         uuid        primary key,
    tenant_id  uuid        not null,
    owner      text        not null,
    name       text        not null,
    shared     boolean     not null default false,
    widgets    jsonb       not null,
    created_at timestamptz not null,
    updated_at timestamptz not null
);

-- Each person's home page: one of the dashboards they can see; none: the default.
create table home_page (
    tenant_id    uuid not null,
    principal_id text not null,
    dashboard_id uuid not null references dashboard (id) on delete cascade,
    primary key (tenant_id, principal_id)
);
