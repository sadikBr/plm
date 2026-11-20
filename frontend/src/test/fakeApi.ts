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
export function fakeApi(routes: Record<string, Handler>) {
  const calls: { method: string; path: string; body?: unknown; auth: string | null }[] = [];
  const compiled = Object.entries(routes).map(([key, handler]) => {
    const [method, pattern] = key.split(" ") as [string, string];
    const names: string[] = [];
    const regex = new RegExp(
      "^" + pattern.replace(/:([a-zA-Z]+)/g, (_, name: string) => (names.push(name), "([^/]+)")) + "$",
    );
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
