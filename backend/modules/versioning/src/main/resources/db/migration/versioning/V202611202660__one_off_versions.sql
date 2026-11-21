-- One-off versions (roadmap 7.10): an iteration made from a version for one order or unit, on a
-- branch beside the main line (B.2-1, B.2-2...). The master's latest never points at one.
alter table object_iteration add column one_off int check (one_off > 0);
alter table object_iteration add column one_off_for text;
alter table object_iteration drop constraint object_iteration_master_id_revision_iteration_key;
create unique index object_iteration_version on object_iteration (master_id, revision, iteration, coalesce(one_off, 0));
