-- Implementation plans (roadmap 5.9): who does each change task, by when.
create table change_plan (
    task_id    uuid primary key,
    tenant_id  uuid        not null,
    assignee   text,
    due_date   date,
    updated_at timestamptz not null,
    updated_by text        not null
);

-- The change board (roadmap 5.9): product managers vote on a change request, signing.
insert into process_template (id, tenant_id, name, description, version, active, updated_at, updated_by)
values ('00000000-0000-7000-8000-000000000503', '00000000-0000-7000-8000-000000000001', 'Change board review',
        'The product managers vote on the change request (a majority decides, signed); it is then approved or rejected.',
        1, true, now(), 'system');
insert into process_template_version (template_id, version, definition, created_at, created_by)
values ('00000000-0000-7000-8000-000000000503', 1, '{
  "start": "submit",
  "nodes": [
    {"id": "submit", "kind": "ACTION", "label": "Submit to the board", "action": "SET_STATE", "state": "UNDER_REVIEW",
     "next": "board", "x": 40, "y": 100},
    {"id": "board", "kind": "VOTE", "label": "Change board", "assignees": ["role:MANAGER"], "rule": "MAJORITY",
     "instructions": "Weigh the benefit against cost, schedule and risk; approve or reject the change.",
     "choices": ["APPROVE", "REJECT"], "dueIn": "P5D", "signature": true,
     "outcomes": {"APPROVE": "approve", "REJECT": "reject"}, "x": 240, "y": 100},
    {"id": "approve", "kind": "ACTION", "label": "Approve", "action": "SET_STATE", "state": "APPROVED", "next": "approved",
     "x": 440, "y": 40},
    {"id": "approved", "kind": "END", "label": "Approved", "outcome": "APPROVED", "x": 640, "y": 40},
    {"id": "reject", "kind": "ACTION", "label": "Reject", "action": "SET_STATE", "state": "REJECTED", "next": "rejected",
     "x": 440, "y": 170},
    {"id": "rejected", "kind": "END", "label": "Rejected", "outcome": "REJECTED", "x": 640, "y": 170}
  ]
}', now(), 'system');
-- Offered on every change request.
insert into process_assignment (id, tenant_id, context_id, type_id, trigger, template_id, active, created_at, created_by)
select '00000000-0000-7000-8000-000000000601', '00000000-0000-7000-8000-000000000001', c.id,
       '00000000-0000-7000-8000-000000000105', 'MANUAL', '00000000-0000-7000-8000-000000000503', true, now(), 'system'
from context c where c.tenant_id = '00000000-0000-7000-8000-000000000001' and c.parent_id is null;
