"""Command line: python -m plm_freecad save|open|inspect … (PLM_SERVER and PLM_TOKEN set)."""
import argparse
import json
import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "sdk", "python"))
from plm_connector import PlmConnector, PlmError  # noqa: E402

from . import connector, fcstd  # noqa: E402


def main(argv=None):
    p = argparse.ArgumentParser(prog="plm_freecad", description="Save FreeCAD documents to PLM, open them from it")
    sub = p.add_subparsers(dest="command", required=True)
    s = sub.add_parser("save", help="save documents and everything they link to")
    s.add_argument("files", nargs="+")
    s.add_argument("--context", required=True, help="product, library or project id for new models")
    s.add_argument("--part", action="store_true", help="make a part for each new model")
    s.add_argument("--keep-checked-out", action="store_true")
    s.add_argument("-m", "--comment")
    o = sub.add_parser("open", help="download a model and its dependencies")
    o.add_argument("model")
    o.add_argument("folder")
    i = sub.add_parser("inspect", help="show what a document links to (no server needed)")
    i.add_argument("file")
    a = p.parse_args(argv)
    if a.command == "inspect":
        doc = fcstd.read_document(a.file)
        doc.pop("thumbnail", None)
        print(json.dumps(doc, indent=2))
        return 0
    plm = PlmConnector(os.environ.get("PLM_SERVER", "http://localhost:8080"), os.environ["PLM_TOKEN"])
    try:
        if a.command == "save":
            connector.save(plm, a.files, a.context, a.part, not a.keep_checked_out, a.comment)
        else:
            connector.open_model(plm, a.model, a.folder)
    except (PlmError, ValueError) as e:
        print(f"plm_freecad: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
