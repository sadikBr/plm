-- Options and choices (roadmap 7.5): option sets kept in a library or product, assigned to the
-- configurable parts whose structures they drive.
create table option_set (
    id          uuid        primary key,
    tenant_id   uuid        not null,
    context_id  uuid        not null references context (id) on delete cascade,
    name        text        not null,
    description text,
    created_at  timestamptz not null,
    created_by  text        not null,
    updated_at  timestamptz not null
);

create table option_def (
    id         uuid    primary key,
    set_id     uuid    not null references option_set (id) on delete cascade,
    -- Short, used in conditions: MOTOR.
    code       text    not null,
    name       text    not null,
    -- A variant must choose one of its choices.
    required   boolean not null default true,
    position   int     not null,
    unique (set_id, code)
);

create table option_choice (
    id        uuid    primary key,
    option_id uuid    not null references option_def (id) on delete cascade,
    code      text    not null,
    name      text    not null,
    position  int     not null,
    -- Retired choices stay readable in old conditions and variants, but can't be chosen anew.
    retired   boolean not null default false,
    unique (option_id, code)
);

create table configurable_part (
    master_id uuid primary key references object_master (id) on delete cascade,
    tenant_id uuid not null,
    set_id    uuid not null references option_set (id)
);
