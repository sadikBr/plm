-- Lifecycle gates (roadmap 2.2): a CEL condition an object must meet to enter a state, with the
-- message shown when it doesn't (in addition to the state's required attributes).
alter table lifecycle_state add column entry_condition text;
alter table lifecycle_state add column entry_message text;
