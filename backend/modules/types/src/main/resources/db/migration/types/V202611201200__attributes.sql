-- Attributes, enumerations and units (roadmap 1.1-1.4, ADR 0015).
--
--   unit_of_measure   units grouped by dimension, with exact conversion to the dimension's base unit
--   enumeration       legal value lists, labels per locale
--   attribute_def     typed attributes on a type, inherited by its subtypes
--
-- Seeds below are the out-of-the-box (OOTB) configuration for the default tenant. Admins can extend,
-- constrain or deactivate it; system rows cannot be deleted or change data type.

create table unit_of_measure (
    tenant_id uuid    not null references tenant (id),
    code      text    not null,
    dimension text    not null,
    labels    jsonb   not null,
    -- value_in_base = value * factor + offset
    factor    numeric not null check (factor > 0),
    "offset"  numeric not null default 0,
    system    boolean not null default false,
    primary key (tenant_id, code)
);

create table enumeration (
    id         uuid primary key,
    tenant_id  uuid        not null references tenant (id),
    name       text        not null,
    labels     jsonb       not null,
    system     boolean     not null default false,
    created_at timestamptz not null default now(),
    unique (tenant_id, name)
);

create table enumeration_value (
    enumeration_id uuid    not null references enumeration (id),
    key            text    not null,
    labels         jsonb   not null,
    sort_order     int     not null,
    active         boolean not null default true,
    primary key (enumeration_id, key)
);

create table attribute_def (
    id             uuid primary key,
    tenant_id      uuid        not null references tenant (id),
    type_id        uuid        not null references type_def (id),
    name           text        not null,
    labels         jsonb       not null,
    description    text,
    data_type      text        not null check (data_type in
        ('STRING', 'TEXT', 'INTEGER', 'REAL', 'QUANTITY', 'BOOLEAN', 'DATE', 'TIMESTAMP', 'URL', 'ENUMERATION')),
    multi_valued   boolean     not null default false,
    required       boolean     not null default false,
    -- Value is fixed once the object is created.
    immutable      boolean     not null default false,
    min_length     int check (min_length >= 0),
    max_length     int check (max_length > 0),
    pattern        text,
    -- For INTEGER/REAL: plain numbers. For QUANTITY: in the dimension's base unit.
    min_value      numeric,
    max_value      numeric,
    enumeration_id uuid references enumeration (id),
    dimension      text,
    display_unit   text,
    default_value  jsonb,
    sort_order     int         not null default 0,
    active         boolean     not null default true,
    system         boolean     not null default false,
    created_at     timestamptz not null default now(),
    unique (type_id, name),
    check ((data_type = 'ENUMERATION') = (enumeration_id is not null)),
    check ((data_type = 'QUANTITY') = (dimension is not null))
);

create index attribute_def_type_idx on attribute_def (type_id);

