-- Reference attributes (roadmap 4.12): find the objects pointing to one quickly.
create index object_iteration_attrs_gin on object_iteration using gin (attrs jsonb_path_ops);
