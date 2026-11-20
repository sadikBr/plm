-- Roadmap 1.14: shared teams and context templates can belong to an organization. Those are
-- managed by the organization's managers and usable only inside it; unscoped ones are site-wide
-- (site administrators).

alter table shared_team add column organization_id uuid references context (id);
alter table context_template add column organization_id uuid references context (id);