-- ---------------------------------------------------------------------------------------------
-- Seeds: units (base unit has factor 1)
-- ---------------------------------------------------------------------------------------------
insert into unit_of_measure (tenant_id, code, dimension, labels, factor, "offset", system)
select '00000000-0000-7000-8000-000000000001', code, dimension, jsonb_build_object('en', label), factor, off, true
from (values
    ('m', 'LENGTH', 'metre', 1, 0), ('mm', 'LENGTH', 'millimetre', 0.001, 0), ('cm', 'LENGTH', 'centimetre', 0.01, 0),
    ('km', 'LENGTH', 'kilometre', 1000, 0), ('in', 'LENGTH', 'inch', 0.0254, 0), ('ft', 'LENGTH', 'foot', 0.3048, 0),
    ('kg', 'MASS', 'kilogram', 1, 0), ('g', 'MASS', 'gram', 0.001, 0), ('t', 'MASS', 'tonne', 1000, 0),
    ('lb', 'MASS', 'pound', 0.45359237, 0), ('oz', 'MASS', 'ounce', 0.028349523125, 0),
    ('s', 'TIME', 'second', 1, 0), ('ms', 'TIME', 'millisecond', 0.001, 0), ('min', 'TIME', 'minute', 60, 0),
    ('h', 'TIME', 'hour', 3600, 0), ('d', 'TIME', 'day', 86400, 0),
    ('K', 'TEMPERATURE', 'kelvin', 1, 0), ('degC', 'TEMPERATURE', 'degree Celsius', 1, 273.15),
    ('degF', 'TEMPERATURE', 'degree Fahrenheit', 0.5555555555555555555555555556, 255.3722222222222222222222222),
    ('m2', 'AREA', 'square metre', 1, 0), ('mm2', 'AREA', 'square millimetre', 0.000001, 0),
    ('cm2', 'AREA', 'square centimetre', 0.0001, 0), ('in2', 'AREA', 'square inch', 0.00064516, 0),
    ('ft2', 'AREA', 'square foot', 0.09290304, 0),
    ('m3', 'VOLUME', 'cubic metre', 1, 0), ('l', 'VOLUME', 'litre', 0.001, 0), ('ml', 'VOLUME', 'millilitre', 0.000001, 0),
    ('cm3', 'VOLUME', 'cubic centimetre', 0.000001, 0), ('mm3', 'VOLUME', 'cubic millimetre', 0.000000001, 0),
    ('in3', 'VOLUME', 'cubic inch', 0.000016387064, 0), ('gal', 'VOLUME', 'US gallon', 0.003785411784, 0),
    ('N', 'FORCE', 'newton', 1, 0), ('kN', 'FORCE', 'kilonewton', 1000, 0), ('lbf', 'FORCE', 'pound-force', 4.4482216152605, 0),
    ('Pa', 'PRESSURE', 'pascal', 1, 0), ('kPa', 'PRESSURE', 'kilopascal', 1000, 0), ('MPa', 'PRESSURE', 'megapascal', 1000000, 0),
    ('bar', 'PRESSURE', 'bar', 100000, 0), ('psi', 'PRESSURE', 'pound per square inch', 6894.757293168361, 0),
    ('rad', 'ANGLE', 'radian', 1, 0), ('deg', 'ANGLE', 'degree', 0.017453292519943295, 0),
    ('N.m', 'TORQUE', 'newton metre', 1, 0), ('lbf.ft', 'TORQUE', 'pound-force foot', 1.3558179483314004, 0),
    ('A', 'CURRENT', 'ampere', 1, 0), ('mA', 'CURRENT', 'milliampere', 0.001, 0),
    ('V', 'VOLTAGE', 'volt', 1, 0), ('mV', 'VOLTAGE', 'millivolt', 0.001, 0), ('kV', 'VOLTAGE', 'kilovolt', 1000, 0),
    ('W', 'POWER', 'watt', 1, 0), ('kW', 'POWER', 'kilowatt', 1000, 0), ('hp', 'POWER', 'horsepower', 745.69987158227022, 0),
    ('J', 'ENERGY', 'joule', 1, 0), ('kJ', 'ENERGY', 'kilojoule', 1000, 0), ('kWh', 'ENERGY', 'kilowatt hour', 3600000, 0),
    ('m/s', 'SPEED', 'metre per second', 1, 0), ('km/h', 'SPEED', 'kilometre per hour', 0.2777777777777777777777777778, 0),
    ('mph', 'SPEED', 'mile per hour', 0.44704, 0),
    ('Hz', 'FREQUENCY', 'hertz', 1, 0), ('kHz', 'FREQUENCY', 'kilohertz', 1000, 0), ('MHz', 'FREQUENCY', 'megahertz', 1000000, 0),
    ('kg/m3', 'DENSITY', 'kilogram per cubic metre', 1, 0), ('g/cm3', 'DENSITY', 'gram per cubic centimetre', 1000, 0)
) as u (code, dimension, label, factor, off);

-- ---------------------------------------------------------------------------------------------
-- Seeds: enumerations. Deterministic ids from md5 so seeds and docs can refer to them.
-- ---------------------------------------------------------------------------------------------
insert into enumeration (id, tenant_id, name, labels, system)
select md5('enum:' || name)::uuid, '00000000-0000-7000-8000-000000000001', name, jsonb_build_object('en', label), true
from (values
    ('partSource', 'Part source'),
    ('unitOfUse', 'Unit of use'),
    ('assemblyMode', 'Assembly mode'),
    ('traceCode', 'Trace code'),
    ('documentCategory', 'Document category'),
    ('department', 'Department')
) as e (name, label);

