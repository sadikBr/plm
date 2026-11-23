-- Roadmap 9.1: a shared team can be managed by the directory (a SCIM group). Its members are then
-- set by the identity provider only; people are still attached to contexts through it as usual.
alter table shared_team add column directory_group text;
create unique index shared_team_directory_group_idx on shared_team (tenant_id, directory_group);
