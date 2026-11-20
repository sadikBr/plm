import createClient, { type Middleware } from "openapi-fetch";
import { config } from "../config";
import type { components, paths } from "./schema";

/** All API schemas, generated from api/openapi.json. */
export type Schemas = components["schemas"];
export type Problem = Schemas["Problem"];

/** A failed API call. `code` is the server's stable error code (e.g. `ALREADY_CHECKED_OUT`). */
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly problem?: Problem,
  ) {
    super(problem?.detail ?? problem?.title ?? `Request failed (${status})`);
    this.name = "ApiError";
  }

  get code(): string | undefined {
    return this.problem?.code;
  }
}

type TokenProvider = () => Promise<string | undefined>;
let tokenProvider: TokenProvider = async () => undefined;

/** Wire in where the access token comes from (the OIDC user manager; a fake in tests). */
export function setTokenProvider(provider: TokenProvider): void {
  tokenProvider = provider;
}

const bearer: Middleware = {
  async onRequest({ request }) {
    const token = await tokenProvider();
    if (token) request.headers.set("Authorization", `Bearer ${token}`);
    return request;
  },
};

/** Typed client: paths, parameters and bodies are checked against the API contract at compile time. */
export const api = createClient<paths>({
  baseUrl: config.apiBase,
  // Look fetch up on every call (not once at import), so test stubs and instrumentation see it.
  fetch: (request) => globalThis.fetch(request),
});
api.use(bearer);

/** Resolves to the response body, or throws [ApiError] for any non-2xx response. */
export async function unwrap<T>(call: Promise<{ data?: T; error?: unknown; response: Response }>): Promise<T> {
  const { data, error, response } = await call;
  if (!response.ok) throw new ApiError(response.status, error as Problem | undefined);
  return data as T;
}

export function isApiError(error: unknown, status?: number): error is ApiError {
  return error instanceof ApiError && (status === undefined || error.status === status);
}
