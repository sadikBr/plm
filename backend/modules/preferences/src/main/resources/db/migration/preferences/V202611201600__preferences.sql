-- Preferences (roadmap 1.13). Definitions live in code (each module declares its own); only values
-- are stored. A value on a context applies to it and everything below, unless a context above
-- locked the preference. User values apply where nothing is locked.

create table preference_value (
    tenant_id   uuid        not null references tenant (id),
    key         text        not null,
    -- CONTEXT (the site is a context too) or USER.
    scope       text        not null check (scope in ('CONTEXT', 'USER')),
    -- Context id or principal id.
    scope_id    text        not null,
    value       jsonb       not null,
    -- Only for CONTEXT values: nothing below may override.
    locked      boolean     not null default false check (not locked or scope = 'CONTEXT'),
    set_at      timestamptz not null,
    set_by      text        not null,
    primary key (tenant_id, key, scope, scope_id)
);
