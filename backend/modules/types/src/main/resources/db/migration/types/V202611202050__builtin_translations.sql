-- French and German labels for what ships out of the box (roadmap 3.12). Only adds languages:
-- a label an administrator already translated is kept.

update type_def t set labels = jsonb_build_object('fr', v.fr, 'de', v.de) || t.labels
from (values ('00000000-0000-7000-8000-000000000101'::uuid, 'Document', 'Dokument'),
             ('00000000-0000-7000-8000-000000000102'::uuid, 'Pièce', 'Teil')) as v (id, fr, de)
where t.id = v.id;

update attribute_def a set labels = jsonb_build_object('fr', v.fr, 'de', v.de) || a.labels
from (values
    ('Part', 'description', 'Description', 'Beschreibung'),
    ('Part', 'source', 'Provenance', 'Beschaffungsart'),
    ('Part', 'defaultUnit', 'Unité par défaut', 'Standardeinheit'),
    ('Part', 'assemblyMode', 'Mode d''assemblage', 'Montageart'),
    ('Part', 'endItem', 'Article final', 'Endprodukt'),
    ('Part', 'phantom', 'Fantôme', 'Phantombaugruppe'),
    ('Part', 'gatheringPart', 'Pièce de regroupement', 'Sammelteil'),
    ('Part', 'traceCode', 'Code de traçabilité', 'Rückverfolgung'),
    ('Part', 'material', 'Matériau', 'Werkstoff'),
    ('Part', 'mass', 'Masse', 'Masse'),
    ('Document', 'title', 'Titre', 'Titel'),
    ('Document', 'description', 'Description', 'Beschreibung'),
    ('Document', 'category', 'Catégorie', 'Kategorie'),
    ('Document', 'department', 'Service', 'Abteilung')
) as v (type_name, name, fr, de)
where a.id = md5('attr:' || v.type_name || '.' || v.name)::uuid;

update enumeration e set labels = jsonb_build_object('fr', v.fr, 'de', v.de) || e.labels
from (values
    ('partSource', 'Provenance de pièce', 'Beschaffungsart'),
    ('unitOfUse', 'Unité d''utilisation', 'Verwendungseinheit'),
    ('assemblyMode', 'Mode d''assemblage', 'Montageart'),
    ('traceCode', 'Code de traçabilité', 'Rückverfolgung'),
    ('documentCategory', 'Catégorie de document', 'Dokumentkategorie'),
    ('department', 'Service', 'Abteilung')
) as v (name, fr, de)
where e.id = md5('enum:' || v.name)::uuid;

update enumeration_value ev set labels = jsonb_build_object('fr', v.fr, 'de', v.de) || ev.labels
from (values
    ('partSource', 'make', 'Fabriquée', 'Eigenfertigung'), ('partSource', 'buy', 'Achetée', 'Zukauf'),
    ('partSource', 'buySingleSource', 'Achetée (source unique)', 'Zukauf (Einzelquelle)'),
    ('unitOfUse', 'each', 'Unité', 'Stück'), ('unitOfUse', 'asNeeded', 'Selon besoin', 'Nach Bedarf'),
    ('unitOfUse', 'kg', 'Kilogrammes', 'Kilogramm'), ('unitOfUse', 'm', 'Mètres', 'Meter'),
    ('unitOfUse', 'l', 'Litres', 'Liter'), ('unitOfUse', 'm2', 'Mètres carrés', 'Quadratmeter'),
    ('unitOfUse', 'm3', 'Mètres cubes', 'Kubikmeter'),
    ('assemblyMode', 'separable', 'Démontable', 'Lösbar'), ('assemblyMode', 'inseparable', 'Indémontable', 'Unlösbar'),
    ('assemblyMode', 'component', 'Composant', 'Komponente'),
    ('traceCode', 'untraced', 'Non tracé', 'Nicht verfolgt'), ('traceCode', 'lot', 'Lot', 'Charge'),
    ('traceCode', 'serial', 'Numéro de série', 'Seriennummer'),
    ('traceCode', 'lotSerial', 'Lot et numéro de série', 'Charge und Seriennummer'),
    ('documentCategory', 'general', 'Général', 'Allgemein'), ('documentCategory', 'specification', 'Spécification', 'Spezifikation'),
    ('documentCategory', 'drawing', 'Plan', 'Zeichnung'), ('documentCategory', 'report', 'Rapport', 'Bericht'),
    ('documentCategory', 'manual', 'Manuel', 'Handbuch'), ('documentCategory', 'plan', 'Planification', 'Plan'),
    ('documentCategory', 'procedure', 'Procédure', 'Verfahren'), ('documentCategory', 'certificate', 'Certificat', 'Zertifikat'),
    ('department', 'engineering', 'Bureau d''études', 'Konstruktion'), ('department', 'manufacturing', 'Production', 'Fertigung'),
    ('department', 'quality', 'Qualité', 'Qualität'), ('department', 'procurement', 'Achats', 'Einkauf'),
    ('department', 'service', 'Après-vente', 'Kundendienst'), ('department', 'marketing', 'Marketing', 'Marketing')
) as v (enum_name, key, fr, de)
where ev.enumeration_id = md5('enum:' || v.enum_name)::uuid and ev.key = v.key;

-- The out-of-the-box layout groups, wherever a layout still uses them.
update type_layout l set groups = (
    select jsonb_agg(
        case g ->> 'key'
            when 'general' then jsonb_set(g, '{labels}', '{"fr": "Général", "de": "Allgemein"}'::jsonb || (g -> 'labels'))
            when 'structure' then jsonb_set(g, '{labels}', '{"fr": "Structure et traçabilité", "de": "Struktur und Rückverfolgung"}'::jsonb || (g -> 'labels'))
            else g
        end order by ord)
    from jsonb_array_elements(l.groups) with ordinality as x (g, ord))
where jsonb_typeof(l.groups) = 'array' and jsonb_array_length(l.groups) > 0;
