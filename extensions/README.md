# Extensions

Extension packages add behaviour without changing platform code (roadmap Phase 2). An extension is
a manifest, `plm-extension.yaml`, installed by a site administrator under **Admin → Extensions**
(or `POST /api/v1/extensions`). See [docs/EXTENSIBILITY.md](../docs/EXTENSIBILITY.md) for the format.

| Sample | What it shows |
|---|---|
| [erp-sync](samples/erp-sync/plm-extension.yaml) | Settings (including a secret), a "Show in ERP" action on Released parts that opens the ERP, and a seed value list and attribute |
| [erp-sync 1.1](samples/erp-sync/plm-extension-1.1.yaml) | The same extension, one version later: an upgrade adds an action and a permission |
