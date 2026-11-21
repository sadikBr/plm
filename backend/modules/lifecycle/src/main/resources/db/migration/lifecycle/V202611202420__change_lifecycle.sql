-- The lifecycle of change objects (roadmap 5.8).
insert into lifecycle_template (id, tenant_id, name, labels, initial_state, system)
values ('00000000-0000-7000-8000-000000000302', '00000000-0000-7000-8000-000000000001', 'Change',
        '{"en": "Change", "fr": "Modification", "de": "Änderung"}', 'OPEN', true);

insert into lifecycle_state (template_id, key, labels, editable, revisable, sort_order)
values ('00000000-0000-7000-8000-000000000302', 'OPEN', '{"en": "Open", "fr": "Ouvert", "de": "Offen"}', true, false, 1),
       ('00000000-0000-7000-8000-000000000302', 'UNDER_REVIEW', '{"en": "Under Review", "fr": "En revue", "de": "In Prüfung"}', false, false, 2),
       ('00000000-0000-7000-8000-000000000302', 'APPROVED', '{"en": "Approved", "fr": "Approuvé", "de": "Genehmigt"}', false, false, 3),
       ('00000000-0000-7000-8000-000000000302', 'IMPLEMENTATION', '{"en": "Implementation", "fr": "Mise en œuvre", "de": "Umsetzung"}', true, false, 4),
       ('00000000-0000-7000-8000-000000000302', 'RESOLVED', '{"en": "Resolved", "fr": "Résolu", "de": "Erledigt"}', false, false, 5),
       ('00000000-0000-7000-8000-000000000302', 'REJECTED', '{"en": "Rejected", "fr": "Rejeté", "de": "Abgelehnt"}', false, false, 6),
       ('00000000-0000-7000-8000-000000000302', 'CANCELLED', '{"en": "Cancelled", "fr": "Annulé", "de": "Storniert"}', false, false, 7);

insert into lifecycle_transition (template_id, from_state, to_state)
values ('00000000-0000-7000-8000-000000000302', 'OPEN', 'UNDER_REVIEW'),
       ('00000000-0000-7000-8000-000000000302', 'OPEN', 'IMPLEMENTATION'),
       ('00000000-0000-7000-8000-000000000302', 'OPEN', 'CANCELLED'),
       ('00000000-0000-7000-8000-000000000302', 'UNDER_REVIEW', 'OPEN'),
       ('00000000-0000-7000-8000-000000000302', 'UNDER_REVIEW', 'APPROVED'),
       ('00000000-0000-7000-8000-000000000302', 'UNDER_REVIEW', 'REJECTED'),
       ('00000000-0000-7000-8000-000000000302', 'APPROVED', 'IMPLEMENTATION'),
       ('00000000-0000-7000-8000-000000000302', 'APPROVED', 'RESOLVED'),
       ('00000000-0000-7000-8000-000000000302', 'IMPLEMENTATION', 'RESOLVED'),
       ('00000000-0000-7000-8000-000000000302', 'IMPLEMENTATION', 'CANCELLED');

insert into type_lifecycle (type_id, tenant_id, template_id)
values ('00000000-0000-7000-8000-000000000103', '00000000-0000-7000-8000-000000000001', '00000000-0000-7000-8000-000000000302');
