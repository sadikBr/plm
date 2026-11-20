# 0014. API contract and web frontend

- **Status:** Accepted
- **Date:** 2026-11-20

## Context
Roadmap 0.10–0.11 adds a web UI. The UI and the backend must evolve together without drifting
apart, and API changes must be visible and reviewable as the API surface grows.

## Decision

### API contract (0.10)
- The backend generates **OpenAPI 3.1** with springdoc from the controllers. Nothing is
  hand-written. Swagger UI at `/swagger-ui.html` signs in through Keycloak (PKCE).
- The spec is **committed** as `api/openapi.json`. `OpenApiContractIT` fails the build when the
  served spec differs, and `make api-contract` regenerates it. Every API change therefore appears
  as a diff in the PR.
- The spec is polished centrally (`OpenApiConfiguration`): stable operation ids
  (`versionedObjectCheckOut`), readable tags, non-nullable Kotlin fields marked `required`, a shared
  `Problem` schema on every operation, and the OAuth2 security scheme.

### Frontend (0.11)
- **React + TypeScript + Vite**, in `frontend/`. Plain CSS with design tokens and light/dark
  themes: no component library yet. AG Grid arrives with BOM views.
- **Typed API client**: `openapi-typescript` generates types from `api/openapi.json` (never
  committed, regenerated before dev/build/test) and `openapi-fetch` checks paths, parameters and
  bodies at compile time. A breaking API change fails the frontend typecheck.
- **Server state** lives in TanStack Query. Query keys are defined in one place (`queries.ts`), and
  every mutation invalidates exactly the views it affects. There is no other global store.
- **Auth**: `oidc-client-ts` with authorization code + PKCE against Keycloak. Tokens are kept in
  `sessionStorage` and renewed with the refresh token. The API client adds the bearer token on
  every request.
- **Routing**: React Router (declarative). Deep links survive sign-in: the target path travels in
  the OIDC `state`.
- **Permissions drive the UI** (`GET /contexts/{id}/permissions`), but the server remains the only
  authority. A hidden button is a convenience, never a control.
- **Tests**: Vitest + Testing Library against a fake `fetch` router (pages render with the real
  routes and the real client), plus a Playwright end-to-end test of the core flow against the
  full stack.
- **Dev**: the Vite dev server proxies `/api` to the backend, so there's one origin and no CORS
  configuration.
- **Dependencies** are added only at versions published at least two weeks earlier
  (`npm install --before=<date>`), and the lockfile is committed.

## Consequences
+ The UI and the API cannot silently diverge. API review happens on one JSON diff.
+ Small bundle (~120 kB gzipped). No CSS framework to fight.
− Serving the built UI from the backend (or a CDN) for deployments is still to do.
− The end-to-end test needs the full stack, so CI runs only the frontend unit tests for now.
