-- Roadmap 9.4: a received package can update objects already here (a new iteration each).
alter table migration_item add column updated boolean not null default false;
