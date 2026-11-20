-- Calculated attributes (roadmap 2.2): a CEL formula computes the value whenever an object is saved.
alter table attribute_def add column formula text;
