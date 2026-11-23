# Migration package format (`plm-migration/1`)

A migration brings objects from another PLM system into a product or library **with their history**
(roadmap 9.3). Export the other system's data as a **zip**:

```
legacy.zip
├── manifest.json        what to bring in (below)
└── files/…              the files the manifest names, at any path
```

Upload it in **Administration → Migration**, choose the product or library it goes into, map what
doesn't match, and run it. The dry run lists every problem before anything changes; the run is a
background job. Objects a run already brought in are skipped, so a run that stopped, or one where
some objects failed, can simply be run again after fixing the mappings. Site administrators only.

## manifest.json

```json
{
  "format": "plm-migration/1",
  "source": "Legacy PDM 9.2",
  "users": [
    { "id": "u17", "username": "jdoe", "displayName": "John Doe", "email": "jdoe@example.com" }
  ],
  "objects": [
    {
      "id": "OR:123",
      "type": "WTPart",
      "number": "100-2001",
      "name": "Gearbox",
      "folder": "/Drivetrain/Gearboxes",
      "iterations": [
        {
          "version": "A", "iteration": 1, "state": "INWORK",
          "createdAt": "2019-03-01T09:00:00Z", "createdBy": "u17",
          "comment": "First release candidate",
          "attributes": { "Description": "Gearbox, 6-speed", "Material": "Steel" }
        },
        {
          "version": "B", "iteration": 1, "state": "RELEASED",
          "createdAt": "2020-05-01T08:00:00Z", "createdBy": "u17",
          "attributes": { "Description": "Gearbox, 6-speed" },
          "uses": [ { "child": "OR:124", "quantity": 2, "unit": "each", "findNumber": "10" } ],
          "describedBy": [ "OR:900" ]
        }
      ]
    },
    {
      "id": "OR:900", "type": "WTDocument", "number": "DRW-2001", "name": "Gearbox drawing",
      "iterations": [
        {
          "version": "A", "iteration": 1, "state": "RELEASED", "createdAt": "2019-03-02T09:00:00Z",
          "files": [ { "path": "files/DRW-2001-A1.pdf", "role": "PRIMARY" } ]
        }
      ]
    }
  ]
}
```

| Field | Meaning |
|---|---|
| `objects[].id` | The other system's id. `uses` and `describedBy` refer to objects by it. |
| `type` | The other system's type name; map it to a type here if the names differ. |
| `number`, `name` | Kept as they are. A number that already exists here stops the run (dry run error). |
| `folder` | Optional path inside the target; folders are made when missing. |
| `iterations` | Oldest first. Each keeps its version label, iteration number, state, date, author, comment and attributes. The last one becomes the latest. |
| `state` | A lifecycle state of the type (by key); map other names. |
| `attributes` | By name; map other names, or map them to nothing to leave them out. Values are validated as on a check-in. |
| `files` | Paths inside the zip, on documents and CAD documents. `role` is `PRIMARY` (default) or `ATTACHMENT`. |
| `uses` | That iteration's structure: child objects (parts) by id, with quantity, unit (a unit-of-use key) and find number. |
| `describedBy` | Documents (by id) that describe that iteration of a part. |
| `users` | The people named in `createdBy`. Users not mapped to someone here are added to the directory, inactive, so the history shows who did what. |

## Mappings

Saved with the migration and editable until it has run:

| Mapping | From → to |
|---|---|
| Types | `WTPart` → `Part` (a type name or display name here) |
| States | `INWORK` → `IN_WORK` (a lifecycle state key) |
| Attributes | `Description` → `description`; empty → left out |
| Users | `u17` → `jdoe` (a user name here) |

The API: `POST /api/v1/migrations` (multipart `package`, `contextId`), `GET /api/v1/migrations/{id}`
(with the analysis), `PUT …/mappings`, `POST …/run`, `GET …/items` (each object's outcome).
