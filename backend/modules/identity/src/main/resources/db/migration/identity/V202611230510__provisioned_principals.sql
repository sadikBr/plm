-- Roadmap 9.1: people the directory (SCIM) creates before they first sign in.
alter table principal add column provisioned boolean not null default false;
alter table principal add column active boolean not null default true;
-- Never signed in yet: their id is the directory's until the first sign-in gives the real subject.
alter table principal add column signed_in boolean not null default true;
