-- Occurrences (roadmap 4.5): the individual instances under a usage line, named by their reference
-- designators (R1, R2, C10...), as on schematics and assembly drawings.
create table usage_occurrence (
    usage_id   uuid not null references usage_link (id) on delete cascade,
    designator text not null,
    -- Order as entered, so R10 comes after R9.
    position   int  not null,
    primary key (usage_id, designator)
);
