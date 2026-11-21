-- Processes about something other than one object (roadmap 5.7): a promotion request, a change.
-- Their roles are found in [context_id]; [about] and [link] say what it is and where it's shown.
alter table process_instance add column context_id uuid;
alter table process_instance add column about text;
alter table process_instance add column link text;
