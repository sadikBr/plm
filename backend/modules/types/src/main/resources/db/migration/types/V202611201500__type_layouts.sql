-- Layouts per type and screen (roadmap 1.7): attribute groups and their order.

create table type_layout (
    type_id    uuid        not null references type_def (id),
    screen     text        not null check (screen in ('DEFAULT', 'CREATE', 'EDIT', 'INFO')),
    tenant_id  uuid        not null references tenant (id),
    -- [{key, labels, columns, attributes: [name, ...]}, ...], validated by the types module.
    groups     jsonb       not null,
    -- Attribute names not shown on this screen.
    hidden     jsonb       not null default '[]',
    updated_at timestamptz not null default now(),
    primary key (type_id, screen)
);

-- Out of the box: one default layout each for Part and Document.
insert into type_layout (type_id, screen, tenant_id, groups)
values ('00000000-0000-7000-8000-000000000102', 'DEFAULT', '00000000-0000-7000-8000-000000000001',
        '[{"key": "general", "labels": {"en": "General"}, "columns": 2,
           "attributes": ["description", "source", "defaultUnit", "material", "mass"]},
          {"key": "structure", "labels": {"en": "Structure and tracking"}, "columns": 2,
           "attributes": ["assemblyMode", "traceCode", "endItem", "phantom", "gatheringPart"]}]'),
       ('00000000-0000-7000-8000-000000000101', 'DEFAULT', '00000000-0000-7000-8000-000000000001',
        '[{"key": "general", "labels": {"en": "General"}, "columns": 2,
           "attributes": ["title", "category", "department", "description"]}]');
