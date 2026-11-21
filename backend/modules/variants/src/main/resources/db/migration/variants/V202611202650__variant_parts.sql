-- Generated variants (roadmap 7.9): which part a variant was made from, at which iteration, for
-- which choices (the signature: the options its structure depends on), so it can be reused.
create table variant_part (
    variant_id     uuid        primary key references object_master (id) on delete cascade,
    tenant_id      uuid        not null,
    base_id        uuid        not null references object_master (id) on delete cascade,
    base_iteration uuid        not null,
    signature      text        not null,
    selection      jsonb       not null,
    spec_id        uuid        references variant_spec (id) on delete set null,
    created_at     timestamptz not null,
    created_by     text        not null
);

create index variant_part_reuse on variant_part (base_id, base_iteration, signature);
