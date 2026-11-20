-- Schedules every site starts with (roadmap 3.1). Administrators can change or turn them off.
insert into job_schedule (tenant_id, id, name, type, payload, cron, time_zone, enabled, run_as, run_as_admin,
                          next_run_at, created_at, created_by, updated_at, updated_by)
select t.id, '00000000-0000-7000-8000-00000000a001', 'Purge expired tokens', 'identity.purge-tokens', '{}',
       '17 * * * *', 'UTC', true, 'system', true, now(), now(), 'system', now(), 'system'
from tenant t where t.id = '00000000-0000-7000-8000-000000000001';
