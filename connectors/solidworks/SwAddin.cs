// SolidWorks add-in (roadmap 8.8): Save to PLM and Open from PLM on the connector protocol.
// A starting point, not a finished connector: it has not been built or run here (that needs
// Windows, SolidWorks and its SDK). See README.md for what it does and what is left.
using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text.Json;
using System.Threading.Tasks;
using SolidWorks.Interop.sldworks;
using SolidWorks.Interop.swconst;
using SolidWorks.Interop.swpublished;

namespace Plm.Connectors.SolidWorks
{
    [ComVisible(true)]
    [Guid("5B0E3C1E-7A63-4C0C-9E1E-4B7F1A9D2C31")]
    public sealed class SwAddin : ISwAddin
    {
        private const int CommandGroupId = 4711;
        private ISldWorks? _sw;
        private int _cookie;

        public bool ConnectToSW(object thisSw, int cookie)
        {
            _sw = (ISldWorks)thisSw;
            _cookie = cookie;
            _sw.SetAddinCallbackInfo2(0, this, cookie);
            var commands = _sw.GetCommandManager(cookie);
            var errors = 0;
            var group = commands.CreateCommandGroup2(CommandGroupId, "PLM", "Save to and open from PLM", "", -1, true, ref errors);
            group.AddCommandItem2("Save to PLM", -1, "Save this document and what it references, and check them in", "Save to PLM", 0,
                nameof(SaveToPlm), "", 1, (int)(swCommandItemType_e.swMenuItem | swCommandItemType_e.swToolbarItem));
            group.AddCommandItem2("Open from PLM", -1, "Download a model with what it depends on and open it", "Open from PLM", 0,
                nameof(OpenFromPlm), "", 2, (int)(swCommandItemType_e.swMenuItem | swCommandItemType_e.swToolbarItem));
            group.HasToolbar = true;
            group.HasMenu = true;
            group.Activate();
            return true;
        }

        public bool DisconnectFromSW()
        {
            _sw?.GetCommandManager(_cookie).RemoveCommandGroup(CommandGroupId);
            _sw = null;
            return true;
        }

        // ---------- Settings and ids ----------

        private static string SettingsPath =>
            Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "PLM", "solidworks.json");

        /// <summary>Server, token, product, and which file is which model (by full path).</summary>
        private sealed class Settings
        {
            public string Server { get; set; } = "http://localhost:8080";
            public string Token { get; set; } = "";
            public string ContextId { get; set; } = "";
            public Dictionary<string, string> Ids { get; set; } = new(StringComparer.OrdinalIgnoreCase);
        }

        private static Settings Load() =>
            File.Exists(SettingsPath) ? JsonSerializer.Deserialize<Settings>(File.ReadAllText(SettingsPath)) ?? new Settings() : new Settings();

        private static void Store(Settings s)
        {
            Directory.CreateDirectory(Path.GetDirectoryName(SettingsPath)!);
            File.WriteAllText(SettingsPath, JsonSerializer.Serialize(s, new JsonSerializerOptions { WriteIndented = true }));
        }

        private static string KindOf(string path) =>
            Path.GetExtension(path).ToLowerInvariant() switch
            {
                ".sldasm" => "ASSEMBLY",
                ".slddrw" => "DRAWING",
                _ => "PART",
            };

        /// <summary>A component's placement: SolidWorks' 3×3 rotation + translation (metres) as a column-major 4×4 in mm.</summary>
        private static double[] Matrix(MathTransform t)
        {
            var a = (double[])t.ArrayData;
            const double mm = 1000.0;
            return new[]
            {
                a[0], a[1], a[2], 0,
                a[3], a[4], a[5], 0,
                a[6], a[7], a[8], 0,
                a[9] * mm, a[10] * mm, a[11] * mm, 1,
            };
        }

        // ---------- Save ----------

        public void SaveToPlm() => Run(SaveAsync);

