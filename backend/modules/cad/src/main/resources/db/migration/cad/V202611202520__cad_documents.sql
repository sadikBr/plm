-- CAD documents (roadmap 6.1): documents holding CAD models, of three kinds, numbered CAD-.
insert into type_def (id, tenant_id, parent_id, name, display_name, base_class, description, labels, instantiable)
values ('00000000-0000-7000-8000-000000000109', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000101', 'CadDocument', 'CAD document', 'DOCUMENT',
        'A CAD model or drawing, with the models it depends on.', '{"fr": "Document CAO", "de": "CAD-Dokument"}', false),
       ('00000000-0000-7000-8000-00000000010a', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000109', 'CadPart', 'CAD part', 'DOCUMENT',
        'A single model: one solid or body.', '{"fr": "Pièce CAO", "de": "CAD-Teil"}', true),
       ('00000000-0000-7000-8000-00000000010b', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000109', 'CadAssembly', 'CAD assembly', 'DOCUMENT',
        'Models placed together: its components, each with its position.', '{"fr": "Assemblage CAO", "de": "CAD-Baugruppe"}', true),
       ('00000000-0000-7000-8000-00000000010c', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000109', 'CadDrawing', 'CAD drawing', 'DOCUMENT',
        'A 2D drawing of one or more models.', '{"fr": "Mise en plan CAO", "de": "CAD-Zeichnung"}', true);
update type_def set icon = 'shapes' where id = '00000000-0000-7000-8000-000000000109';
update type_def set icon = 'box' where id = '00000000-0000-7000-8000-00000000010a';
update type_def set icon = 'boxes' where id = '00000000-0000-7000-8000-00000000010b';
update type_def set icon = 'drafting-compass' where id = '00000000-0000-7000-8000-00000000010c';

insert into numbering_scheme (id, tenant_id, name, prefix, padding, next_value)
values ('00000000-0000-7000-8000-000000000209', '00000000-0000-7000-8000-000000000001', 'CAD documents', 'CAD-', 6, 1);

insert into type_rule (type_id, tenant_id, numbering_scheme_id, revision_scheme, allow_manual_number)
values ('00000000-0000-7000-8000-000000000109', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000209', null, true);

insert into attribute_def
    (id, tenant_id, type_id, name, labels, description, data_type, max_length, enumeration_id, dimension,
     display_unit, default_value, min_value, sort_order, system)
values (md5('attr:CadDocument.authoringApplication')::uuid, '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000109', 'authoringApplication',
        '{"en": "Authoring application", "fr": "Application de création", "de": "Autorenanwendung"}',
        'The CAD application the model was made in.', 'STRING', 100, null, null, null, null, null, 110, true),
       (md5('attr:CadDocument.fileFormat')::uuid, '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000109', 'fileFormat',
        '{"en": "File format", "fr": "Format de fichier", "de": "Dateiformat"}',
        'e.g. STEP AP242, SLDPRT, PRT.', 'STRING', 50, null, null, null, null, null, 120, true);

-- What a CAD document's version depends on: an assembly's components (each placement one row), a
-- drawing's models, other references. Kept per iteration, edited on the working copy.
create table cad_dependency (
    id                  uuid        primary key,
    tenant_id           uuid        not null,
    parent_iteration_id uuid        not null references object_iteration (id) on delete cascade,
    child_master_id     uuid        not null references object_master (id),
    kind                text        not null check (kind in ('COMPONENT', 'DRAWING', 'REFERENCE')),
    -- Needed to open the parent (a component); optional ones may be left out (a reference).
    required            boolean     not null default true,
    -- The occurrence's name in the assembly, e.g. BOLT:3.
    occurrence          text,
    -- Column-major 4x4 placement of the component in the parent, in millimetres; null: identity.
    transform           jsonb,
    position            int         not null,
    created_at          timestamptz not null,
    created_by          text        not null
);
create index cad_dependency_parent on cad_dependency (parent_iteration_id, position);
create index cad_dependency_child on cad_dependency (child_master_id);
