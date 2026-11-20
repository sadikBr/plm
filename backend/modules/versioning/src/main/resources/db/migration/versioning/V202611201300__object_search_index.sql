-- Substring search on number and name (global search box). Trigram GIN indexes keep
-- `ilike '%text%'` fast on millions of objects; full-text search comes with OpenSearch (Phase 2).
create extension if not exists pg_trgm;

create index object_master_number_trgm on object_master using gin (number gin_trgm_ops);
create index object_master_name_trgm on object_master using gin (name gin_trgm_ops);
