-- Electronic signatures (roadmap 5.6, 21 CFR Part 11): who signed what, when, and what it means.
-- Records are written once: the database refuses to change or remove them.
create table e_signature (
    id             uuid primary key,
    tenant_id      uuid        not null,
    principal      text        not null,
    username       text        not null,
    display_name   text        not null,
    meaning        text        not null,
    comment        text,
    signed_at      timestamptz not null,
    object_id      uuid,
    object_number  text,
    object_version text,
    iteration_id   uuid,
    work_item_id   uuid        not null,
    instance_id    uuid        not null,
    -- SHA-256 over the fields above: shows the record is the one written.
    hash           text        not null
);
create index e_signature_object_idx on e_signature (object_id, signed_at);

create function e_signature_append_only() returns trigger language plpgsql as
$$
begin
    raise exception 'electronic signature % cannot be changed or removed', old.id
        using errcode = 'P0001', hint = 'PLM_IMMUTABLE_SIGNATURE';
end;
$$;
create trigger e_signature_append_only before update or delete on e_signature
    for each row execute function e_signature_append_only();
