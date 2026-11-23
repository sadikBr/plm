# PLM connector for FreeCAD

Save FreeCAD documents to PLM and open them from it (roadmap 8.6), on the
[connector protocol](../README.md).

- **Save to PLM** saves the active document and every document it links to (`App::Link` and
  other links to other files, to any depth), with their instance names and placements as the
  assembly's dependencies, in one go: all of them or none. Only files changed since the last
  save are sent. New models can get a part each (owned by the model). Drawings (TechDraw pages
  showing other files) are saved as drawings of those models.
- **Open from PLM** downloads a model with everything it depends on into `~/PLM/<id>` and opens it.

The folder's `.plm-freecad.json` remembers which file is which model.

## Install

Copy or link this folder into FreeCAD's `Mod` folder as `PLM`, and `../sdk/python/plm_connector.py`
into it (or keep the repository layout and link the folder). Then **Edit → Preferences** has nothing
to set: the first save asks for your access token (from **My preferences → Access tokens**) and
the product to save into, and remembers both.

## Without FreeCAD

The same code works from the command line, which is how it is tested:

```bash
export PLM_SERVER=http://localhost:8080 PLM_TOKEN=plm_…
python -m plm_freecad inspect Engine.FCStd                       # what it links to
python -m plm_freecad save Engine.FCStd --context <product id> --part -m "First issue"
python -m plm_freecad open <model id> ./engine
```

## Tests

```bash
python -m unittest discover -s tests                 # offline, on real FreeCAD files
PLM_TOKEN=$(../../scripts/token.sh) python -m unittest discover -s tests   # plus the round trip
```
