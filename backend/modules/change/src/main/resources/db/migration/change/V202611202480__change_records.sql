-- Change dashboards (roadmap 5.12): one row per change object, kept up to date from its events,
-- so open counts, ages and cycle times are cheap to compute.
create table change_record (
    id          uuid primary key,
    tenant_id   uuid        not null,
    context_id  uuid        not null,
    kind        text        not null,
    state       text        not null,
    created_at  timestamptz not null,
    -- When it entered its current state.
    state_since timestamptz not null,
    -- When it was (last) resolved; null otherwise.
    resolved_at timestamptz
);
create index change_record_tenant_idx on change_record (tenant_id, kind, state);

-- The changes that exist already.
with recursive lineage (type_id, ancestor_id) as (
    select id, id from type_def where base_class = 'CHANGE'
    union all
    select l.type_id, t.parent_id from lineage l join type_def t on t.id = l.ancestor_id where t.parent_id is not null
),
kinds (type_id, kind) as (
    select type_id,
           case ancestor_id
               when '00000000-0000-7000-8000-000000000104' then 'PROBLEM_REPORT'
               when '00000000-0000-7000-8000-000000000105' then 'CHANGE_REQUEST'
               when '00000000-0000-7000-8000-000000000106' then 'CHANGE_NOTICE'
               when '00000000-0000-7000-8000-000000000107' then 'CHANGE_TASK'
               when '00000000-0000-7000-8000-000000000108' then 'VARIANCE'
           end
    from lineage
    where ancestor_id in ('00000000-0000-7000-8000-000000000104', '00000000-0000-7000-8000-000000000105',
                          '00000000-0000-7000-8000-000000000106', '00000000-0000-7000-8000-000000000107',
                          '00000000-0000-7000-8000-000000000108')
)
insert into change_record (id, tenant_id, context_id, kind, state, created_at, state_since, resolved_at)
select m.id, m.tenant_id, m.context_id, k.kind, i.state, m.created_at,
       coalesce((select max(e.occurred_at) from outbox_event e
                 where e.aggregate_id = m.id and e.event_type = 'versioning.ObjectStateChanged.v1'), m.created_at),
       case when i.state = 'RESOLVED' then
           coalesce((select max(e.occurred_at) from outbox_event e
                     where e.aggregate_id = m.id and e.event_type = 'versioning.ObjectStateChanged.v1'
                       and e.payload ->> 'to' = 'RESOLVED'), i.created_at)
       end
from object_master m
join kinds k on k.type_id = m.type_id
join object_iteration i on i.id = m.latest_iteration_id;
