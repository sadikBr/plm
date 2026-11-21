-- Claiming (roadmap 5.3): one of the people a step was given to takes it; the others' copies wait.
alter table work_item add column claimed_by text;
alter table work_item add column claimed_at timestamptz;
