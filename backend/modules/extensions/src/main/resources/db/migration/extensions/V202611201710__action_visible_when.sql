-- Action visibility conditions (roadmap 2.2): a CEL expression; the action shows only where it's true.
alter table action_config add column visible_when text;
