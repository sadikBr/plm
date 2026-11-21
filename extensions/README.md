# Extensions

Extension packages add behaviour without changing platform code (roadmap Phase 2). An extension is
a manifest, `plm-extension.yaml`, installed by a site administrator under **Admin → Extensions**
(or `POST /api/v1/extensions`). See [docs/EXTENSIBILITY.md](../docs/EXTENSIBILITY.md) for the format.

| Sample | What it shows |
|---|---|
| [erp-sync](samples/erp-sync/plm-extension.yaml) | Settings (including a secret), a "Show in ERP" action on Released parts that opens the ERP, and a seed value list and attribute |
| [erp-sync 1.1](samples/erp-sync/plm-extension-1.1.yaml) | The same extension, one version later: an upgrade adds an action and a permission |
| [quality-rules](samples/quality-rules/plm-extension.yaml) | Operation rules only: no check-in without a description, no release without a reason, released parts keep their number |
| [erp-sync 2.0](samples/erp-sync/plm-extension-2.0.yaml) | Webhooks: "Send to ERP" asks for a plant and a quantity and shows the ERP's answer; "ERP cost report" downloads a file |
| [erp-sync 2.1](samples/erp-sync/plm-extension-2.1.yaml) | A long-running "Cost roll-up" that runs in the background, reports progress and ends with a file |

## Trying the webhooks

`samples/erp-sync/mock-erp.mjs` is a stand-in ERP (Node, no dependencies) that checks signatures
like a real service should:

```bash
ERP_SECRET=demo-key node extensions/samples/erp-sync/mock-erp.mjs
```

Install (or upgrade to) `plm-extension-2.0.yaml` (or 2.1) with *ERP service address* `http://localhost:8099`
and *API key* `demo-key`, release a part, and use **Send to ERP**.

## Writing a webhook service

The platform POSTs JSON to the action's `webhook.url`:

```json
{ "invocationId": "…", "action": "acme.erp-sync.send-to-erp", "extension": "acme.erp-sync", "timestamp": "1795…",
  "object": { "id": "…", "number": "0000000042", "name": "…", "type": "Part", "state": "RELEASED", "version": "A.1", "attributes": { } },
  "user": { "id": "…", "siteAdmin": false, "permissions": ["READ"] },
  "parameters": { "plant": "P100", "quantity": 5, "urgent": false } }
```

Headers: `X-PLM-Signature: sha256=<hex HMAC-SHA256("<X-PLM-Timestamp>.<body>", secret)>`,
`X-PLM-Timestamp` (Unix seconds; refuse old ones), `X-PLM-Invocation`, `X-PLM-Action`. Answer within
the action's `timeoutSeconds` (default 10, at most 30) with a 2xx and, optionally:

```json
{ "message": "Order SO-4711 created", "level": "info|warning|error", "refresh": true,
  "navigate": { "objectId": "…" } , "download": { "filename": "report.csv", "contentType": "text/csv", "content": "<base64>" } }
```

`navigate` may instead be `{ "url": "https://…" }`. A non-2xx answer is shown as an error, with its
`message` if it has one. Redirects aren't followed.

## Long-running actions

Work that takes longer than 30 seconds (a report from a slow query, a costing run) is declared
`background: true`, with a `timeoutSeconds` of up to 3600:

```yaml
handler:
  webhook:
    url: "${config.erpApiUrl}/rollup"
    secret: apiKey
    background: true
    timeoutSeconds: 600
```

Running it starts a background job as the person who ran it. They follow it on the object's page
(and can cancel it), and they're notified when it ends, with its message and a link back to the
object, where its file can be downloaded. The call carries the job:

```json
"job": { "id": "…", "token": "pjt_…", "progressUrl": "https://…/api/v1/extension-jobs/…/progress",
         "completeUrl": "https://…/api/v1/extension-jobs/…/complete" }
```

- Report progress as often as useful: `POST progressUrl` with header `X-PLM-Job-Token: <token>` and
  `{ "done": 3, "total": 8, "message": "Costing at P200" }`. The answer
  `{ "cancelRequested": true }` means the person cancelled: stop.
- Either keep the request open and answer as usual, or answer `202` at once and later
  `POST completeUrl` (same header) with the answer you would have given.
- The token works only while the job runs. If no answer comes within `timeoutSeconds`, the job fails.

The mock ERP's `/rollup` does the second: it accepts, reports eight steps of progress, then posts a
CSV. Install `plm-extension-2.1.yaml` to try it.


## Calling the PLM API

Webhook services often need more than the object they were sent. Two permissions give them a way:

- **`api:read` / `api:write`**: every action call carries a token that acts as the person who ran
  the action. It has their access rules and their name in the audit trail, never administration,
  and expires after 15 minutes. `api:read` allows GET requests; `api:write` allows changes too.

  ```json
  "api": {
    "baseUrl": "https://plm.example.com",
    "token": "plm_…",
    "expiresAt": "2026-11-20T10:15:00Z",
    "scopes": ["read"]
  }
  ```

  Send it as `Authorization: Bearer <token>`. The sample ERP's cost report uses it to read every
  iteration of the part (`GET /api/v1/objects/{id}/iterations`).

- **`api:account`**: the extension gets a service account of its own (`svc-ext-<id>`), for work
  that no person starts, such as a nightly sync. An administrator issues its tokens or client
  secrets in *Admin → Service accounts*. The account is turned off while the extension is disabled
  or uninstalled.

Service accounts for other integrations are created in the same place. A client secret is
exchanged for a one-hour access token (OAuth 2.0 client credentials):

```bash
curl -X POST https://plm.example.com/api/v1/oauth/token \
  -d grant_type=client_credentials -d client_id=<account id> -d client_secret=<secret>
```

Tokens act within their scopes (`read`, `write`, and `admin` for accounts allowed to administer)
and their account's access rules. They can never manage service accounts, tokens or extensions.
