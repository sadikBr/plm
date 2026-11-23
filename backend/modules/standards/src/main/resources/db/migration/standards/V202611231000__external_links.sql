-- Roadmap 9.6: links from objects to resources in other tools (requirements, tests, ...), as
-- OSLC link types. Kept on the object (master), not per iteration.
create table external_link (
    id         uuid primary key,
    tenant_id  uuid        not null,
    object_id  uuid        not null references object_master (id) on delete cascade,
    -- An OSLC property, e.g. oslc_rm:implementsRequirement, oslc_qm:testedByTestCase.
    link_type  text        not null,
    url        text        not null,
    title      text,
    created_at timestamptz not null,
    created_by text        not null,
    unique (object_id, link_type, url)
);
create index external_link_object on external_link (object_id);
