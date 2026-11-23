// plm-sync: keeps a PLM workspace (roadmap 8.1) in sync with a folder (roadmap 8.3).
//
// The folder holds `.plm-sync.json`: the server, the workspace, and for each file what was last
// brought down (object, iteration, SHA-256). Comparing a file's hash now with that record tells
// whether it was changed here; the server tells whether a newer iteration exists. Nothing local is
// ever overwritten: a file changed here that is also out of date is kept, and the new one is saved
// next to it as `name (server version).ext`.
import { createHash } from "node:crypto";
import { existsSync } from "node:fs";
import { mkdir, readFile, readdir, stat, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { basename, dirname, extname, join, relative } from "node:path";

export const STATE_FILE = ".plm-sync.json";
const IGNORED = new Set([STATE_FILE, ".DS_Store", "Thumbs.db", "desktop.ini"]);

/** Where `plm-sync login` keeps the server and token. */
export function configPath() {
  const base = process.env.PLM_SYNC_CONFIG_DIR ?? join(process.env.XDG_CONFIG_HOME ?? join(homedir(), ".config"), "plm-sync");
  return join(base, "config.json");
}

export async function loadConfig() {
  const env = process.env.PLM_TOKEN ? { server: process.env.PLM_SERVER ?? "http://localhost:8080", token: process.env.PLM_TOKEN } : null;
  if (env) return env;
  try {
    return JSON.parse(await readFile(configPath(), "utf8"));
  } catch {
    throw new Error("Not signed in: run `plm-sync login --server <url> --token <personal token>` (make a token in My preferences)");
  }
}

export async function saveConfig(config) {
  await mkdir(dirname(configPath()), { recursive: true });
  await writeFile(configPath(), JSON.stringify(config, null, 2) + "\n", { mode: 0o600 });
}

export function sha256(bytes) {
  return createHash("sha256").update(bytes).digest("hex");
}

/** A small API client: JSON in and out, errors as their problem detail. */
export class Api {
  constructor(server, token, fetchImpl = globalThis.fetch) {
    this.server = server.replace(/\/+$/, "");
    this.token = token;
    this.fetch = fetchImpl;
  }

  url(path) {
    return path.startsWith("http") ? path : this.server + path;
  }

  async call(method, path, body) {
    const res = await this.fetch(this.url(path), {
      method,
      headers: { Authorization: `Bearer ${this.token}`, ...(body !== undefined ? { "Content-Type": "application/json" } : {}) },
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    const data = text ? JSON.parse(text) : null;
    if (!res.ok) {
      const err = new Error(data?.detail ?? `${method} ${path}: ${res.status}`);
      err.code = data?.code;
      err.status = res.status;
      throw err;
    }
    return data;
  }

  get = (path) => this.call("GET", path);
  post = (path, body = {}) => this.call("POST", path, body);

  async bytes(path) {
    const res = await this.fetch(this.url(path), { headers: { Authorization: `Bearer ${this.token}` } });
    if (!res.ok) throw new Error(`Download failed (${res.status})`);
    return Buffer.from(await res.arrayBuffer());
  }

  /** Sends bytes to the vault: an upload id. */
  async upload(fileName, bytes) {
    const ticket = await this.post("/api/v1/vault/uploads", { fileName, size: bytes.length });
    const res = await this.fetch(this.url(ticket.uploadUrl), {
      method: "PUT",
      headers: { "Content-Type": "application/octet-stream" },
      body: bytes,
    });
    if (!res.ok) throw new Error(`Upload of ${fileName} failed (${res.status})`);
    return ticket.uploadId;
  }
}

export async function loadState(dir) {
  const path = join(dir, STATE_FILE);
  if (!existsSync(path)) throw new Error(`${dir} isn't a synced folder: run \`plm-sync init <workspace> ${dir}\` first`);
  return JSON.parse(await readFile(path, "utf8"));
}

export async function saveState(dir, state) {
  await writeFile(join(dir, STATE_FILE), JSON.stringify(state, null, 2) + "\n");
}

/** Every file under [dir] (relative paths), except the state file and system clutter. */
export async function listFiles(dir, base = dir) {
  const out = [];
  for (const entry of await readdir(dir, { withFileTypes: true })) {
    if (IGNORED.has(entry.name) || entry.name.startsWith(".")) continue;
    const full = join(dir, entry.name);
    if (entry.isDirectory()) out.push(...(await listFiles(full, base)));
    else out.push(relative(base, full));
  }
  return out.sort();
}

/**
 * What changed in the folder since the last sync: tracked files changed or deleted here, and
 * files that aren't tracked at all (new). Pure, given the hashes: easy to test.
 */
export function localChanges(state, hashes) {
  const tracked = new Map(Object.entries(state.files ?? {}).map(([objectId, f]) => [f.path, { objectId, ...f }]));
  const modified = [];
  const deleted = [];
  for (const [path, f] of tracked) {
    if (!(path in hashes)) deleted.push(f);
    else if (hashes[path] !== f.sha256) modified.push({ ...f, sha256: hashes[path] });
  }
  const created = Object.keys(hashes).filter((p) => !tracked.has(p));
  return { modified, deleted, created };
}

export async function hashFolder(dir) {
  const hashes = {};
  for (const path of await listFiles(dir)) hashes[path] = sha256(await readFile(join(dir, path)));
  return hashes;
}

/** Tells the server what this computer has, so its file states are right. */
export async function report(api, dir, state, hashes) {
  const files = Object.entries(state.files ?? {}).map(([objectId, f]) => ({
    objectId,
    iterationId: f.iterationId,
    sha256: hashes[f.path] ?? null,
  }));
  if (files.length) await api.post(`/api/v1/workspaces/${state.workspaceId}/local`, { files });
  return api.get(`/api/v1/workspaces/${state.workspaceId}`);
}

/** `name.ext` → `name (B.1).ext`, for keeping both when a change here meets a newer one there. */
export function sideName(path, version) {
  const ext = extname(path);
  return join(dirname(path), `${basename(path, ext)} (${version})${ext}`);
}

/** Brings files up to date. Changed-here files are never overwritten. */
export async function pull(api, dir, { all = false, log = console.log } = {}) {
  const state = await loadState(dir);
  state.files ??= {};
  const hashes = await hashFolder(dir);
  const ws = await report(api, dir, state, hashes);
  const ids = all ? ws.items.map((i) => i.objectId) : [];
  const downloads = await api.get(
    `/api/v1/workspaces/${state.workspaceId}/downloads${ids.length ? "?" + ids.map((i) => `ids=${i}`).join("&") : ""}`,
  );
  // Items never brought down here are missing locally: fetch them too.
  const missing = ws.items.filter((i) => !state.files[i.objectId] && (i.workingFile ?? i.file));
  const extra = missing.length
    ? await api.get(`/api/v1/workspaces/${state.workspaceId}/downloads?${missing.map((i) => `ids=${i.objectId}`).join("&")}`)
    : [];
  const seen = new Set();
  let count = 0;
  for (const d of [...downloads, ...extra]) {
    if (seen.has(d.objectId)) continue;
    seen.add(d.objectId);
    const known = state.files[d.objectId];
    const path = known?.path ?? d.fileName;
    const changedHere = known && hashes[path] !== undefined && hashes[path] !== known.sha256;
    const bytes = await api.bytes(d.url);
    if (sha256(bytes) !== d.sha256) throw new Error(`${d.number}: the download doesn't match its checksum`);
    if (changedHere) {
      const side = sideName(path, d.version);
      await writeFile(join(dir, side), bytes);
      log(`! ${path}: changed here and on the server; the server's ${d.version} is saved as ${side}`);
      continue;
    }
    await mkdir(dirname(join(dir, path)), { recursive: true });
    await writeFile(join(dir, path), bytes);
    state.files[d.objectId] = { path, number: d.number, iterationId: d.iterationId, version: d.version, sha256: d.sha256 };
    log(`↓ ${path} (${d.number} ${d.version})`);
    count++;
  }
  await saveState(dir, state);
  await report(api, dir, state, await hashFolder(dir));
  return count;
}

/** Sends what changed here: changed files into working copies (checking out), new files as new files. */
export async function push(api, dir, { log = console.log } = {}) {
  const state = await loadState(dir);
  state.files ??= {};
  const hashes = await hashFolder(dir);
  const { modified, created } = localChanges(state, hashes);
  let count = 0;
  const failed = [];
  // One file that can't go (released, someone else's) doesn't stop the others.
  for (const f of modified) {
    try {
      const bytes = await readFile(join(dir, f.path));
      const uploadId = await api.upload(basename(f.path), bytes);
      await api.post(`/api/v1/workspaces/${state.workspaceId}/upload`, { objectId: f.objectId, uploadId });
      // The server now has this file in the working copy: it's the reference for "changed here".
      state.files[f.objectId] = { ...state.files[f.objectId], sha256: f.sha256 };
      log(`↑ ${f.path} (${f.number})`);
      count++;
    } catch (e) {
      failed.push(f.path);
      log(`! ${f.path}: ${e.message}`);
    }
  }
  const known = new Set((await api.get(`/api/v1/workspaces/${state.workspaceId}`)).newFiles.map((n) => n.fileName));
  for (const path of created) {
    if (known.has(path) && !(state.sentNew ?? {})[path]) state.sentNew = { ...state.sentNew, [path]: hashes[path] };
    if ((state.sentNew ?? {})[path] === hashes[path]) continue;
    try {
      const bytes = await readFile(join(dir, path));
      const uploadId = await api.upload(basename(path), bytes);
      await api.post(`/api/v1/workspaces/${state.workspaceId}/new-files`, { uploadId, fileName: path, size: bytes.length });
      state.sentNew = { ...state.sentNew, [path]: hashes[path] };
      log(`+ ${path} (new: make it an object on the workspace page)`);
      count++;
    } catch (e) {
      failed.push(path);
      log(`! ${path}: ${e.message}`);
    }
  }
  await saveState(dir, state);
  await report(api, dir, state, await hashFolder(dir));
  if (failed.length) {
    const err = new Error(`${failed.length} file(s) couldn't be sent: ${failed.join(", ")}`);
    err.sent = count;
    throw err;
  }
  return count;
}

/** Checks in everything checked out to you in the workspace, after pushing what changed. */
export async function checkin(api, dir, comment, { log = console.log } = {}) {
  await push(api, dir, { log });
  const state = await loadState(dir);
  const results = await api.post(`/api/v1/workspaces/${state.workspaceId}/check-in`, { comment: comment ?? null });
  const ws = await api.get(`/api/v1/workspaces/${state.workspaceId}`);
  for (const r of results) {
    const item = ws.items.find((i) => i.objectId === r.objectId);
    const f = state.files[r.objectId];
    if (f && item?.file) state.files[r.objectId] = { ...f, version: r.version, sha256: item.file.sha256 };
    log(`✓ ${r.number} → ${r.version}`);
  }
  // The new iterations are what this computer has.
  const latest = await api.get(`/api/v1/workspaces/${state.workspaceId}/downloads?${results.map((r) => `ids=${r.objectId}`).join("&")}`);
  for (const d of latest) if (state.files[d.objectId]) state.files[d.objectId].iterationId = d.iterationId;
  await saveState(dir, state);
  await report(api, dir, state, await hashFolder(dir));
  return results;
}

/** Makes [dir] a synced copy of a workspace (by name or id) and brings everything down. */
export async function init(api, workspace, dir, { log = console.log } = {}) {
  const list = await api.get("/api/v1/workspaces");
  const ws = list.find((w) => w.id === workspace || w.name.toLowerCase() === workspace.toLowerCase());
  if (!ws) throw new Error(`No workspace “${workspace}”: you have ${list.map((w) => `“${w.name}”`).join(", ") || "none"}`);
  await mkdir(dir, { recursive: true });
  if (existsSync(join(dir, STATE_FILE))) throw new Error(`${dir} is already synced`);
  await saveState(dir, { server: api.server, workspaceId: ws.id, workspace: ws.name, files: {} });
  log(`Syncing “${ws.name}” into ${dir}`);
  await pull(api, dir, { all: true, log });
  return ws;
}

const LABELS = {
  IN_SYNC: "in sync",
  OUT_OF_DATE: "out of date",
  MODIFIED: "modified",
  CHECKED_OUT: "checked out",
  CONFLICT: "CONFLICT",
  CHECKED_OUT_BY_OTHER: "someone else's",
  UNAVAILABLE: "unavailable",
};

/** The workspace's files with their states, after telling the server what this computer has. */
export async function status(api, dir) {
  const state = await loadState(dir);
  const hashes = await hashFolder(dir);
  const ws = await report(api, dir, state, hashes);
  const { created } = localChanges(state, hashes);
  const rows = ws.items.map((i) => ({
    state: LABELS[i.fileState] ?? i.fileState,
    number: i.number,
    version: i.baseVersion && i.baseVersion !== i.version ? `${i.baseVersion} → ${i.version}` : (i.version ?? ""),
    file: state.files[i.objectId]?.path ?? i.file?.fileName ?? "",
  }));
  for (const path of created) {
    const sent = (state.sentNew ?? {})[path] === hashes[path];
    rows.push({ state: sent ? "new (sent)" : "new", number: "", version: "", file: path });
  }
  for (const f of ws.newFiles) if (!created.includes(f.fileName)) rows.push({ state: "new (sent)", number: "", version: "", file: f.fileName });
  return { workspace: ws.workspace, rows };
}

/** Keeps the folder in sync: pushes changes as they happen, pulls new iterations every [every] ms. */
export async function watch(api, dir, { every = 30_000, log = console.log, signal } = {}) {
  const { watch: fsWatch } = await import("node:fs");
  let timer = null;
  const run = async (what) => {
    try {
      if (what === "push") await push(api, dir, { log });
      else await pull(api, dir, { log });
    } catch (e) {
      log(`! ${e.message}`);
    }
  };
  const watcher = fsWatch(dir, { recursive: true }, (_event, file) => {
    if (!file || file.endsWith(STATE_FILE) || basename(file).startsWith(".")) return;
    clearTimeout(timer);
    timer = setTimeout(() => void run("push"), 1500);
  });
  const interval = setInterval(() => void run("pull"), every);
  await run("pull");
  log(`Watching ${dir} (Ctrl+C to stop)`);
  await new Promise((resolve) => signal?.addEventListener("abort", resolve));
  watcher.close();
  clearInterval(interval);
}

export async function isDirectory(path) {
  try {
    return (await stat(path)).isDirectory();
  } catch {
    return false;
  }
}

