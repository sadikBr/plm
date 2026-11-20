-- Vault cleanup every night (roadmap 3.2).
insert into job_schedule (tenant_id, id, name, type, payload, cron, time_zone, enabled, run_as, run_as_admin,
                          next_run_at, created_at, created_by, updated_at, updated_by)
select t.id, '00000000-0000-7000-8000-00000000a002', 'Clean up the vault', 'content.vault-cleanup', '{}',
       '40 3 * * *', 'UTC', true, 'system', true, now() + interval '1 hour', now(), 'system', now(), 'system'
from tenant t where t.id = '00000000-0000-7000-8000-000000000001';
