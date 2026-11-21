-- Whether the user held the site administrator role at their latest sign-in. The role itself is
-- granted in the identity provider; this copy lets Administration list who has it.
alter table principal add column site_admin boolean not null default false;
