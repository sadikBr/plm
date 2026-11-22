-- Report builder (between Phase 7 and 8): a report can be a query over objects and their links,
-- with the answers its questions get on scheduled runs.
alter table report add column definition jsonb;
alter table report add column answers jsonb not null default '{}';
