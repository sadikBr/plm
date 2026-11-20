-- Every (ancestor, descendant) pair of types, including (t, t). Lets other modules ask "is X a
-- kind of Y?" in a join instead of a recursive query, e.g. access policies that apply to a type
-- and all its subtypes. Owned by the types module; other modules may read it, never write it.

create table type_closure (
    ancestor_id   uuid not null references type_def (id),
    descendant_id uuid not null references type_def (id),
    depth         int  not null check (depth >= 0),
    primary key (ancestor_id, descendant_id)
);

create index type_closure_descendant_idx on type_closure (descendant_id, ancestor_id);

insert into type_closure (ancestor_id, descendant_id, depth)
with recursive tree (ancestor_id, descendant_id, depth) as (
    select id, id, 0 from type_def
    union all
    select t.ancestor_id, d.id, t.depth + 1
    from tree t join type_def d on d.parent_id = t.descendant_id
)
select ancestor_id, descendant_id, depth from tree;
