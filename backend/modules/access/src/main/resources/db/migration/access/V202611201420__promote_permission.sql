-- New permission bit PROMOTE (32): change an object's lifecycle state (roadmap 1.6).
alter table access_policy drop constraint access_policy_permissions_check;
alter table access_policy add constraint access_policy_permissions_check check (permissions > 0 and permissions < 64);

-- Managers may promote by default (31 -> 63). Members ask a manager (or, later, a promotion request).
update access_policy set permissions = permissions | 32
where id = '00000000-0000-7000-8000-000000000203';
