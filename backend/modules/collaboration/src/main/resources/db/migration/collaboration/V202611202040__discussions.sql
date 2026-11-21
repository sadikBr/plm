-- Discussions on objects (roadmap 3.10): comments and replies, with @mentions.
create table comment (
    id         uuid        primary key,
    tenant_id  uuid        not null,
    object_id  uuid        not null,
    -- A reply's top-level comment; null for a new topic.
    parent_id  uuid        references comment (id),
    body       text        not null,
    mentions   text[]      not null default '{}',
    author     text        not null,
    created_at timestamptz not null,
    edited_at  timestamptz,
    -- Deleted comments keep their place in the thread, without their text.
    deleted    boolean     not null default false,
    resolved   boolean     not null default false
);

create index comment_object on comment (tenant_id, object_id, created_at);
