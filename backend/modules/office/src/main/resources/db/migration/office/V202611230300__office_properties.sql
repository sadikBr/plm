-- Office file properties ↔ attributes, per type (roadmap 8.4). Subtypes inherit their supertypes'
-- mappings; a subtype's own mapping of a property wins.
create table office_property_map (
    tenant_id uuid not null,
    type_id   uuid not null,
    -- A core property (title, subject, author, keywords, description, category, status) or a custom one's name.
    property  text not null,
    attribute text not null,
    primary key (tenant_id, type_id, property)
);

-- Built-in Document type: its own attributes.
insert into office_property_map (tenant_id, type_id, property, attribute) values
    ('00000000-0000-7000-8000-000000000001', '00000000-0000-7000-8000-000000000101', 'title', 'title'),
    ('00000000-0000-7000-8000-000000000001', '00000000-0000-7000-8000-000000000101', 'description', 'description'),
    ('00000000-0000-7000-8000-000000000001', '00000000-0000-7000-8000-000000000101', 'category', 'category'),
    ('00000000-0000-7000-8000-000000000001', '00000000-0000-7000-8000-000000000101', 'Department', 'department');
