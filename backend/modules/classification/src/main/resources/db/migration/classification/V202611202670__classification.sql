-- Classification (roadmap 7.11): a tree of classes whose attributes are inherited down it; parts
-- are classified in one class, with values kept per iteration.
create table class_node (
    id          uuid        primary key,
    tenant_id   uuid        not null,
    parent_id   uuid        references class_node (id),
    code        text        not null,
    name        text        not null,
    description text,
    position    int         not null default 0,
    created_at  timestamptz not null,
    unique (tenant_id, code)
);

create table class_attribute (
    id        uuid    primary key,
    class_id  uuid    not null references class_node (id) on delete cascade,
    key       text    not null,
    label     text    not null,
    -- NUMBER, TEXT, BOOLEAN, ENUM
    data_type text    not null check (data_type in ('NUMBER', 'TEXT', 'BOOLEAN', 'ENUM')),
    unit      text,
    allowed   text[]  not null default '{}',
    required  boolean not null default false,
    position  int     not null,
    unique (class_id, key)
);

create table class_value (
    iteration_id uuid        primary key references object_iteration (id) on delete cascade,
    tenant_id    uuid        not null,
    master_id    uuid        not null references object_master (id) on delete cascade,
    class_id     uuid        not null references class_node (id),
    "values"     jsonb       not null default '{}',
    updated_at   timestamptz not null,
    updated_by   text        not null
);

create index class_value_class on class_value (class_id);
