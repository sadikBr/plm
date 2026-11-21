-- French and German names of the out-of-the-box lifecycle states (roadmap 3.12), in every
-- lifecycle that still uses their English names. Existing translations are kept.
update lifecycle_state s set labels = jsonb_build_object('fr', v.fr, 'de', v.de) || s.labels
from (values ('In Work', 'En cours', 'In Arbeit'),
             ('Under Review', 'En revue', 'In Prüfung'),
             ('Released', 'Publié', 'Freigegeben'),
             ('Obsolete', 'Obsolète', 'Veraltet'),
             ('Canceled', 'Annulé', 'Storniert')) as v (en, fr, de)
where s.labels ->> 'en' = v.en;

update lifecycle_template t set labels = '{"fr": "De base", "de": "Basis"}'::jsonb || t.labels
where t.labels ->> 'en' = 'Basic';