insert into enumeration_value (enumeration_id, key, labels, sort_order)
select md5('enum:' || enum_name)::uuid, key, jsonb_build_object('en', label), ord
from (values
    ('partSource', 'make', 'Make', 1), ('partSource', 'buy', 'Buy', 2),
    ('partSource', 'buySingleSource', 'Buy (single source)', 3),
    ('unitOfUse', 'each', 'Each', 1), ('unitOfUse', 'asNeeded', 'As needed', 2), ('unitOfUse', 'kg', 'Kilograms', 3),
    ('unitOfUse', 'm', 'Metres', 4), ('unitOfUse', 'l', 'Litres', 5), ('unitOfUse', 'm2', 'Square metres', 6),
    ('unitOfUse', 'm3', 'Cubic metres', 7),
    ('assemblyMode', 'separable', 'Separable', 1), ('assemblyMode', 'inseparable', 'Inseparable', 2),
    ('assemblyMode', 'component', 'Component', 3),
    ('traceCode', 'untraced', 'Untraced', 1), ('traceCode', 'lot', 'Lot', 2), ('traceCode', 'serial', 'Serial number', 3),
    ('traceCode', 'lotSerial', 'Lot and serial number', 4),
    ('documentCategory', 'general', 'General', 1), ('documentCategory', 'specification', 'Specification', 2),
    ('documentCategory', 'drawing', 'Drawing', 3), ('documentCategory', 'report', 'Report', 4),
    ('documentCategory', 'manual', 'Manual', 5), ('documentCategory', 'plan', 'Plan', 6),
    ('documentCategory', 'procedure', 'Procedure', 7), ('documentCategory', 'certificate', 'Certificate', 8),
    ('department', 'engineering', 'Engineering', 1), ('department', 'manufacturing', 'Manufacturing', 2),
    ('department', 'quality', 'Quality', 3), ('department', 'procurement', 'Procurement', 4),
    ('department', 'service', 'Service', 5), ('department', 'marketing', 'Marketing', 6)
) as v (enum_name, key, label, ord);

-- ---------------------------------------------------------------------------------------------
-- Seeds: OOTB attributes of Part (...0102) and Document (...0101)
-- ---------------------------------------------------------------------------------------------
insert into attribute_def
    (id, tenant_id, type_id, name, labels, description, data_type, max_length, enumeration_id, dimension,
     display_unit, default_value, min_value, sort_order, system)
select md5('attr:' || type_name || '.' || name)::uuid, '00000000-0000-7000-8000-000000000001',
       case type_name when 'Part' then '00000000-0000-7000-8000-000000000102'::uuid
                      else '00000000-0000-7000-8000-000000000101'::uuid end,
       name, jsonb_build_object('en', label), description, data_type, max_length,
       case when enum_name is null then null else md5('enum:' || enum_name)::uuid end,
       dimension, display_unit, default_value::jsonb, min_value, ord, true
from (values
    ('Part', 'description', 'Description', 'What the part is and what it is for.', 'TEXT', 4000, null, null, null, null, null, 10),
    ('Part', 'source', 'Source', 'Whether the part is made in-house or bought.', 'ENUMERATION', null, 'partSource', null, null, '"make"', null, 20),
    ('Part', 'defaultUnit', 'Default unit', 'Unit in which the part is used in assemblies.', 'ENUMERATION', null, 'unitOfUse', null, null, '"each"', null, 30),
    ('Part', 'assemblyMode', 'Assembly mode', 'Separable assemblies can be taken apart in service; inseparable cannot.', 'ENUMERATION', null, 'assemblyMode', null, null, '"separable"', null, 40),
    ('Part', 'endItem', 'End item', 'A top-level product that is sold or delivered.', 'BOOLEAN', null, null, null, null, 'false', null, 50),
    ('Part', 'phantom', 'Phantom', 'A logical grouping that is not built or stocked on its own.', 'BOOLEAN', null, null, null, null, 'false', null, 60),
    ('Part', 'gatheringPart', 'Gathering part', 'Collects parts for convenience; not a physical item.', 'BOOLEAN', null, null, null, null, 'false', null, 70),
    ('Part', 'traceCode', 'Trace code', 'How individual instances are tracked.', 'ENUMERATION', null, 'traceCode', null, null, '"untraced"', null, 80),
    ('Part', 'material', 'Material', null, 'STRING', 200, null, null, null, null, null, 90),
    ('Part', 'mass', 'Mass', null, 'QUANTITY', null, null, 'MASS', 'kg', null, 0, 100),
    ('Document', 'title', 'Title', null, 'STRING', 500, null, null, null, null, null, 10),
    ('Document', 'description', 'Description', null, 'TEXT', 4000, null, null, null, null, null, 20),
    ('Document', 'category', 'Category', null, 'ENUMERATION', null, 'documentCategory', null, null, '"general"', null, 30),
    ('Document', 'department', 'Department', null, 'ENUMERATION', null, 'department', null, null, null, null, 40)
) as a (type_name, name, label, description, data_type, max_length, enum_name, dimension, display_unit, default_value, min_value, ord);
