# 0017. Numbering, revision schemes and lifecycles

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
Roadmap 1.5–1.6. Objects need type-specific numbering and revision labels, and a lifecycle that
controls what can happen to them. In particular: only released objects may be revised, and released
objects are read-only.

## Decision
- **Rules per type, inherited.** `type_rule` (types module) holds a type's own overrides: numbering
  scheme, revision scheme, whether manual numbers are allowed. The effective value comes from the
  nearest type in the lineage that sets it. `type_lifecycle` (lifecycle module) does the same for
  lifecycles. Basic is the fallback.
- **Objects keep what they were created with.** `object_master` stores its `lifecycle_id` and
  `revision_scheme`. Changing a type's rules affects only new objects; existing ones never change
  underneath their users.
- **Numbering schemes** are prefix + zero-padded counter + suffix. The counter row is advanced with
  `UPDATE … RETURNING` inside the creating transaction: concurrent creators serialize, numbers are
  unique, and a rolled-back create releases its number. Types without a scheme keep the system
  10-digit sequence.
- **Revision schemes** are a closed set of algorithms: ASME letters (default), A–Z, 1…, 0….
- **Lifecycle states carry two structural flags**: `editable` (may be checked out) and `revisable`
  (may be revised from). They express *what* is possible in a state, for everyone. Access policies,
  optionally scoped to a state, still decide *who* may act. Template validation ensures the
  initial state is editable and every state is reachable.
- **Set state** moves the latest checked-in iteration along an allowed transition. It needs the new
  **PROMOTE** permission (bit 32, managers by default), and the object must not be checked out.
  A site administrator may **override** the transitions; overrides are flagged in the
  `ObjectStateChanged` event and therefore in the audit log. State is the only column the
  history trigger allows to change on checked-in iterations (ADR 0012).
- **Revise** is refused from non-revisable states (`REVISE_NOT_ALLOWED`). The new revision starts in
  the lifecycle's initial state. **Check-out** is refused from non-editable states (`NOT_EDITABLE`).
- **The UI asks the server** (`GET /objects/{id}/lifecycle`) what is possible, and explains disabled
  actions ("Only Released objects can be revised").

## Consequences
+ "Released is read-only" and "revise only from Released" hold for every client, API included.
+ Promotion requests and change notices (Phase 4) will drive the same set-state operation from workflows.
− Lifecycle templates can't be edited after creation yet. Versioning templates and an editor
  come with the admin UI (roadmap 1.9).
