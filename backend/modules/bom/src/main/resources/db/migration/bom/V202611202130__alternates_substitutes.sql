-- Replacements (roadmap 4.8).
-- Alternates: a part that may replace another anywhere it's used. Belongs to the part master, not an
-- iteration; one row per direction (a two-way alternate is two rows).
create table part_alternate (
    tenant_id    uuid        not null,
    part_id      uuid        not null references object_master (id) on delete cascade,
    alternate_id uuid        not null references object_master (id) on delete cascade,
    notes        text,
    created_at   timestamptz not null,
    created_by   text        not null,
    primary key (part_id, alternate_id),
    check (part_id <> alternate_id)
);

create index part_alternate_alternate on part_alternate (alternate_id);

-- Substitutes: a part that may replace the child on one line of one structure only.
create table usage_substitute (
    usage_id      uuid    not null references usage_link (id) on delete cascade,
    substitute_id uuid    not null references object_master (id),
    -- Null: the line's quantity.
    quantity      numeric check (quantity > 0),
    notes         text,
    primary key (usage_id, substitute_id)
);

create index usage_substitute_part on usage_substitute (substitute_id);
