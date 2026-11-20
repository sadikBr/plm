/** Runtime configuration. Override with VITE_* variables (see .env.example). */
export const config = {
  oidcAuthority: import.meta.env.VITE_OIDC_AUTHORITY ?? "http://localhost:8180/realms/plm",
  oidcClientId: import.meta.env.VITE_OIDC_CLIENT_ID ?? "plm-web",
  /** Empty = same origin (the Vite dev server proxies /api to the backend). */
  apiBase: import.meta.env.VITE_API_BASE ?? "",
};

/** Seeded built-in types (see the `types` module migration). */
export const BUILT_IN_TYPES = {
  document: "00000000-0000-7000-8000-000000000101",
  part: "00000000-0000-7000-8000-000000000102",
} as const;
