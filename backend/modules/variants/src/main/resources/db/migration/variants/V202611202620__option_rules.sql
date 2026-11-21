-- Option rules (roadmap 7.6): when a choice is made, others must be made too (INCLUDES), one of
-- them must be (REQUIRES), or none may be (EXCLUDES).
create table option_rule (
    id         uuid        primary key,
    set_id     uuid        not null references option_set (id) on delete cascade,
    kind       text        not null check (kind in ('INCLUDES', 'REQUIRES', 'EXCLUDES')),
    if_choice  uuid        not null references option_choice (id) on delete cascade,
    then_choices uuid[]    not null,
    note       text,
    created_at timestamptz not null,
    created_by text        not null
);

create index option_rule_set on option_rule (set_id);
