-- Effectivity (roadmap 7.1): when part versions and structure lines apply. Serials, lots and blocks
-- count in an end item (an effectivity context); dates may stand alone.
create table effectivity_context (
    id          uuid        primary key,
    tenant_id   uuid        not null,
    end_item_id uuid        not null references object_master (id) on delete cascade,
    description text,
    created_at  timestamptz not null,
    created_by  text        not null,
    unique (tenant_id, end_item_id)
);

create table effectivity (
    id          uuid        primary key,
    tenant_id   uuid        not null,
    -- The part: whose revision it is, or whose iteration the line is on.
    master_id   uuid        not null references object_master (id) on delete cascade,
    -- A version's effectivity names its revision; a line's, the line.
    revision    text,
    line_id     uuid        references usage_link (id) on delete cascade,
    context_id  uuid        references effectivity_context (id) on delete cascade,
    kind        text        not null check (kind in ('DATE', 'SERIAL', 'LOT', 'BLOCK')),
    -- The range, by kind: numbers for serials and blocks, dates, or text for lots. No end: open.
    from_num    numeric,
    to_num      numeric,
    from_date   date,
    to_date     date,
    from_text   text,
    to_text     text,
    -- PENDING: set through change notice [change_id], effective once it's resolved (7.3).
    status      text        not null default 'ACTUAL' check (status in ('ACTUAL', 'PENDING')),
    change_id   uuid,
    created_at  timestamptz not null,
    created_by  text        not null,
    check ((revision is null) <> (line_id is null)),
    check (kind = 'DATE' or context_id is not null)
);

create index effectivity_master on effectivity (master_id, kind);
create index effectivity_line on effectivity (line_id) where line_id is not null;
create index effectivity_change on effectivity (change_id) where change_id is not null;
