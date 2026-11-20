# 0015. Typed attributes, enumerations and units

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
Roadmap 1.1–1.4. Until now, attribute values were free-form JSON. Engineering data needs typed,
validated attributes, out-of-the-box types with their standard fields, and customization by
administrators without code (see [PARITY.md](../PARITY.md) §2–§3).

## Decision
- **Definitions are data**: `attribute_def` rows on a type, inherited by subtypes. Names are
  unique along the whole hierarchy (no shadowing in either direction), and camelCase.
- **Data types**: STRING, TEXT, INTEGER, REAL, QUANTITY, BOOLEAN, DATE, TIMESTAMP, URL,
  ENUMERATION, each optionally multi-valued. Constraints: required, immutable, min/max length,
  pattern, min/max value. Constraints that don't fit the data type are rejected at definition time.
  References to other objects come later.
- **Values stay in the iteration's JSONB** (ADR 0004). The server is the only validator:
  `AttributeValidator` runs on create, on every working-copy edit, and again at check-in, so a
  newly required attribute is enforced before the next release of existing objects.
- **One canonical stored form per type**: Long, Double, `{value, unit}`, UTC ISO timestamps,
  enumeration keys. It makes comparisons (immutability) and later indexing and search stable.
- **Quantities keep the unit the user entered**. Range checks convert to the dimension's base unit
  with exact decimal factors and offsets (°C, °F). Units are reference data grouped by dimension.
- **Enumerations are separate, reusable value lists** with labels per language. Values and
  attributes are **never deleted, only deactivated**: existing data keeps validating and
  displaying, but inactive choices can't be newly set.
- **Out-of-the-box configuration is seeded by migration** for the default tenant (`system = true`):
  Part and Document attributes, their enumerations, ~60 units. Admins may constrain or deactivate
  system attributes, never delete them or change their data type.
- **Absent means default.** Missing attributes receive their default value on every write.
  Blank strings and empty lists count as absent.
- **Unknown attributes are rejected** (`UNKNOWN_ATTRIBUTE`) rather than silently stored.
- **Errors are per field**: `INVALID_ATTRIBUTES` with `details.errors[] = {attribute, code,
  message}`. The UI shows each message next to its field.
- **The UI renders forms from definitions** (`GET /types/{id}/attributes`): one input per data type,
  labels in the user's language, value-list labels, units filtered by dimension.

## Consequences
+ Admins can model their own part and document types today, through the API or Swagger UI.
  The admin screens come in roadmap 1.9.
+ Data quality is enforced at the only place that can't be bypassed.
− Attribute definitions are read on each write. Caching can come when profiling asks for it.
− New tenants will need the out-of-the-box seed applied by a provisioning step (multi-tenancy, Phase 8).
