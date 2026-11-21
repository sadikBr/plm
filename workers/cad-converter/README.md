# CAD converter

Turns STEP models into binary glTF (`.glb`) for the web viewer (roadmap 6.6). The backend runs it
as a background job when a CAD document with a STEP file is checked in:

```bash
npm ci
node convert.mjs model.step model.glb
```

It uses [occt-import-js](https://github.com/kovacsv/occt-import-js) (OpenCASCADE compiled to
WebAssembly, LGPL-2.1), keeps the assembly's names and colours, and prints a JSON summary.
The backend finds it at `plm.cad.converter.script` (default `workers/cad-converter/convert.mjs`
from the repository root) and runs it with `plm.cad.converter.node` (default `node`).
