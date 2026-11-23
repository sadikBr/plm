"""The FreeCAD connector (roadmap 8.6): saves FreeCAD documents to PLM, and opens them from it.

Saving a document saves every document it links to (its components, to any depth), and any
drawings named with it, in one manifest: the server creates or updates them all, or none. A
folder's `.plm-freecad.json` remembers which file is which model, and each file's hash at the
last save, so unchanged files aren't sent again.
"""
import hashlib
import json
import os

from . import fcstd

STATE = ".plm-freecad.json"


def _state_path(folder):
    return os.path.join(folder, STATE)


def load_state(folder):
    try:
        with open(_state_path(folder)) as f:
            return json.load(f)
    except (OSError, ValueError):
        return {"models": {}}


def save_state(folder, state):
    with open(_state_path(folder), "w") as f:
        json.dump(state, f, indent=2, sort_keys=True)
        f.write("\n")


def collect(paths):
    """The documents to save: [paths] and every document they link to, keyed by path relative
    to the first one's folder. Links to missing files are reported, never guessed."""
    base = os.path.dirname(os.path.abspath(paths[0]))
    docs = {}
    missing = []
    todo = [os.path.abspath(p) for p in paths]
    while todo:
        path = todo.pop(0)
        key = os.path.relpath(path, base).replace(os.sep, "/")
        if key in docs:
            continue
        if not os.path.exists(path):
            missing.append(key)
            continue
        doc = fcstd.read_document(path)
        doc["path"] = path
        docs[key] = doc
        folder = os.path.dirname(path)
        for link in doc["links"]:
            todo.append(os.path.normpath(os.path.join(folder, link["file"])))
        for source in doc["drawingOf"]:
            todo.append(os.path.normpath(os.path.join(folder, source)))
    return base, docs, missing


def _sha(path):
    with open(path, "rb") as f:
        return hashlib.sha256(f.read()).hexdigest()


def _key(base, doc, file):
    return os.path.relpath(os.path.normpath(os.path.join(os.path.dirname(doc["path"]), file)), base).replace(os.sep, "/")


def manifest(base, docs, state, context_id, upload, part=False, check_in=True, comment=None):
    """The save manifest for [docs]; [upload] sends a changed file and returns its upload id."""
    program = next((d["program"] for d in docs.values() if d.get("program")), "")
    application = "FreeCAD " + program.split(" ")[0] if program else "FreeCAD"
    models = []
    for key, doc in docs.items():
        known = state["models"].get(key, {})
        with open(doc["path"], "rb") as f:
            data = f.read()
        sha = hashlib.sha256(data).hexdigest()
        changed = known.get("sha256") != sha or not known.get("id")
        deps = [
            {"key": _key(base, doc, link["file"]), "kind": "COMPONENT", "required": True,
             "occurrence": link["label"], "transform": link["transform"]}
            for link in doc["links"]
        ] + [
            {"key": _key(base, doc, source), "kind": "DRAWING", "required": True}
            for source in doc["drawingOf"]
        ]
        model = {
            "key": key,
            "id": known.get("id"),
            "name": doc["label"],
            "kind": doc["kind"],
            "uploadId": upload(doc["path"], data) if changed else None,
            "attributes": {"fileFormat": "FreeCAD document"},
            "dependencies": deps,
        }
        if part and doc["kind"] != "DRAWING" and not known.get("partId"):
            model["part"] = {"create": True, "name": doc["label"]}
        models.append(model)
        doc["sha256"] = sha
    return {
        "contextId": context_id,
        "application": application,
        "models": models,
        "checkIn": check_in,
        "comment": comment,
    }


def save(plm, paths, context_id, part=False, check_in=True, comment=None, log=print):
    """Saves [paths] and everything they link to. Returns the saved models."""
    plm.check()
    base, docs, missing = collect(paths)
    if missing:
        raise ValueError("These linked files are missing: " + ", ".join(missing))
    state = load_state(base)
    m = manifest(base, docs, state, context_id, plm.upload, part, check_in, comment)
    saved = plm.save(m)
    for s in saved:
        entry = state["models"].setdefault(s["key"], {})
        entry.update({"id": s["id"], "number": s["number"], "version": s["version"], "sha256": docs[s["key"]]["sha256"]})
        if s.get("partId"):
            entry["partId"] = s["partId"]
        sent = next(x for x in m["models"] if x["key"] == s["key"])["uploadId"] is not None
        log(("+ " if s["created"] else "↑ " if sent else "= ") + f"{s['key']}  {s['number']} {s['version']}"
            + (f"  (part {s['partNumber']})" if s.get("partNumber") else ""))
    state["contextId"] = context_id
    save_state(base, state)
    return saved


def _arrange(folder, files):
    """Puts each file where the documents linking to it expect it (e.g. `parts/Pin.FCStd`): each
    link is matched to its dependency by instance name. The top model stays at the top."""
    by_id = {f["id"]: f for f in files}
    where = {files[0]["id"]: files[0]["fileName"]}
    todo = [files[0]["id"]]
    while todo:
        model = by_id[todo.pop(0)]
        rel = where[model["id"]]
        path = os.path.join(folder, rel)
        if not rel.lower().endswith(".fcstd") or not os.path.exists(path):
            continue
        links = {l["label"]: l["file"] for l in fcstd.read_document(path)["links"]}
        for dep in model["dependencies"]:
            child = by_id.get(dep["id"])
            if child is None or child["id"] in where or not child.get("fileName"):
                continue
            target = links.get(dep.get("occurrence"))
            target = os.path.normpath(os.path.join(os.path.dirname(rel), target)) if target else child["fileName"]
            where[child["id"]] = target.replace(os.sep, "/")
            todo.append(child["id"])
    for f in files:
        rel = where.get(f["id"], f.get("fileName"))
        if not rel or rel == f["fileName"]:
            continue
        os.makedirs(os.path.dirname(os.path.join(folder, rel)) or folder, exist_ok=True)
        os.replace(os.path.join(folder, f["fileName"]), os.path.join(folder, rel))
    return where


def open_model(plm, model_id, folder, log=print):
    """Downloads a model and everything it depends on, ready to open in FreeCAD, each file where
    the documents linking to it expect it."""
    files = plm.open(model_id, folder)
    where = _arrange(folder, files) if files else {}
    state = load_state(folder)
    for f in files:
        if not f.get("fileName"):
            continue
        rel = where.get(f["id"], f["fileName"])
        state["models"][rel] = {
            "id": f["id"], "number": f["number"], "version": f["version"],
            "sha256": _sha(os.path.join(folder, rel)),
        }
        log(f"↓ {rel}  {f['number']} {f['version']}")
    save_state(folder, state)
    return files
