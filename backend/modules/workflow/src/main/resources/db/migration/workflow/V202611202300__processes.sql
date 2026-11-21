-- Processes (roadmap 5.1): templates, their versions, running instances, their history and the
-- tasks they give people. The process itself runs on Temporal; these rows are its readable record.
create table process_template (
    id          uuid primary key,
    tenant_id   uuid        not null,
    name        text        not null,
    description text,
    version     int         not null,
    active      boolean     not null default true,
    updated_at  timestamptz not null,
    updated_by  text        not null,
    unique (tenant_id, name)
);

create table process_template_version (
    template_id uuid        not null references process_template (id) on delete cascade,
    version     int         not null,
    definition  jsonb       not null,
    created_at  timestamptz not null,
    created_by  text        not null,
    primary key (template_id, version)
);

create table process_instance (
    id               uuid primary key,
    tenant_id        uuid        not null,
    template_id      uuid        not null references process_template (id),
    template_version int         not null,
    name             text        not null,
    subject_id       uuid,
    status           text        not null check (status in ('RUNNING', 'COMPLETED', 'CANCELLED', 'FAILED')),
    outcome          text,
    started_by       text        not null,
    started_at       timestamptz not null,
    ended_at         timestamptz,
    comment          text,
    -- Steps open now: node id → label.
    current          jsonb       not null default '{}'
);
create index process_instance_subject_idx on process_instance (subject_id, started_at desc);
create index process_instance_status_idx on process_instance (tenant_id, status, started_at desc);

create table process_event (
    id          bigint generated always as identity primary key,
    instance_id uuid        not null references process_instance (id) on delete cascade,
    at          timestamptz not null,
    kind        text        not null,
    node_id     text,
    actor       text,
    message     text        not null
);
create index process_event_instance_idx on process_event (instance_id, id);

create table work_item (
    id           uuid primary key,
    tenant_id    uuid        not null,
    instance_id  uuid        not null references process_instance (id) on delete cascade,
    node_id      text        not null,
    -- Which pass through the step: a step can be visited again (a loop back after a rejection).
    visit        int         not null,
    kind         text        not null,
    title        text        not null,
    instructions text,
    assignee     text        not null,
    choices      jsonb       not null,
    signature    boolean     not null default false,
    status       text        not null check (status in ('OPEN', 'DONE', 'CANCELLED')),
    outcome      text,
    comment      text,
    subject_id   uuid,
    created_at   timestamptz not null,
    due_at       timestamptz,
    completed_at timestamptz,
    completed_by text
);
create index work_item_assignee_idx on work_item (tenant_id, assignee, status, created_at desc);
create index work_item_instance_idx on work_item (instance_id, created_at);

-- A ready-made review: the owner's manager approves (or sends back), then the object is released.
insert into process_template (id, tenant_id, name, description, version, active, updated_at, updated_by)
values ('00000000-0000-7000-8000-000000000501', '00000000-0000-7000-8000-000000000001', 'Review and release',
        'A product manager reviews the object; on approval it is released, on rejection its owner reworks it.',
        1, true, now(), 'system');
insert into process_template_version (template_id, version, definition, created_at, created_by)
values ('00000000-0000-7000-8000-000000000501', 1, '{
  "start": "submit",
  "nodes": [
    {"id": "submit", "kind": "ACTION", "label": "Submit", "action": "SET_STATE", "state": "UNDER_REVIEW",
     "next": "review", "x": 40, "y": 100},
    {"id": "review", "kind": "TASK", "label": "Review", "assignees": ["role:MANAGER"],
     "instructions": "Check the object and its files, then approve it or send it back.",
     "choices": ["APPROVE", "REJECT"], "dueIn": "P3D",
     "outcomes": {"APPROVE": "release", "REJECT": "back"}, "x": 240, "y": 100},
    {"id": "release", "kind": "ACTION", "label": "Release", "action": "SET_STATE", "state": "RELEASED",
     "next": "approved", "x": 440, "y": 40},
    {"id": "approved", "kind": "END", "label": "Approved", "outcome": "APPROVED", "x": 640, "y": 40},
    {"id": "back", "kind": "ACTION", "label": "Back to work", "action": "SET_STATE", "state": "IN_WORK",
     "next": "rework", "x": 440, "y": 180},
    {"id": "rework", "kind": "TASK", "label": "Rework", "assignees": ["owner"],
     "instructions": "See the reviewer''s comment, change the object, then resubmit it or withdraw.",
     "choices": ["RESUBMIT", "WITHDRAW"], "outcomes": {"RESUBMIT": "submit", "WITHDRAW": "withdrawn"},
     "x": 640, "y": 180},
    {"id": "withdrawn", "kind": "END", "label": "Withdrawn", "outcome": "WITHDRAWN", "x": 840, "y": 180}
  ]
}', now(), 'system');
