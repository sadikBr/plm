# Database migrations

- One subfolder per owning module, e.g. `versioning/`, `access/`.
- File name: `V<yyyyMMddHHmm>__<description>.sql` (timestamp versions avoid collisions between modules).
- Migrations are immutable once merged to `main`. Fix forward with a new migration.
- Every table carries `tenant_id` (ADR 0009).
