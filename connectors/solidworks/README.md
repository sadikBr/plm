# SolidWorks add-in (starting point)

**Status: a starting point, not a finished connector.** It has not been compiled or run: that
needs Windows, SolidWorks and its API assemblies, none of which this project's build machines
have. Finishing it is a separate project (roadmap 8.8) for someone with a SolidWorks seat.

What is here, on the [connector protocol](../README.md):

- `PlmClient.cs`: the protocol for .NET (upload, save a manifest, list files to open).
- `SwAddin.cs`: an add-in with **Save to PLM** (walks the active assembly's components at every
  level, with their instance names and placements converted to the protocol's millimetre
  matrices, or a drawing's models; uploads them; saves the manifest; remembers which file is which
  model) and **Open from PLM** (downloads a model with its dependencies and opens it).

## Build and register (on Windows with SolidWorks)

```bat
dotnet build -c Release /p:SolidWorksDir="C:\Program Files\SOLIDWORKS Corp\SOLIDWORKS"
%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\regasm /codebase bin\Release\net48\PlmConnector.SolidWorks.dll
```

Then put the server, your access token (My preferences → Access tokens) and the product id in
`%APPDATA%\PLM\solidworks.json`:

```json
{ "Server": "http://plm.example.com", "Token": "plm_…", "ContextId": "<product id>" }
```

## Left to do

- Only send files that changed (compare hashes, as the FreeCAD connector does) instead of all.
- Configurations: map SolidWorks configurations to family table instances (roadmap 6.3).
- Custom properties ↔ attributes, a settings dialog instead of the JSON file, a task pane showing
  each component's state (as the workspace page does), and an installer.
- The same shape works for Inventor (`Inventor.Application`, `ComponentOccurrences`), Creo
  (J-Link / Toolkit), NX (NX Open) and CATIA (Automation): each needs its vendor's SDK.
