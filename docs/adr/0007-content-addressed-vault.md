# 0007. Content-addressed file vault on S3-compatible storage

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
CAD files can be many gigabytes and are re-checked-in unchanged frequently. Streaming through app servers does not scale.

## Decision
Files are stored in S3-compatible storage (MinIO on-prem, S3/Azure Blob in cloud), keyed by SHA-256 of content. Clients upload/download directly with presigned URLs. The database stores only metadata (`content_item`, `iteration_content`).

## Consequences
+ Automatic dedup, app servers stay light, replication-ready. − Need upload finalization/verification flow and orphan garbage collection.
