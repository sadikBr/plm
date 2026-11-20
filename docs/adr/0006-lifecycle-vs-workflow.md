# 0006. Lifecycle state machine separate from workflow engine

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
Lifecycle transitions must be atomic with data changes. Human approval processes run for days or weeks and must survive restarts and code deploys. Building a durable workflow engine is a multi-year effort.

## Decision
**Lifecycle** is a declarative state machine per type, implemented in our `lifecycle` module and executed inside the DB transaction. **Workflow** (reviews, approvals, change implementation) runs on **Temporal**. Workflows invoke lifecycle transitions through the lifecycle API; lifecycle never depends on workflow.

## Consequences
+ Clear separation; durable, versionable workflows for free. − Temporal is another component to operate.
