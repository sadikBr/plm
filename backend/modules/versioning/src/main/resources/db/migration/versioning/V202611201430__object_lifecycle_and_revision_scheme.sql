-- Each object keeps the lifecycle and revision scheme it was created with (roadmap 1.5-1.6), so
-- changing a type's rules later never changes existing objects underneath their users.
alter table object_master add column lifecycle_id uuid references lifecycle_template (id);
alter table object_master add column revision_scheme text;

update object_master set lifecycle_id = '00000000-0000-7000-8000-000000000301', revision_scheme = 'ALPHABETIC_ASME'
where lifecycle_id is null;

alter table object_master alter column lifecycle_id set not null;
alter table object_master alter column revision_scheme set not null;
alter table object_master add constraint object_master_revision_scheme_check
    check (revision_scheme in ('ALPHABETIC_ASME', 'ALPHABETIC', 'NUMERIC_FROM_1', 'NUMERIC_FROM_0'));
