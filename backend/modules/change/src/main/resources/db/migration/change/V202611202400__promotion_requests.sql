-- Promotion requests (roadmap 5.7): a set of objects asked to move to a target state, reviewed by
-- a process; locked meanwhile, promoted on approval.
create sequence promotion_number_seq;

create table promotion_request (
    id           uuid primary key,
    tenant_id    uuid        not null,
    number       text        not null,
    name         text        not null,
    target_state text        not null,
    context_id   uuid        not null,
    status       text        not null check (status in ('UNDER_REVIEW', 'APPROVED', 'REJECTED', 'CANCELLED')),
    comment      text,
    instance_id  uuid,
    created_by   text        not null,
    created_at   timestamptz not null,
    decided_at   timestamptz,
    unique (tenant_id, number)
);

create table promotion_item (
    request_id   uuid not null references promotion_request (id) on delete cascade,
    object_id    uuid not null,
    -- The version asked to be promoted, and its state then.
    iteration_id uuid not null,
    version      text not null,
    state_before text not null,
    primary key (request_id, object_id)
);
create index promotion_item_object_idx on promotion_item (object_id);

-- The review a promotion request goes through, unless another template is chosen.
insert into process_template (id, tenant_id, name, description, version, active, updated_at, updated_by)
values ('00000000-0000-7000-8000-000000000502', '00000000-0000-7000-8000-000000000001', 'Promotion review',
        'A product manager approves or rejects a promotion request, signing it.', 1, true, now(), 'system');
insert into process_template_version (template_id, version, definition, created_at, created_by)
values ('00000000-0000-7000-8000-000000000502', 1, '{
  "start": "review",
  "nodes": [
    {"id": "review", "kind": "TASK", "label": "Approve promotion", "assignees": ["role:MANAGER"],
     "instructions": "Check the objects of the request; approving promotes them all to the target state.",
     "choices": ["APPROVE", "REJECT"], "dueIn": "P3D", "signature": true,
     "outcomes": {"APPROVE": "approved", "REJECT": "rejected"}, "x": 40, "y": 80},
    {"id": "approved", "kind": "END", "label": "Approved", "outcome": "APPROVED", "x": 280, "y": 20},
    {"id": "rejected", "kind": "END", "label": "Rejected", "outcome": "REJECTED", "x": 280, "y": 150}
  ]
}', now(), 'system');
