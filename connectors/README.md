# Connectors

Connectors link authoring tools (CAD, ECAD) to PLM (roadmap 8.5). They all use one small
protocol, so each connector is mostly about reading its tool's files and putting buttons in its
tool.

| Connector | State |
|---|---|
| [FreeCAD](freecad/) | Workbench (Save to PLM, Open from PLM) and command line; tested on real FreeCAD files |
| KiCad | See [kicad/](kicad/) |
| SolidWorks, Inventor, Creo, NX, CATIA | Add-ins built on this protocol need the vendor's SDK and a licensed Windows machine: [solidworks/](solidworks/) is a starting point |

Client libraries: [Python](sdk/python/plm_connector.py) and [JavaScript](sdk/js/plm-connector.mjs),
no dependencies.

## Signing in

A person's **access token** (My preferences → Access tokens), sent as `Authorization: Bearer plm_…`.
The connector acts as that person, with their access rules.

## The protocol (version 1)

`GET /api/v1/connectors` → `{"protocol": 1}`: check before saving.

### Save

1. Send each changed file to the vault: `POST /api/v1/vault/uploads {fileName, size}` gives
   `{uploadId, uploadUrl}`; `PUT` the bytes to `uploadUrl`.
2. `POST /api/v1/connectors/save` with a manifest:

```json
{
  "contextId": "<product, library or project for new models>",
  "application": "FreeCAD 1.0",
  "checkIn": true,
  "comment": "Pin diameter 8 mm",
  "models": [
    {
      "key": "Engine.FCStd",
      "id": null,
      "name": "Engine",
      "kind": "ASSEMBLY",
      "uploadId": "<from step 1, or null to keep the file>",
      "attributes": { "fileFormat": "FreeCAD document" },
      "dependencies": [
        { "key": "parts/Pin.FCStd", "kind": "COMPONENT", "occurrence": "Pin001",
          "transform": [1,0,0,0, 0,1,0,0, 0,0,1,0, 25,0,0,1] }
      ],
      "part": { "create": true }
    },
    { "key": "parts/Pin.FCStd", "name": "Pin", "kind": "PART", "uploadId": "…" }
  ]
}
```

- `key`: the connector's name for the model (usually its path); dependencies point at keys, or
  at `id`s of models already in the system.
- `id`: set when the model is already in PLM (the connector remembers it); new models get one.
- `kind`: `PART`, `ASSEMBLY` or `DRAWING`. Dependency kinds: `COMPONENT`, `DRAWING` (a drawing
  of the model), `REFERENCE`. `transform`: column-major 4×4, millimetres.
- `part`: the part the model represents (`{"id"}`, `{"number"}` or `{"create": true}`): the model
  becomes its owning model.
- Each model is checked out (if it isn't already, to you), gets its file, values and
  dependencies (replacing the previous ones), and with `checkIn` everything is checked in.
  **All or nothing**: if one model can't be saved, none is, and the error's `detail` starts with
  its key.

Answer: `[{key, id, number, version, created, partId, partNumber}]`.

### Open

`GET /api/v1/connectors/models/{id}/files`: the model and everything it depends on, each with
`fileName`, `sha256`, a download `url` (signed, short-lived), and its `dependencies`. Your working
copy's files when you have it checked out.
