# FreeCAD workbench for the PLM connector (roadmap 8.6). Install: copy or link this folder into
# FreeCAD's Mod folder (e.g. ~/.local/share/FreeCAD/Mod/PLM), with ../sdk/python beside it as
# described in connectors/freecad/README.md. The commands call plm_freecad.connector, which is
# tested without FreeCAD; this file only adds the menu and toolbar.
import os
import sys

import FreeCAD
import FreeCADGui

HERE = os.path.dirname(__file__)
for p in (HERE, os.path.join(HERE, "..", "sdk", "python"), os.path.join(HERE, "sdk")):
    if os.path.isdir(p) and p not in sys.path:
        sys.path.insert(0, p)


def _settings():
    """Server, token and default product from FreeCAD's parameters (Edit > Preferences > PLM)."""
    p = FreeCAD.ParamGet("User parameter:BaseApp/Preferences/Mod/PLM")
    server = p.GetString("Server", os.environ.get("PLM_SERVER", "http://localhost:8080"))
    token = p.GetString("Token", os.environ.get("PLM_TOKEN", ""))
    context = p.GetString("Context", "")
    return p, server, token, context


def _ask(title, label, value=""):
    from PySide import QtGui

    text, ok = QtGui.QInputDialog.getText(None, title, label, text=value)
    return text if ok else None


class SaveToPlm:
    def GetResources(self):
        return {"MenuText": "Save to PLM", "ToolTip": "Save this document and what it links to, and check them in"}

    def IsActive(self):
        return FreeCAD.ActiveDocument is not None

    def Activated(self):
        from plm_connector import PlmConnector, PlmError
        from plm_freecad import connector

        doc = FreeCAD.ActiveDocument
        if not doc.FileName:
            FreeCAD.Console.PrintError("Save the document to a file first\n")
            return
        doc.save()
        params, server, token, context = _settings()
        if not token:
            token = _ask("PLM", "Your access token (My preferences > Access tokens):")
            if not token:
                return
            params.SetString("Token", token)
        state = connector.load_state(os.path.dirname(doc.FileName))
        context = state.get("contextId") or context or _ask("PLM", "Product, library or project id for new models:")
        if not context:
            return
        comment = _ask("PLM", "Check-in comment:", "") or None
        try:
            connector.save(PlmConnector(server, token), [doc.FileName], context, part=True, comment=comment,
                           log=lambda line: FreeCAD.Console.PrintMessage(line + "\n"))
        except (PlmError, ValueError) as e:
            FreeCAD.Console.PrintError(f"PLM: {e}\n")


class OpenFromPlm:
    def GetResources(self):
        return {"MenuText": "Open from PLM", "ToolTip": "Download a model with what it depends on and open it"}

    def Activated(self):
        from plm_connector import PlmConnector, PlmError
        from plm_freecad import connector

        _, server, token, _ = _settings()
        model = _ask("PLM", "Model id:")
        if not model:
            return
        folder = os.path.join(os.path.expanduser("~"), "PLM", model)
        try:
            files = connector.open_model(PlmConnector(server, token), model, folder,
                                         log=lambda line: FreeCAD.Console.PrintMessage(line + "\n"))
        except PlmError as e:
            FreeCAD.Console.PrintError(f"PLM: {e}\n")
            return
        if files and files[0].get("fileName"):
            FreeCAD.openDocument(os.path.join(folder, files[0]["fileName"]))


class PlmWorkbench(FreeCADGui.Workbench):
    MenuText = "PLM"
    ToolTip = "Save to and open from PLM"

    def Initialize(self):
        FreeCADGui.addCommand("PLM_Save", SaveToPlm())
        FreeCADGui.addCommand("PLM_Open", OpenFromPlm())
        self.appendToolbar("PLM", ["PLM_Open", "PLM_Save"])
        self.appendMenu("PLM", ["PLM_Open", "PLM_Save"])

    def GetClassName(self):
        return "Gui::PythonWorkbench"


FreeCADGui.addWorkbench(PlmWorkbench())
