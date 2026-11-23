// Client for the PLM connector protocol (roadmap 8.5), for connectors written in JavaScript or
// TypeScript (Node 22+, or a browser-based tool's host). No dependencies.
//
//   const plm = new PlmConnector("http://localhost:8080", token);
//   const uploadId = await plm.upload("Board.kicad_pcb", bytes);
//   const saved = await plm.save({ contextId, application: "KiCad 8", models: [...] });
//   const files = await plm.open(saved[0].id, "./board");
//
// The protocol is described in connectors/README.md.
import { createHash } from "node:crypto";
import { mkdir, writeFile } from "node:fs/promises";
import { basename, join } from "node:path";

export const PROTOCOL = 1;

export class PlmError extends Error {
  constructor(message, code, status) {
    super(message);
    this.code = code;
    this.status = status;
  }
}

export class PlmConnector {
  constructor(server, token, fetchImpl = globalThis.fetch) {
    this.server = server.replace(/\/+$/, "");
    this.token = token;
    this.fetch = fetchImpl;
  }

  url(path) {
    return path.startsWith("http") ? path : this.server + path;
  }

  async request(method, path, { body, raw, auth = true } = {}) {
    const headers = {};
    if (auth) headers.Authorization = `Bearer ${this.token}`;
    if (body !== undefined) headers["Content-Type"] = "application/json";
    if (raw !== undefined) headers["Content-Type"] = "application/octet-stream";
    const res = await this.fetch(this.url(path), { method, headers, body: raw ?? (body !== undefined ? JSON.stringify(body) : undefined) });
    if (!res.ok) {
      const problem = await res.json().catch(() => ({}));
      throw new PlmError(problem.detail ?? `${method} ${path}: ${res.status}`, problem.code, res.status);
    }
    return res;
  }

  async get(path) {
    return (await this.request("GET", path)).json();
  }

  async post(path, body) {
    return (await this.request("POST", path, { body })).json();
  }

  /** The server speaks this protocol version (or a newer, compatible one). */
  async check() {
    const info = await this.get("/api/v1/connectors");
    if ((info.protocol ?? 0) < PROTOCOL) throw new PlmError("This server is too old for this connector");
    return info;
  }

  /** Sends a file to the vault: the upload id to put in a manifest. */
  async upload(fileName, bytes) {
    const ticket = await this.post("/api/v1/vault/uploads", { fileName: basename(fileName), size: bytes.length });
    await this.request("PUT", ticket.uploadUrl, { raw: bytes, auth: false });
    return ticket.uploadId;
  }

  /** Creates or updates every model of the manifest in one go: all of them or none. */
  save(manifest) {
    return this.post("/api/v1/connectors/save", manifest);
  }

  /** A model and everything it depends on: what to download to open it. */
  files(modelId) {
    return this.get(`/api/v1/connectors/models/${modelId}/files`);
  }

  /** Downloads a model and its dependencies into [folder], checking each file's SHA-256. */
  async open(modelId, folder) {
    await mkdir(folder, { recursive: true });
    const files = await this.files(modelId);
    for (const f of files) {
      if (!f.url) continue;
      const bytes = Buffer.from(await (await this.request("GET", f.url, { auth: false })).arrayBuffer());
      if (createHash("sha256").update(bytes).digest("hex") !== f.sha256) throw new PlmError(`${f.number}: the download doesn't match its checksum`);
      await writeFile(join(folder, f.fileName), bytes);
    }
    return files;
  }
}
