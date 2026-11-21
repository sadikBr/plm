-- Delegation and escalation (roadmap 5.4).
-- Whose task a stand-in or a reassignment received it for.
alter table work_item add column on_behalf_of text;
-- Who gets someone's new tasks while they're away (both dates optional: open-ended).
create table task_stand_in (
    tenant_id  uuid not null,
    principal  text not null,
    stand_in   text not null,
    from_date  date,
    until_date date,
    primary key (tenant_id, principal),
    check (principal <> stand_in)
);
