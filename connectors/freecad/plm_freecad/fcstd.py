"""Reads what the connector needs from a FreeCAD document (.FCStd), without FreeCAD.

An .FCStd file is a zip with a `Document.xml`. The connector needs:
- links to other files: `App::Link` objects (and anything with a `PropertyXLink`) whose `XLink`
  names another file: the assembly's components, with their instance name (Label) and Placement;
- parameters: the aliased cells of `Spreadsheet::Sheet` objects;
- whether it's a drawing: `TechDraw::DrawPage` objects whose views show other files;
- its thumbnail (`thumbnails/Thumbnail.png`), when FreeCAD saved one.
"""
import os
import xml.etree.ElementTree as ET
import zipfile


def _placement_matrix(el):
    """A FreeCAD placement (position + quaternion x, y, z, w) as a column-major 4x4 matrix."""
    px, py, pz = (float(el.get(k, "0")) for k in ("Px", "Py", "Pz"))
    x, y, z, w = (float(el.get(k, "0" if k != "Q3" else "1")) for k in ("Q0", "Q1", "Q2", "Q3"))
    return [
        1 - 2 * (y * y + z * z), 2 * (x * y + z * w), 2 * (x * z - y * w), 0.0,
        2 * (x * y - z * w), 1 - 2 * (x * x + z * z), 2 * (y * z + x * w), 0.0,
        2 * (x * z + y * w), 2 * (y * z - x * w), 1 - 2 * (x * x + y * y), 0.0,
        px, py, pz, 1.0,
    ]


def read_document(path):
    with zipfile.ZipFile(path) as z:
        root = ET.fromstring(z.read("Document.xml"))
        thumbnail = z.read("thumbnails/Thumbnail.png") if "thumbnails/Thumbnail.png" in z.namelist() else None
    types = {}
    for obj in root.iter("Object"):
        if obj.get("type"):
            types[obj.get("name")] = obj.get("type")
    label = ""
    for prop in root.find("Properties") or []:
        if prop.get("name") == "Label":
            s = prop.find("String")
            label = s.get("value", "") if s is not None else ""
    links = []
    parameters = {}
    drawing_sources = set()
    data = root.find("ObjectData")
    for obj in (data if data is not None else []):
        name = obj.get("name")
        kind = types.get(name, "")
        props = {p.get("name"): p for p in obj.iter("Property")}
        obj_label = (props.get("Label").find("String").get("value") if props.get("Label") is not None
                     and props.get("Label").find("String") is not None else name)
        if kind.startswith("Spreadsheet::Sheet"):
            for cell in obj.iter("Cell"):
                alias = cell.get("alias")
                if alias:
                    content = cell.get("content", "")
                    parameters[alias] = content[1:] if content.startswith("=") else content
            continue
        for pname, prop in props.items():
            for xl in prop.iter("XLink"):
                file = xl.get("file", "")
                if not file:
                    continue
                if kind.startswith("TechDraw::"):
                    drawing_sources.add(file)
                    continue
                placement = props.get("Placement")
                pl = placement.find("PropertyPlacement") if placement is not None else None
                links.append({
                    "file": file,
                    "object": xl.get("name", ""),
                    "label": obj_label,
                    "property": pname,
                    "transform": _placement_matrix(pl) if pl is not None else None,
                })
    has_page = any(t.startswith("TechDraw::DrawPage") for t in types.values())
    if has_page and not links:
        kind = "DRAWING"
    elif links or any(t.startswith("Assembly::AssemblyObject") for t in types.values()):
        kind = "ASSEMBLY"
    else:
        kind = "PART"
    return {
        "program": root.get("ProgramVersion", ""),
        "label": label or os.path.splitext(os.path.basename(path))[0],
        "kind": kind,
        "links": links,
        "drawingOf": sorted(drawing_sources),
        "parameters": parameters,
        "thumbnail": thumbnail,
        "objects": len(types),
    }
