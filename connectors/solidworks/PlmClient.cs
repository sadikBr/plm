// The connector protocol (connectors/README.md) for .NET: upload, save a manifest, list files.
using System;
using System.Collections.Generic;
using System.IO;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using System.Threading.Tasks;

namespace Plm.Connectors.SolidWorks
{
    public sealed class PlmException : Exception
    {
        public string? Code { get; }
        public PlmException(string message, string? code = null) : base(message) => Code = code;
    }

    public sealed class PlmClient
    {
        private static readonly JsonSerializerOptions Json = new() { PropertyNamingPolicy = JsonNamingPolicy.CamelCase };
        private readonly HttpClient _http;
        private readonly string _server;

        public PlmClient(string server, string token)
        {
            _server = server.TrimEnd('/');
            _http = new HttpClient();
            _http.DefaultRequestHeaders.Authorization = new AuthenticationHeaderValue("Bearer", token);
        }

        private string Url(string path) => path.StartsWith("http") ? path : _server + path;

        private async Task<JsonElement> Send(HttpRequestMessage request)
        {
            var response = await _http.SendAsync(request).ConfigureAwait(false);
            var text = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
            var json = string.IsNullOrEmpty(text) ? default : JsonDocument.Parse(text).RootElement.Clone();
            if (!response.IsSuccessStatusCode)
            {
                var detail = json.ValueKind == JsonValueKind.Object && json.TryGetProperty("detail", out var d) ? d.GetString() : null;
                var code = json.ValueKind == JsonValueKind.Object && json.TryGetProperty("code", out var c) ? c.GetString() : null;
                throw new PlmException(detail ?? $"{request.Method} {request.RequestUri}: {(int)response.StatusCode}", code);
            }
            return json;
        }

        public Task<JsonElement> Get(string path) => Send(new HttpRequestMessage(HttpMethod.Get, Url(path)));

        public Task<JsonElement> Post(string path, object body) =>
            Send(new HttpRequestMessage(HttpMethod.Post, Url(path))
            {
                Content = new StringContent(JsonSerializer.Serialize(body, Json), Encoding.UTF8, "application/json"),
            });

        /// <summary>The server speaks protocol 1 or newer.</summary>
        public async Task Check()
        {
            var info = await Get("/api/v1/connectors").ConfigureAwait(false);
            if (info.GetProperty("protocol").GetInt32() < 1) throw new PlmException("This server is too old for this connector");
        }

        /// <summary>Sends a file to the vault: the upload id for a manifest.</summary>
        public async Task<string> Upload(string path)
        {
            var bytes = File.ReadAllBytes(path);
            var ticket = await Post("/api/v1/vault/uploads", new { fileName = Path.GetFileName(path), size = bytes.Length }).ConfigureAwait(false);
            using var put = new HttpRequestMessage(HttpMethod.Put, Url(ticket.GetProperty("uploadUrl").GetString()!))
            {
                Content = new ByteArrayContent(bytes),
            };
            put.Content.Headers.ContentType = new MediaTypeHeaderValue("application/octet-stream");
            put.Headers.Authorization = null;
            await Send(put).ConfigureAwait(false);
            return ticket.GetProperty("uploadId").GetString()!;
        }

        /// <summary>Saves every model of the manifest in one go: all of them or none.</summary>
        public Task<JsonElement> Save(object manifest) => Post("/api/v1/connectors/save", manifest);

        public Task<JsonElement> Files(string modelId) => Get($"/api/v1/connectors/models/{modelId}/files");
    }

    /// <summary>The models of one save, as the protocol names them.</summary>
    public sealed class Manifest
    {
        public string ContextId { get; set; } = "";
        public string Application { get; set; } = "SOLIDWORKS";
        public bool CheckIn { get; set; } = true;
        public string? Comment { get; set; }
        public List<ManifestModel> Models { get; } = new();
    }

    public sealed class ManifestModel
    {
        public string Key { get; set; } = "";
        public string? Id { get; set; }
        public string Name { get; set; } = "";
        public string Kind { get; set; } = "PART";
        public string? UploadId { get; set; }
        public Dictionary<string, object?> Attributes { get; } = new();
        public List<ManifestDependency> Dependencies { get; } = new();
        public Dictionary<string, object?>? Part { get; set; }
    }

    public sealed class ManifestDependency
    {
        public string? Key { get; set; }
        public string Kind { get; set; } = "COMPONENT";
        public bool Required { get; set; } = true;
        public string? Occurrence { get; set; }
        public double[]? Transform { get; set; }
    }
}
