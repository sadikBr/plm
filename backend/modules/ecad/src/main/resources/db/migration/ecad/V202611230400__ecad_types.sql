-- Electrical CAD documents (roadmap 8.7): a project, its schematic sheets and its board.
insert into type_def (id, tenant_id, parent_id, name, display_name, base_class, description, labels, instantiable)
values ('00000000-0000-7000-8000-000000000110', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-00000000010b', 'EcadProject', 'ECAD project', 'DOCUMENT',
        'An electronics project: its schematic sheets and its board.', '{"fr": "Projet électronique", "de": "Elektronikprojekt"}', true),
       ('00000000-0000-7000-8000-000000000111', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-00000000010c', 'Schematic', 'Schematic', 'DOCUMENT',
        'A schematic sheet: components and their connections.', '{"fr": "Schéma", "de": "Schaltplan"}', true),
       ('00000000-0000-7000-8000-000000000112', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-00000000010b', 'Board', 'Printed circuit board', 'DOCUMENT',
        'A board layout: footprints placed and routed.', '{"fr": "Circuit imprimé", "de": "Leiterplatte"}', true);

update type_def set icon = 'cpu' where id = '00000000-0000-7000-8000-000000000112';
update type_def set icon = 'circuit-board' where id = '00000000-0000-7000-8000-000000000110';
update type_def set icon = 'cable' where id = '00000000-0000-7000-8000-000000000111';