        private async Task SaveAsync()
        {
            var doc = (ModelDoc2)_sw!.ActiveDoc ?? throw new PlmException("Open a document first");
            if (string.IsNullOrEmpty(doc.GetPathName())) throw new PlmException("Save the document to a file first");
            int errors = 0, warnings = 0;
            doc.Save3((int)swSaveAsOptions_e.swSaveAsOptions_Silent, ref errors, ref warnings);
            var settings = Load();
            if (string.IsNullOrEmpty(settings.Token) || string.IsNullOrEmpty(settings.ContextId))
                throw new PlmException($"Set your access token and product in {SettingsPath}");
            var client = new PlmClient(settings.Server, settings.Token);
            await client.Check().ConfigureAwait(false);

            // Every file below the document: an assembly's components (all levels), a drawing's models.
            var manifest = new Manifest { ContextId = settings.ContextId, Application = "SOLIDWORKS " + _sw.RevisionNumber() };
            var models = new Dictionary<string, ManifestModel>(StringComparer.OrdinalIgnoreCase);

            ManifestModel Add(string path, string name)
            {
                if (models.TryGetValue(path, out var known)) return known;
                var m = new ManifestModel { Key = path, Name = name, Kind = KindOf(path) };
                if (settings.Ids.TryGetValue(path, out var id)) m.Id = id;
                models[path] = m;
                manifest.Models.Add(m);
                return m;
            }

            var top = Add(doc.GetPathName(), Path.GetFileNameWithoutExtension(doc.GetPathName()));
            if (doc is AssemblyDoc assembly)
            {
                void Walk(Component2 parent, ManifestModel parentModel)
                {
                    foreach (Component2 child in (object[])parent.GetChildren() ?? Array.Empty<object>())
                    {
                        if (child.IsSuppressed() || string.IsNullOrEmpty(child.GetPathName())) continue;
                        var m = Add(child.GetPathName(), Path.GetFileNameWithoutExtension(child.GetPathName()));
                        parentModel.Dependencies.Add(new ManifestDependency
                        {
                            Key = m.Key,
                            Occurrence = child.Name2,
                            Transform = Matrix(child.Transform2),
                        });
                        if (m.Kind == "ASSEMBLY") Walk(child, m);
                    }
                }
                Walk((Component2)((ConfigurationManager)doc.ConfigurationManager).ActiveConfiguration.GetRootComponent3(true), top);
            }
            else if (doc is DrawingDoc)
            {
                var deps = (string[])_sw.GetDocumentDependencies2(doc.GetPathName(), false, true, false) ?? Array.Empty<string>();
                for (var i = 0; i + 1 < deps.Length; i += 2)
                {
                    var m = Add(deps[i + 1], deps[i]);
                    top.Dependencies.Add(new ManifestDependency { Key = m.Key, Kind = "DRAWING" });
                }
            }

            // Send the files (the protocol keeps unchanged models at their version), then save.
            foreach (var m in manifest.Models)
            {
                m.UploadId = await client.Upload(m.Key).ConfigureAwait(false);
                if (m.Id == null && m.Kind != "DRAWING") m.Part = new Dictionary<string, object?> { ["create"] = true };
            }
            var saved = await client.Save(manifest).ConfigureAwait(false);
            foreach (var s in saved.EnumerateArray())
                settings.Ids[s.GetProperty("key").GetString()!] = s.GetProperty("id").GetString()!;
            Store(settings);
            _sw.SendMsgToUser2($"Saved {manifest.Models.Count} document(s) to PLM.",
                (int)swMessageBoxIcon_e.swMbInformation, (int)swMessageBoxBtn_e.swMbOk);
        }

        // ---------- Open ----------

        public void OpenFromPlm() => Run(OpenAsync);

        private async Task OpenAsync()
        {
            var settings = Load();
            var id = Microsoft.VisualBasic.Interaction.InputBox("Model id:", "Open from PLM");
            if (string.IsNullOrWhiteSpace(id)) return;
            var client = new PlmClient(settings.Server, settings.Token);
            var files = await client.Files(id).ConfigureAwait(false);
            var folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments), "PLM", id);
            Directory.CreateDirectory(folder);
            string? first = null;
            using var http = new System.Net.Http.HttpClient();
            foreach (var f in files.EnumerateArray())
            {
                if (f.GetProperty("url").ValueKind != JsonValueKind.String) continue;
                var path = Path.Combine(folder, f.GetProperty("fileName").GetString()!);
                File.WriteAllBytes(path, await http.GetByteArrayAsync(settings.Server.TrimEnd('/') + f.GetProperty("url").GetString()).ConfigureAwait(false));
                settings.Ids[path] = f.GetProperty("id").GetString()!;
                first ??= path;
            }
            Store(settings);
            if (first != null)
            {
                int errors = 0, warnings = 0;
                var type = KindOf(first) switch { "ASSEMBLY" => swDocumentTypes_e.swDocASSEMBLY, "DRAWING" => swDocumentTypes_e.swDocDRAWING, _ => swDocumentTypes_e.swDocPART };
                _sw!.OpenDoc6(first, (int)type, (int)swOpenDocOptions_e.swOpenDocOptions_Silent, "", ref errors, ref warnings);
            }
        }

        private void Run(Func<Task> work)
        {
            try
            {
                work().GetAwaiter().GetResult();
            }
            catch (Exception e)
            {
                _sw?.SendMsgToUser2("PLM: " + e.Message, (int)swMessageBoxIcon_e.swMbStop, (int)swMessageBoxBtn_e.swMbOk);
            }
        }

        // ---------- COM registration (regasm /codebase) ----------

        [ComRegisterFunction]
        public static void Register(Type t)
        {
            using var key = Microsoft.Win32.Registry.LocalMachine.CreateSubKey($@"SOFTWARE\SolidWorks\Addins\{{{t.GUID}}}");
            key.SetValue(null, 0);
            key.SetValue("Title", "PLM");
            key.SetValue("Description", "Save to and open from PLM");
        }

        [ComUnregisterFunction]
        public static void Unregister(Type t) =>
            Microsoft.Win32.Registry.LocalMachine.DeleteSubKey($@"SOFTWARE\SolidWorks\Addins\{{{t.GUID}}}", false);
    }
}
