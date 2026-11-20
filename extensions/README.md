# Extensions

Extension packages add behaviour without changing platform code (roadmap Phase 2). An extension is
a manifest, `plm-extension.yaml`, installed by a site administrator under **Admin → Extensions**
(or `POST /api/v1/extensions`). See [docs/EXTENSIBILITY.md](../docs/EXTENSIBILITY.md) for the format.

| Sample | What it shows |
|---|---|
| [erp-sync](samples/erp-sync/plm-extension.yaml) | Settings (including a secret), a "Show in ERP" action on Released parts that opens the ERP, and a seed value list and attribute |
| [erp-sync 1.1](samples/erp-sync/plm-extension-1.1.yaml) | The same extension, one version later: an upgrade adds an action and a permission |
| [erp-sync 2.0](samples/erp-sync/plm-extension-2.0.yaml) | Webhooks: "Send to ERP" asks for a plant and a quantity and shows the ERP's answer; "ERP cost report" downloads a file |

## Trying the webhooks

`samples/erp-sync/mock-erp.mjs` is a stand-in ERP (Node, no dependencies) that checks signatures
like a real service should:

```bash
ERP_SECRET=demo-key node extensions/samples/erp-sync/mock-erp.mjs
```

Install (or upgrade to) `plm-extension-2.0.yaml` with *ERP service address* `http://localhost:8099`
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

