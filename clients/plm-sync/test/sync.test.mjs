import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { test } from "node:test";
import { Api, checkin, init, localChanges, pull, push, sha256, sideName, status } from "../lib/sync.mjs";

test("local changes: modified, deleted and new files", () => {
  const state = {
    files: {
      a: { path: "a.step", sha256: "1" },
      b: { path: "sub/b.pdf", sha256: "2" },
      c: { path: "c.txt", sha256: "3" },
    },
  };
  const { modified, deleted, created } = localChanges(state, { "a.step": "1", "sub/b.pdf": "9", "new.xlsx": "4" });
  assert.deepEqual(
    modified.map((f) => [f.objectId, f.sha256]),
    [["b", "9"]],
  );
  assert.deepEqual(
    deleted.map((f) => f.objectId),
    ["c"],
  );
  assert.deepEqual(created, ["new.xlsx"]);
});

test("a server version next to a local change gets its own name", () => {
  assert.equal(sideName("models/pack.step", "B.1"), join("models", "pack (B.1).step"));
  assert.equal(sideName("notes", "A.2"), "notes (A.2)");
});

// Against a running server (./plm start): PLM_SERVER and a site administrator's PLM_ADMIN_TOKEN.
const server = process.env.PLM_SERVER ?? "http://localhost:8080";
const adminToken = process.env.PLM_ADMIN_TOKEN;

test("a folder follows a workspace: init, change, push, check in, pull", { skip: !adminToken && "set PLM_ADMIN_TOKEN" }, async () => {
  const admin = new Api(server, adminToken);
  const suffix = Date.now().toString(36);
  const site = await admin.get("/api/v1/contexts/site");
  const org = await admin.post("/api/v1/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `Sync ${suffix}` });
  const product = await admin.post("/api/v1/contexts", { parentId: org.id, kind: "PRODUCT", name: `Sync ${suffix}` });
  const doc = await admin.post("/api/v1/objects", {
    typeId: "00000000-0000-7000-8000-000000000101",
    contextId: product.id,
    name: "Sync spec",
    number: `SYNC-${suffix}`,
  });
  await admin.post(`/api/v1/objects/${doc.id}/checkout`);
  const first = await admin.upload("spec.txt", Buffer.from("v1"));
  await admin.post(`/api/v1/objects/${doc.id}/content`, { uploadId: first, role: "PRIMARY" });
  await admin.post(`/api/v1/objects/${doc.id}/checkin`, { comment: "v1" });

  // A personal token, as a person would make in My preferences.
  const issued = await admin.post("/api/v1/me/tokens", { name: `sync test ${suffix}`, scopes: ["read", "write"] });
  const api = new Api(server, issued.secret);
  const ws = await api.post("/api/v1/workspaces", { name: `Sync ${suffix}`, contextId: product.id });
  await api.post(`/api/v1/workspaces/${ws.id}/items`, { ids: [doc.id] });

  const dir = await mkdtemp(join(tmpdir(), "plm-sync-"));
  const quiet = { log: () => {} };
  await init(api, ws.name, dir, quiet);
  assert.equal(await readFile(join(dir, "spec.txt"), "utf8"), "v1");
  assert.equal((await status(api, dir)).rows[0].state, "in sync");

  // Changed here: modified; pushed: checked out with the file; checked in: a new iteration.
  await writeFile(join(dir, "spec.txt"), "v2");
  assert.equal((await status(api, dir)).rows[0].state, "modified");
  await writeFile(join(dir, "notes.md"), "# notes");
  assert.equal(await push(api, dir, quiet), 2);
  assert.equal((await status(api, dir)).rows[0].state, "checked out");
  const results = await checkin(api, dir, "From my desk", quiet);
  assert.deepEqual(
    results.map((r) => r.version),
    ["A.3"],
  );
  assert.equal((await status(api, dir)).rows[0].state, "in sync");
  const sent = await api.get(`/api/v1/workspaces/${ws.id}`);
  assert.deepEqual(
    sent.newFiles.map((f) => f.fileName),
    ["notes.md"],
  );

  // Someone checks in a newer one: the folder is out of date, and pull brings it down.
  await admin.post(`/api/v1/objects/${doc.id}/checkout`);
  const v3 = await admin.upload("spec.txt", Buffer.from("v3 from the office"));
  await admin.post(`/api/v1/objects/${doc.id}/content`, { uploadId: v3, role: "PRIMARY" });
  await admin.post(`/api/v1/objects/${doc.id}/checkin`, { comment: "office" });
  assert.equal((await status(api, dir)).rows[0].state, "out of date");
  assert.equal(await pull(api, dir, quiet), 1);
  assert.equal(await readFile(join(dir, "spec.txt"), "utf8"), "v3 from the office");
  assert.equal((await status(api, dir)).rows[0].state, "in sync");

  // A change here meeting a newer one there is kept; the server's is saved beside it.
  await admin.post(`/api/v1/objects/${doc.id}/checkout`);
  const v4 = await admin.upload("spec.txt", Buffer.from("v4"));
  await admin.post(`/api/v1/objects/${doc.id}/content`, { uploadId: v4, role: "PRIMARY" });
  await admin.post(`/api/v1/objects/${doc.id}/checkin`, { comment: "again" });
  await writeFile(join(dir, "spec.txt"), "mine");
  assert.equal((await status(api, dir)).rows[0].state, "CONFLICT");
  await pull(api, dir, quiet);
  assert.equal(await readFile(join(dir, "spec.txt"), "utf8"), "mine");
  assert.equal(await readFile(join(dir, "spec (A.5).txt"), "utf8"), "v4");
  assert.equal(sha256(Buffer.from("v4")), sha256(await readFile(join(dir, "spec (A.5).txt"))));
});
