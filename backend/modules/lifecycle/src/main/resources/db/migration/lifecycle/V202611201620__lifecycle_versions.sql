-- Roadmap 1.15: lifecycle templates are versioned. Editing one inserts a new version; objects keep
-- the version they were created with (object_master.lifecycle_id), types move to the new version.

alter table lifecycle_template add column version int not null default 1 check (version >= 1);
alter table lifecycle_template add column latest boolean not null default true;
alter table lifecycle_template add column previous_id uuid references lifecycle_template (id);
alter table lifecycle_template drop constraint lifecycle_template_tenant_id_name_key;
alter table lifecycle_template add constraint lifecycle_template_name_version_key unique (tenant_id, name, version);
create unique index lifecycle_template_latest_name_idx on lifecycle_template (tenant_id, name) where latest;

-- Entry criteria: attributes that must have a value before an object may enter the state.
alter table lifecycle_state add column required_attributes jsonb not null default '[]';
-- State-based revision series: revisions started from this state continue in this scheme.
alter table lifecycle_state add column revision_scheme text
    check (revision_scheme in ('ALPHABETIC_ASME', 'ALPHABETIC', 'NUMERIC_FROM_1', 'NUMERIC_FROM_0'));
