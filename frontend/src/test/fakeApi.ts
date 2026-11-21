import { vi } from "vitest";

type Handler = (req: Request, params: Record<string, string>) => unknown | Promise<unknown>;

/** A reply with a specific status (anything else a handler returns is sent as 200 JSON). */
export class Reply {
  constructor(
    readonly status: number,
    readonly body?: unknown,
  ) {}
}

export const problem = (status: number, code: string, detail = code) =>
  new Reply(status, { status, code, detail, title: "Error", type: `urn:plm:error:${code}` });

/**
 * Stubs global fetch with a tiny router: `{"GET /api/v1/objects/:id": (req, { id }) => body}`.
 * Unmatched requests fail the test loudly. Returns the list of requests made, for assertions.
 */
/** Routes most pages call that tests rarely care about; a test's own routes come first. */
const DEFAULTS: Record<string, Handler> = {
  "GET /api/v1/objects/:id/content": () => [],
  "GET /api/v1/me/profile": () => ({ profiles: [], hiddenActions: [], hiddenAreas: [] }),
  "POST /api/v1/me/recent/:id": () => new Reply(204, undefined),
  "GET /api/v1/me/favorites": () => [],
  "GET /api/v1/me/subscriptions": () => [],
  "GET /api/v1/me/notifications": () => [],
  "GET /api/v1/me/notifications/unread": () => ({ unread: 0 }),
  "GET /api/v1/objects/:id/comments": () => [],
  "GET /api/v1/objects/:id/shares": () => [],
  "GET /api/v1/me/shared-with-me": () => [],
  "GET /api/v1/jobs": () => [],
  "GET /api/v1/objects/:id/usages": () => [],
  "GET /api/v1/baselines": () => [],
  "GET /api/v1/objects/:id/alternates": () => [],
  "GET /api/v1/objects/:id/substitute-uses": () => [],
  "GET /api/v1/objects/:id/document-links": () => [],
  "GET /api/v1/objects/:id/linking-parts": () => [],
  "GET /api/v1/objects/:id/where-used": () => ({ childId: "", spec: { type: "LATEST" }, nodes: [], truncated: false }),
  "GET /api/v1/objects/:id/referenced-by": () => [],
  "GET /api/v1/objects/:id/processes": () => [],
  "GET /api/v1/process-templates": () => [],
  "GET /api/v1/tasks": () => [],
  "GET /api/v1/tasks/counts": () => ({ open: 0, overdue: 0 }),
  "GET /api/v1/me/stand-in": () => null,
  "GET /api/v1/objects/:id/process-assignments": () => [],
  "GET /api/v1/objects/:id/signatures": () => [],
  "GET /api/v1/promotion-requests": () => [],
  "GET /api/v1/objects/:id/changes": () => [],
  "GET /api/v1/objects/:id/variances": () => [],
  "GET /api/v1/changes/:id/redlines": () => [],
  "GET /api/v1/release-gate": () => ({ enabled: false, states: ["RELEASED"], updatedAt: null, updatedBy: null }),
  "GET /api/v1/change-metrics": () => ({ open: 0, byState: [], ages: [], cycleTimes: [], flow: [], mine: [], periodDays: 180 }),
  "GET /api/v1/objects/:id/structure": () => ({ rootId: "", spec: { type: "LATEST" }, nodes: [], truncated: false, elapsedMs: 0 }),
  "GET /api/v1/preferences/effective": () => [],
};

export function fakeApi(routes: Record<string, Handler>) {
  const calls: { method: string; path: string; body?: unknown; auth: string | null }[] = [];
  const compiled = Object.entries({ ...DEFAULTS, ...routes }).map(([key, handler]) => {
    const [method, pattern] = key.split(" ") as [string, string];
    const names: string[] = [];
    const regex = new RegExp("^" + pattern.replace(/:([a-zA-Z]+)/g, (_, name: string) => (names.push(name), "([^/]+)")) + "$");
    return { method, regex, names, handler };
  });

  vi.stubGlobal("fetch", async (input: RequestInfo | URL, init?: RequestInit) => {
    const req = input instanceof Request ? input : new Request(input, init);
    const url = new URL(req.url);
    const text = req.method === "GET" ? "" : await req.clone().text();
    calls.push({
      method: req.method,
      path: url.pathname + url.search,
      body: text ? JSON.parse(text) : undefined,
      auth: req.headers.get("Authorization"),
    });
    for (const route of compiled) {
      const match = route.method === req.method ? route.regex.exec(url.pathname) : null;
      if (!match) continue;
      const params = Object.fromEntries(route.names.map((n, i) => [n, decodeURIComponent(match[i + 1]!)]));
      const result = await route.handler(req, params);
      const reply = result instanceof Reply ? result : new Reply(200, result);
      const contentType = reply.status >= 400 ? "application/problem+json" : "application/json";
      return new Response(reply.body === undefined ? null : JSON.stringify(reply.body), {
        status: reply.status,
        headers: { "Content-Type": contentType },
      });
    }
    throw new Error(`fakeApi: no route for ${req.method} ${url.pathname}`);
  });

  return calls;
}
