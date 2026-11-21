-- Reference and alias attributes (roadmap 4.12).
alter table attribute_def drop constraint attribute_def_data_type_check;
alter table attribute_def add constraint attribute_def_data_type_check check (data_type in
    ('STRING', 'TEXT', 'INTEGER', 'REAL', 'QUANTITY', 'BOOLEAN', 'DATE', 'TIMESTAMP', 'URL', 'ENUMERATION',
     'REFERENCE'));
alter table attribute_def add column reference_type_id uuid references type_def (id);
alter table attribute_def add column alias_of text;
alter table attribute_def add constraint attribute_def_reference_type_check
    check (reference_type_id is null or data_type = 'REFERENCE');
