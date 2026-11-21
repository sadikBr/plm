-- Variances (roadmap 5.10): deviations and waivers, a change kind of their own.
insert into type_def (id, tenant_id, parent_id, name, display_name, base_class, description, labels, instantiable)
values ('00000000-0000-7000-8000-000000000108', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000103', 'Variance', 'Variance', 'CHANGE',
        'Permission to depart from the released definition for a limited scope (a deviation before, a waiver after).',
        '{"fr": "Dérogation", "de": "Abweichungsgenehmigung"}', true);
insert into numbering_scheme (id, tenant_id, name, prefix, padding, next_value)
values ('00000000-0000-7000-8000-000000000208', '00000000-0000-7000-8000-000000000001', 'Variances', 'VAR-', 5, 1);
insert into type_rule (type_id, tenant_id, numbering_scheme_id, revision_scheme, allow_manual_number)
values ('00000000-0000-7000-8000-000000000108', '00000000-0000-7000-8000-000000000001',
        '00000000-0000-7000-8000-000000000208', null, null);
