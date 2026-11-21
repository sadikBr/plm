-- Variant specifications (roadmap 7.8): a named selection of choices for a configurable product,
-- with how to read its structure (a configuration specification, effectivity included).
create table variant_spec (
    id            uuid        primary key,
    tenant_id     uuid        not null,
    product_id    uuid        not null references object_master (id) on delete cascade,
    name          text        not null,
    description   text,
    selection     jsonb       not null,
    spec_type     text        not null default 'LATEST',
    spec_state    text,
    eff_kind      text,
    eff_at        text,
    end_item_id   uuid,
    created_at    timestamptz not null,
    created_by    text        not null,
    updated_at    timestamptz not null,
    updated_by    text        not null,
    unique (product_id, name)
);
