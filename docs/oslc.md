# Standards: OSLC and STEP AP242 (roadmap 9.6)

## OSLC

Requirements, test and change tools (anything that speaks OSLC 2.0 / 3.0 Core) can find parts,
documents and changes, show them, and link to them. Every call takes a bearer token: a service
account's API token for a tool, or a person's own token (My preferences → Access tokens).

| What | Where |
|---|---|
| Service provider catalog | `GET /api/v1/oslc/catalog` |
| Service provider (one per product, library, project) | `GET /api/v1/oslc/contexts/{id}/provider` |
| Query capability: parts and documents (`oslc_am`), changes (`oslc_cm`) | `GET /api/v1/oslc/contexts/{id}/query?domain=am&oslc.where=dcterms:identifier="P-100"` or `&oslc.searchTerms=bracket` |
| Resource | `GET /api/v1/oslc/resources/{id}` |
| Compact form (hover preview) | the same, with `Accept: application/x-oslc-compact+xml` |
| Delegated selection dialog | `{ui}/oslc/select?contextId=…&domain=am`: answers with `postMessage("oslc-response:{\"oslc:results\":[…]}")` |
| Small preview page | `{ui}/oslc/preview/{id}` |

Formats: RDF/XML by default (`application/rdf+xml`), Turtle (`text/turtle`) or JSON-LD
(`application/ld+json`) when asked. Responses carry `OSLC-Core-Version: 2.0`.

A resource has `dcterms:identifier` (the number), `dcterms:title`, `dcterms:description`,
`dcterms:created`, `dcterms:modified`, `dcterms:creator`, `oslc:shortTitle` (number and version),
`oslc:serviceProvider`, `plm:version`, `plm:state`, `plm:page` (its page here), and its links to
other tools.

**Links to other tools** are kept on the object (Details → Links to other tools, or
`POST /api/v1/objects/{id}/external-links`) as OSLC link types:
`oslc_rm:implementsRequirement`, `oslc_rm:satisfiesRequirement`, `oslc_qm:testedByTestCase`,
`oslc_qm:validatedByTestResult`, `oslc_cm:relatedChangeRequest`, `dcterms:references`. They are
published on the resource, so the other tool sees the backlink.

Not supported: OAuth 1.0a (use bearer tokens), creation factories (tools don't create parts
here), `oslc.select` / `oslc.properties` (whole resources are returned), Jazz root services.

ReqIF comes with requirements management (Phase 14), IPC-1752 with compliance (Phase 13).

## STEP AP242 export

`GET /api/v1/objects/{id}/step-export` (Structure tab → STEP AP242) returns a zip:

- `<number>.stp`: the structure as AP242 (`AP242_MANAGED_MODEL_BASED_3D_ENGINEERING_MIM_LF`):
  each part a `PRODUCT` with its version (`PRODUCT_DEFINITION_FORMATION`) and design
  `PRODUCT_DEFINITION`; each unit of quantity a `NEXT_ASSEMBLY_USAGE_OCCURRENCE` (find number as
  its name); the CAD files of the documents describing a part as `DOCUMENT_FILE` with an
  `APPLIED_DOCUMENT_REFERENCE` to the part;
- `cad/<document number>/<file>`: those CAD files.

`released=true` exports the latest released iterations; `files=false` leaves the CAD files out.
The STEP import (roadmap 6.5) reads the file back.
