# PLM web UI

React + TypeScript + Vite. See [ADR 0014](../docs/adr/0014-api-contract-and-frontend.md) for the
design decisions, and [docs/DEVELOPMENT.md](../docs/DEVELOPMENT.md#4-run-the-web-ui) to run it.

```bash
npm ci
npm run dev        # http://localhost:5173 (backend must run on :8080)
npm test           # unit tests
npm run e2e        # browser test against the full running stack
```

## Layout

| Path | What |
|---|---|
| `src/api/client.ts` | Typed API client (bearer token, `ApiError` with the server's error `code`) |
| `src/api/schema.d.ts` | **Generated** from `../api/openapi.json`. Don't edit; not committed |
| `src/api/queries.ts` | All server state: query keys, read hooks, mutations |
| `src/auth/` | Keycloak sign-in (OIDC + PKCE) |
| `src/components/` | Layout, context tree, attribute editor, shared bits |
| `src/pages/` | Routes: context page, object page, home, not found |
| `src/test/` | Fake `fetch` router, fixtures, render helper |
| `e2e/` | Playwright end-to-end test |
