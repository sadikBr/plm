# 0004. Hybrid type system (columns + validated JSONB)

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
Customers must define subtypes and attributes at runtime without schema migrations. EAV tables are flexible but perform poorly at scale.

## Decision
Core fields are real columns. Type-specific attributes live in a JSONB `attrs` column, validated on write against `type_def.attr_schema`. Types form an inheritance tree; attributes, lifecycle, numbering and UI layouts are inherited. JSONB gets GIN indexes; frequently queried attributes are promoted to indexed generated columns.

## Consequences
+ Runtime extensibility with good query performance. − Validation lives in the app; attribute renames require data migration jobs.
