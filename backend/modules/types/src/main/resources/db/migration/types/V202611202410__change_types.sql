-- Change objects (roadmap 5.8): problem reports, change requests, change notices and change tasks
-- are versioned objects of their own base class, numbered by type.
alter table type_def drop constraint type_def_base_class_check;
alter table type_def add constraint type_def_base_class_check check (base_class in ('DOCUMENT', 'PART', 'CHANGE'));

insert into type_def (id, tenant_id, parent_id, name, display_name, base_class, description, labels, instantiable)
values ('00000000-0000-7000-8000-000000000103', '00000000-0000-7000-8000-000000000001', null, 'Change', 'Change',
        'CHANGE', 'Everything about changing released data.', '{"fr": "Modification", "de": "Änderung"}', false),
       ('00000000-0000-7000-8000-000000000104', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000103', 'ProblemReport', 'Problem report', 'CHANGE',
        'Something is wrong or could be better: anyone can report it.',
        '{"fr": "Rapport de problème", "de": "Problembericht"}', true),
       ('00000000-0000-7000-8000-000000000105', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000103', 'ChangeRequest', 'Change request', 'CHANGE',
        'A proposed change, analysed and decided by the change board.',
        '{"fr": "Demande de modification", "de": "Änderungsantrag"}', true),
       ('00000000-0000-7000-8000-000000000106', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000103', 'ChangeNotice', 'Change notice', 'CHANGE',
        'The approved change, planned as tasks and carried out.',
        '{"fr": "Avis de modification", "de": "Änderungsmitteilung"}', true),
       ('00000000-0000-7000-8000-000000000107', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000103', 'ChangeTask', 'Change task', 'CHANGE',
        'One piece of work of a change notice: revise and release some objects.',
        '{"fr": "Tâche de modification", "de": "Änderungsaufgabe"}', true);

insert into numbering_scheme (id, tenant_id, name, prefix, padding, next_value)
values ('00000000-0000-7000-8000-000000000204', '00000000-0000-7000-8000-000000000001', 'Problem reports', 'PR-', 5, 1),
       ('00000000-0000-7000-8000-000000000205', '00000000-0000-7000-8000-000000000001', 'Change requests', 'CR-', 5, 1),
       ('00000000-0000-7000-8000-000000000206', '00000000-0000-7000-8000-000000000001', 'Change notices', 'CN-', 5, 1),
       ('00000000-0000-7000-8000-000000000207', '00000000-0000-7000-8000-000000000001', 'Change tasks', 'CT-', 5, 1);

insert into type_rule (type_id, tenant_id, numbering_scheme_id, revision_scheme, allow_manual_number)
values ('00000000-0000-7000-8000-000000000103', '00000000-0000-7000-8000-000000000001', null, 'ALPHABETIC_ASME', false),
       ('00000000-0000-7000-8000-000000000104', '00000000-0000-7000-8000-000000000001', '00000000-0000-7000-8000-000000000204', null, null),
       ('00000000-0000-7000-8000-000000000105', '00000000-0000-7000-8000-000000000001', '00000000-0000-7000-8000-000000000205', null, null),
       ('00000000-0000-7000-8000-000000000106', '00000000-0000-7000-8000-000000000001', '00000000-0000-7000-8000-000000000206', null, null),
       ('00000000-0000-7000-8000-000000000107', '00000000-0000-7000-8000-000000000001', '00000000-0000-7000-8000-000000000207', null, null);
