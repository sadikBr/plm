-- Redlines (roadmap 5.11): edits a change proposes to an object's structure or attributes, applied
-- when the change is resolved.
create table change_redline (
    id            uuid primary key,
    tenant_id     uuid        not null,
    change_id     uuid        not null,
    op            text        not null check (op in ('ADD', 'REMOVE', 'QUANTITY', 'REPLACE', 'SET_ATTRIBUTE')),
    -- The part whose structure changes, or the object whose attribute does.
    target_id     uuid        not null,
    child_id      uuid,
    new_child_id  uuid,
    quantity      numeric,
    attribute     text,
    value         jsonb,
    note          text,
    created_at    timestamptz not null,
    created_by    text        not null,
    applied_at    timestamptz,
    -- The target's version the redline produced.
    applied_version text
);
create index change_redline_change_idx on change_redline (change_id);
