-- Markups (roadmap 6.10, 6.11): saved views and annotations on a version's 3D model or on a page
-- of one of its files, each discussed in its own topic.
create table markup (
    id           uuid        primary key,
    tenant_id    uuid        not null,
    object_id    uuid        not null,
    iteration_id uuid        not null,
    version      text        not null,
    -- MODEL: on the 3D view; SHEET: on a page of a file (drawing, PDF).
    kind         text        not null check (kind in ('MODEL', 'SHEET')),
    content_id   uuid,
    page         int,
    title        text        not null,
    -- The camera, section and hidden parts (MODEL), or the zoom (SHEET): as the viewer wrote it.
    view         jsonb       not null default '{}',
    -- Pins and notes (MODEL), or arrows, text, clouds, rectangles (SHEET).
    shapes       jsonb       not null default '[]',
    topic_id     uuid        references comment (id),
    author       text        not null,
    created_at   timestamptz not null,
    updated_at   timestamptz not null
);

create index markup_object on markup (tenant_id, object_id, created_at);
