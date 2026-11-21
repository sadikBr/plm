-- Ad hoc access (roadmap 3.10): one object shared with one person, outside the context's policies.
create table object_share (
    tenant_id    uuid        not null,
    object_id    uuid        not null,
    principal_id text        not null,
    -- Where the object lives, so the person can see that context's name and path.
    context_id   uuid        not null,
    -- Permission bits, as in access_policy.
    permissions  int         not null,
    note         text,
    expires_at   timestamptz,
    granted_by   text        not null,
    granted_at   timestamptz not null,
    primary key (tenant_id, object_id, principal_id)
);

create index object_share_principal on object_share (tenant_id, principal_id);
create index object_share_context on object_share (tenant_id, context_id);
