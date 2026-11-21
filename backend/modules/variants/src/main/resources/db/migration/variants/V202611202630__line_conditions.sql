-- 150% structures (roadmap 7.7): a line of a configurable part applies only when its condition
-- over the set's choices holds.
create table line_condition (
    line_id    uuid        primary key references usage_link (id) on delete cascade,
    tenant_id  uuid        not null,
    set_id     uuid        not null references option_set (id),
    expression text        not null,
    -- The option codes it refers to: an option used here can't be removed.
    options    text[]      not null,
    updated_at timestamptz not null,
    updated_by text        not null
);

create index line_condition_set on line_condition (set_id);
