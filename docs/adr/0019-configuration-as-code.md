# 0019. Configuration as code

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
Roadmap 1.10. Admins configure types, attributes, layouts, value lists, numbering, rules,
lifecycles and team roles in the UI. Real deployments have several environments (dev, test, prod)
and want those settings reviewed and versioned like code, then promoted, without retyping them.

## Decision
- **One bundle** (`plm-config/1`, JSON) holds the whole configuration of a tenant. It is produced
  by `GET /api/v1/configuration/export` and the Admin page, and is meant to live in Git.
- **References by name, never by id.** Types, value lists, numbering schemes and lifecycles are
  matched by name, roles by key. Ids differ between environments; names are what admins see.
- **Deterministic output.** Sorted lists, sorted label maps, no timestamps, no counters: exporting
  twice gives the same file, so diffs show only real changes. A numbering scheme created by an
  import starts at 1.
- **Import adds and updates, never deletes.** Anything here but not in the bundle stays. Deleting
  configuration is rare and dangerous, and stays a deliberate act in the UI.
- **Plan first.** Every import computes a plan (create / update / unchanged / conflict, with
  field-level diffs). The whole run happens inside one transaction, each change under a savepoint,
  by calling the same module services the UI uses, so every rule they enforce applies. A dry run,
  or any run with a conflict, is rolled back. The plan of a dry run is therefore exactly what
  applying does, and an import is applied entirely or not at all.
- **Conflicts instead of surprises.** Changes the platform can't make (a type's parent, an
  attribute's data type, an existing lifecycle template or numbering format) are reported as
  conflicts rather than skipped silently.
- A separate `configuration` module owns this. It depends only on the public APIs of `types`,
  `lifecycle` and `access`.

## Consequences
+ Configuration can be code-reviewed, versioned and promoted dev → test → prod.
+ Context templates (roadmap 1.12) can reuse the same bundle format.
− Renames look like "create new + keep old". Renaming support comes with a stable external key per item, if needed.
− Lifecycle templates can't be updated by import until they can be versioned (roadmap 1.15).
