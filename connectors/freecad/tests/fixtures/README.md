Real FreeCAD files from FreeCAD's own examples (github.com/FreeCAD/FreeCAD, `data/examples`):
`parts/Block.FCStd` is `EngineBlock.FCStd`, `parts/Pin.FCStd` is `PartDesignExample.FCStd`, and
`Engine.FCStd` is `AssemblyExample.FCStd` with three of its links pointed at those two files (as
FreeCAD writes links to other documents: `<XLink file="parts/Pin.FCStd" name="Body"/>`).
