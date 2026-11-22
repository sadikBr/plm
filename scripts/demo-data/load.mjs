#!/usr/bin/env node
// Loads the demo data (dataset.mjs) into a running platform through its API, as the site
// administrator. Run it with ./plm load-data, which asks for the password; or directly:
//
//   PLM_ADMIN_PASSWORD=… node scripts/demo-data/load.mjs [voltara|byd] [--company "Name"]
//
// Data sets: voltara (a fictional EV maker; the default) and byd (the same shape with BYD's real
// vehicle names and public figures; the records inside are invented). Both can be loaded together.
//
// It refuses to load twice into the same database (part numbers are unique): ./plm reset starts over.

const BASE = process.env.PLM_API_URL ?? "http://localhost:8080";
const API = BASE + "/api/v1";
const ISSUER = process.env.PLM_OIDC_ISSUER ?? "http://localhost:8180/realms/plm";
const ADMIN = process.env.PLM_ADMIN_USER ?? "plmadmin";
const PART = "00000000-0000-7000-8000-000000000102";
const DOCUMENT = "00000000-0000-7000-8000-000000000101";
const PROBLEM_REPORT = "00000000-0000-7000-8000-000000000104";
const CAD_PART = "00000000-0000-7000-8000-00000000010a";
const CAD_ASSEMBLY = "00000000-0000-7000-8000-00000000010b";

const DATASETS = { voltara: "./voltara.mjs", byd: "./byd.mjs" };
const args = process.argv.slice(2);
const companyArg = args.includes("--company") ? args[args.indexOf("--company") + 1] : undefined;
const named = args.filter((a, i) => !a.startsWith("--") && args[i - 1] !== "--company");
const setName = (args.includes("--dataset") ? args[args.indexOf("--dataset") + 1] : named[0]) ?? "voltara";
if (!DATASETS[setName]) {
  console.error(`Unknown data set "${setName}": choose ${Object.keys(DATASETS).join(" or ")}`);
  process.exit(1);
}
const D = await import(DATASETS[setName]);
const K = D.KEYS;
const company = companyArg ?? D.COMPANY;
if (!company) fail("--company needs a name");

// ---- output ----
const bold = (s) => console.log(`\x1b[1m${s}\x1b[0m`);
const ok = (s) => console.log(`  \x1b[32m✓\x1b[0m ${s}`);
function fail(s) {
  console.error(`  \x1b[31m✗\x1b[0m ${s}`);
  process.exit(1);
}

// ---- API ----
async function token(user, password) {
  const body = new URLSearchParams({ grant_type: "password", client_id: "plm-web", username: user, password });
  let r;
  try {
    r = await fetch(`${ISSUER}/protocol/openid-connect/token`, { method: "POST", body });
  } catch {
    fail(`Keycloak doesn't answer at ${ISSUER}: start the platform first (./plm start)`);
  }
  const json = await r.json().catch(() => ({}));
  if (!r.ok) {
    if (user === ADMIN && /not fully set up/i.test(json.error_description ?? "")) {
      fail(`${ADMIN} must choose its password first: ./plm admin-password (or sign in once in the browser)`);
    }
    if (user === ADMIN) fail(`${ADMIN} couldn't sign in (${json.error_description ?? r.status}): check its password`);
    fail(`${user} couldn't sign in (${json.error_description ?? r.status}); the demo users' password is their name`);
  }
  return json.access_token;
}

function client(bearer) {
  return async function call(method, path, body, { allow404 = false } = {}) {
    let r;
    try {
      r = await fetch(API + path, {
        method,
        headers: { Authorization: `Bearer ${bearer}`, "Content-Type": "application/json" },
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    } catch {
      fail(`The backend doesn't answer at ${API}: start the platform first (./plm start)`);
    }
    const text = await r.text();
    if (allow404 && r.status === 404) return null;
    if (!r.ok) {
      let detail = text;
      try {
        detail = JSON.parse(text).detail ?? text;
      } catch {
        /* not JSON */
      }
      throw new Error(`${method} ${path}: ${r.status} ${detail}`);
    }
    return text ? JSON.parse(text) : null;
  };
}

// ---- a small PDF writer: one or more pages of text, for the documents' primary files ----
function pdf(title, subtitle, lines) {
  const esc = (s) => s.replace(/[\\()]/g, (c) => "\\" + c).replace(/[^\x20-\x7e]/g, "-");
  const wrap = (s, n) => {
    const out = [];
    let line = "";
    for (const word of s.split(" ")) {
      if ((line + " " + word).trim().length > n) {
        out.push(line);
        line = (s.startsWith("  ") ? "   " : "") + word;
      } else line = line ? line + " " + word : word;
    }
    if (line) out.push(line);
    return out;
  };
  const body = lines.flatMap((l) => wrap(l, 92));
  const perPage = 44;
  const pages = [];
  for (let i = 0; i < Math.max(1, body.length); i += perPage) pages.push(body.slice(i, i + perPage));
  const objects = [];
  const add = (s) => objects.push(s) && objects.length;
  add("<< /Type /Catalog /Pages 2 0 R >>");
  add("PAGES");
  add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>");
  add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold >>");
  const kids = [];
  pages.forEach((pageLines, p) => {
    let s = `BT /F2 16 Tf 56 780 Td (${esc(title)}) Tj ET\nBT /F1 9 Tf 56 762 Td (${esc(subtitle)}) Tj ET\n`;
    s += "0.75 G 56 752 m 539 752 l S 0 G\n";
    pageLines.forEach((l, i) => (s += `BT /F1 10.5 Tf 56 ${728 - i * 15.5} Td (${esc(l)}) Tj ET\n`));
    s += `BT /F1 8 Tf 56 40 Td (${esc(`${company} - demonstration document, invented data`)}) Tj ET\n`;
    s += `BT /F1 8 Tf 500 40 Td (${p + 1} / ${pages.length}) Tj ET\n`;
    const content = add(`<< /Length ${Buffer.byteLength(s)} >>\nstream\n${s}endstream`);
    kids.push(
      add(
        `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 3 0 R /F2 4 0 R >> >> /Contents ${content} 0 R >>`,
      ),
    );
  });
  objects[1] = `<< /Type /Pages /Kids [${kids.map((k) => `${k} 0 R`).join(" ")}] /Count ${kids.length} >>`;
  let out = "%PDF-1.4\n";
  const offsets = [];
  objects.forEach((o, i) => {
    offsets.push(Buffer.byteLength(out));
    out += `${i + 1} 0 obj\n${o}\nendobj\n`;
  });
  const xref = Buffer.byteLength(out);
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const o of offsets) out += `${String(o).padStart(10, "0")} 00000 n \n`;
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(out, "latin1");
}

// ---- the load ----
const password = process.env.PLM_ADMIN_PASSWORD;
if (!password) fail("Set PLM_ADMIN_PASSWORD to plmadmin's password (./plm load-data asks for it)");

bold(`Loading the demo data: ${company}`);
const admin = client(await token(ADMIN, password));
const as = {};
for (const user of ["alice", "bob", "carol"]) {
  as[user] = client(await token(user, user));
  await as[user]("GET", "/me"); // signing in puts them in the directory
}
const me = await admin("GET", "/me");
if (!me.siteAdmin) fail(`${ADMIN} isn't a site administrator`);
ok("Signed in as plmadmin; alice, bob and carol are in the directory");

const site = await admin("GET", "/contexts/site");
const existing = await admin("GET", `/contexts/${site.id}/children`);
if (existing.some((c) => c.name === company)) {
  fail(`"${company}" is already there: the demo data is loaded. To load it afresh: ./plm reset, then ./plm load-data`);
}
const taken = await admin("POST", "/search", {
  text: K.sentinel,
  attributes: {},
  contextIds: [],
  states: [],
  typeIds: [],
  limit: 50,
  offset: 0,
}).catch(() => null);
if ((taken?.hits ?? []).some((h) => h.number === K.sentinel)) {
  fail(`The demo parts are already in this database (under another company name): ./plm reset to start over`);
}

const principal = {};
for (const user of ["alice", "bob", "carol"]) principal[user] = (await admin("GET", `/principals?username=${user}`)).id;

async function step(name, fn) {
  try {
    const summary = await fn();
    ok(summary ?? name);
  } catch (e) {
    fail(`${name}: ${e.message}`);
  }
}

// Containers, teams and folders
const ctx = {};
const folders = {};
await step("Organization, products, library and project", async () => {
  const org = await admin("POST", "/contexts", { parentId: site.id, kind: "ORGANIZATION", name: company });
  ctx.org = org;
  for (const [key, [kind, name]] of Object.entries(D.CONTEXTS)) {
    ctx[key] = await admin("POST", "/contexts", { parentId: org.id, kind, name });
  }
  for (const [user, key, role] of D.TEAM) {
    await admin("PUT", `/contexts/${ctx[key].id}/members/${principal[user]}`, { role });
  }
  for (const [, folder] of D.DOCUMENTS) {
    if (folder && !folders[folder]) folders[folder] = (await admin("POST", `/contexts/${ctx.plant.id}/folders`, { name: folder })).id;
  }
  return `${company}: ${Object.keys(D.CONTEXTS).length} products, libraries and projects; alice, bob and carol on their teams`;
});

// Classification
const classes = {};
await step("Classification", async () => {
  const all = (await admin("GET", "/classes").catch(() => [])) ?? [];
  const known = Object.fromEntries((Array.isArray(all) ? all : (all.items ?? [])).map((c) => [c.code, c]));
  let made = 0;
  for (const [code, name, parent, description, attributes] of D.CLASSES) {
    if (known[code]) {
      classes[code] = known[code];
      continue;
    }
    classes[code] = await admin("POST", "/classes", { code, name, description, parentId: parent ? classes[parent].id : null });
    for (const [key, label, dataType, unit, allowed, required] of attributes) {
      await admin("POST", `/classes/${classes[code].id}/attributes`, { key, label, dataType, unit, allowed, required });
    }
    made++;
  }
  return `Classification: ${made} classes${made < D.CLASSES.length ? ` (${D.CLASSES.length - made} already there)` : ""}`;
});

// Parts
const part = {};
await step("Parts", async () => {
  for (const [key, number, name, a, cls, values] of D.PARTS) {
    const attributes = { material: a.material, source: a.source };
    if (a.mass !== undefined) attributes.mass = { value: a.mass, unit: "kg" };
    if (a.trace) attributes.traceCode = a.trace;
    if (a.description) attributes.description = a.description;
    if (number.endsWith("-0001") && !number.startsWith("DOC")) attributes.endItem = true;
    part[number] = await admin("POST", "/objects", { typeId: PART, contextId: ctx[key].id, name, number, attributes });
    if (cls) {
      await admin("POST", `/objects/${part[number].id}/checkout`);
      await admin("PUT", `/objects/${part[number].id}/classification`, { classId: classes[cls].id, values });
      await admin("POST", `/objects/${part[number].id}/checkin`, { comment: "Classified" });
    }
  }
  return `${D.PARTS.length} parts, ${D.PARTS.filter((p) => p[4]).length} of them classified`;
});

// Options (before the vehicle's structure, whose lines have conditions)
let optionSet;
await step("Options", async () => {
  const o = D.OPTION_SET;
  optionSet = await admin("POST", "/option-sets", { contextId: ctx[o.context].id, name: o.name, description: o.description });
  for (const [code, name, required, choices] of o.options) {
    optionSet = await admin("POST", `/option-sets/${optionSet.id}/options`, { code, name, required });
    const option = optionSet.options.find((x) => x.code === code);
    for (const [c, n] of choices) optionSet = await admin("POST", `/options/${option.id}/choices`, { code: c, name: n, retired: false });
  }
  for (const [kind, [io, ic], then, note] of o.rules) {
    await admin("POST", `/option-sets/${optionSet.id}/rules`, {
      kind,
      ifChoice: { option: io, choice: ic },
      thenChoices: then.map(([option, choice]) => ({ option, choice })),
      note,
    });
  }
  return `${o.name}: ${o.options.length} options, ${o.rules.length} rules`;
});

// Structures
await step("Structures", async () => {
  let lines = 0;
  for (const [parent, children] of Object.entries(D.STRUCTURES)) {
    const p = part[parent];
    if (children.some((c) => c[2])) await admin("PUT", `/objects/${p.id}/options`, { optionSetId: optionSet.id });
    await admin("POST", `/objects/${p.id}/checkout`);
    for (const [child, quantity, condition, position] of children) {
      const line = await admin("POST", `/objects/${p.id}/usages`, {
        childId: part[child].id,
        quantity,
        notes: position ? `Position: ${position}` : null,
      });
      if (condition) await admin("PUT", `/objects/${p.id}/usages/${line.id}/condition`, { expression: condition });
      lines++;
    }
    await admin("POST", `/objects/${p.id}/checkin`, { comment: "Structure" });
  }
  return `${Object.keys(D.STRUCTURES).length} assemblies, ${lines} structure lines`;
});

// Documents, with a PDF each, linked to their parts
const doc = {};
await step("Documents", async () => {
  for (const [key, folder, number, title, category, department, links, text] of D.DOCUMENTS) {
    const d = await admin("POST", "/objects", {
      typeId: DOCUMENT,
      contextId: ctx[key].id,
      folderId: folder ? folders[folder] : null,
      name: title,
      number,
      attributes: { title, category, department },
    });
    doc[number] = d;
    const file = pdf(title, `${number}  rev. A  -  ${D.CONTEXTS[key][1]}`, text);
    await admin("POST", `/objects/${d.id}/checkout`);
    const ticket = await admin("POST", "/vault/uploads", { fileName: `${number}.pdf`, contentType: "application/pdf", size: file.length });
    const put = await fetch(new URL(ticket.uploadUrl, BASE), { method: "PUT", body: file, headers: { "Content-Type": "application/pdf" } });
    if (!put.ok) throw new Error(`uploading ${number}.pdf: ${put.status} ${await put.text()}`);
    await admin("POST", `/objects/${d.id}/content`, { uploadId: ticket.uploadId, role: "PRIMARY", description: null });
    await admin("POST", `/objects/${d.id}/checkin`, { comment: "Issued" });
  }
  // Links from parts to documents (on the parts' working copies).
  const byPart = {};
  for (const [, , number, , , , links] of D.DOCUMENTS) for (const [p, kind] of links) (byPart[p] ??= []).push([number, kind]);
  let n = 0;
  for (const [p, docs] of Object.entries(byPart)) {
    await admin("POST", `/objects/${part[p].id}/checkout`);
    for (const [number, kind] of docs) {
      await admin("POST", `/objects/${part[p].id}/document-links`, { documentId: doc[number].id, kind });
      n++;
    }
    await admin("POST", `/objects/${part[p].id}/checkin`, { comment: "Documents" });
  }
  return `${D.DOCUMENTS.length} documents with PDFs (plant documents in folders by shop), ${n} links to parts`;
});

// CAD models: a CAD document with its STEP file, owning its part. Converted for the 3D viewer
// on check-in, in the background.
const cad = {};
await step("CAD models", async () => {
  const { readFile } = await import("node:fs/promises");
  const contextOf = Object.fromEntries(D.PARTS.map((p) => [p[1], p[0]]));
  for (const [number, file, kind, name] of D.CAD_MODELS ?? []) {
    const data = await readFile(new URL(`./cad/${file}`, import.meta.url));
    const model = await admin("POST", "/objects", {
      typeId: kind === "ASSEMBLY" ? CAD_ASSEMBLY : CAD_PART,
      contextId: ctx[contextOf[number]].id,
      name,
      number: `CAD-${number}`,
      attributes: {},
    });
    cad[number] = model;
    await admin("POST", `/objects/${model.id}/checkout`);
    const ticket = await admin("POST", "/vault/uploads", { fileName: file, contentType: "model/step", size: data.length });
    const put = await fetch(new URL(ticket.uploadUrl, BASE), { method: "PUT", body: data, headers: { "Content-Type": "model/step" } });
    if (!put.ok) throw new Error(`uploading ${file}: ${put.status} ${await put.text()}`);
    await admin("POST", `/objects/${model.id}/content`, { uploadId: ticket.uploadId, role: "PRIMARY", description: null });
    await admin("POST", `/objects/${model.id}/checkin`, { comment: "Model" });
    await admin("POST", `/objects/${part[number].id}/checkout`);
    await admin("POST", `/objects/${part[number].id}/cad/associations`, { cadId: model.id, kind: "OWNER" });
    await admin("POST", `/objects/${part[number].id}/checkin`, { comment: "3D model" });
  }
  return `${Object.keys(cad).length} CAD models with STEP files, each owning its part (3D views are made in the background)`;
});

// Lifecycle states: children first. Released through the administrator's override: this is data
// brought over from an earlier system, already released there.
await step("Lifecycle states", async () => {
  const wanted = {};
  for (const [key, number, , , , , state] of D.PARTS) wanted[number] = state ?? (key === K.inDevelopment ? "IN_WORK" : "RELEASED");
  const done = new Set();
  const order = [];
  const visit = (n) => {
    if (done.has(n)) return;
    done.add(n);
    for (const [c] of D.STRUCTURES[n] ?? []) visit(c);
    order.push(n);
  };
  Object.keys(part).forEach(visit);
  const counts = {};
  for (const n of order) {
    const to = wanted[n];
    if (to !== "IN_WORK") await admin("POST", `/objects/${part[n].id}/state`, { state: "UNDER_REVIEW" });
    if (to === "RELEASED") {
      await admin("POST", `/objects/${part[n].id}/state`, {
        state: "RELEASED",
        override: true,
        comment: "Released in the previous PLM system; imported",
      });
    }
    counts[to] = (counts[to] ?? 0) + 1;
  }
  for (const d of [...Object.values(doc), ...Object.values(cad)]) {
    await admin("POST", `/objects/${d.id}/state`, { state: "UNDER_REVIEW" });
    await admin("POST", `/objects/${d.id}/state`, { state: "RELEASED", override: true, comment: "Imported" });
  }
  return `Parts: ${counts.RELEASED ?? 0} released, ${counts.UNDER_REVIEW ?? 0} under review, ${counts.IN_WORK ?? 0} in work; documents and models released`;
});

// Effectivity: the vehicle is the end item; packs and seal effective by vehicle serial number.
await step("Effectivity", async () => {
  const aurora = part[K.vehicle];
  await admin("POST", "/effectivity-contexts", {
    endItemId: aurora.id,
    description: `${aurora.name}: by vehicle serial number (VIN sequence)`,
  });
  for (const n of K.serialEffective) {
    await admin("POST", `/objects/${part[n].id}/effectivity`, {
      target: "VERSION",
      revision: "A",
      kind: "SERIAL",
      from: "1",
      endItemId: aurora.id,
    });
  }
  await admin("POST", `/objects/${part[D.CHANGES.seal.part].id}/effectivity`, {
    target: "VERSION",
    revision: "A",
    kind: "SERIAL",
    from: "1",
    to: String(Number(D.CHANGES.seal.fromSerial) - 1),
    endItemId: aurora.id,
  });
  return `${aurora.number} is an end item; packs, drive unit, body and door seal effective by vehicle serial`;
});

// Variant specifications, and their generated variant parts
await step("Variants", async () => {
  const aurora = part[K.vehicle];
  let made = 0;
  for (const [name, selection, description] of D.OPTION_SET.variants) {
    const spec = await admin("POST", `/objects/${aurora.id}/variant-specs`, { name, selection, description, spec: { type: "LATEST" } });
    const result = await admin("POST", `/variant-specs/${spec.id}/generate`);
    made += result?.made ?? result?.created ?? 0;
  }
  return `${D.OPTION_SET.variants.length} variant specifications, generated (${made} variant parts)`;
});

// Changes: problem reports from the line and the lab; the seal's change through to resolution.
await step("Changes", async () => {
  const pr = {};
  for (const p of D.CHANGES.problems) {
    const who = as[p.by];
    pr[p.key] = await who("POST", "/objects", { typeId: PROBLEM_REPORT, contextId: ctx[p.context].id, name: p.name, attributes: {} });
    for (const a of p.affected) await who("POST", `/changes/${pr[p.key].id}/links`, { role: "AFFECTED", targetId: part[a].id });
    for (const [user, body] of p.comments) await as[user]("POST", `/objects/${pr[p.key].id}/comments`, { body });
  }
  const advance = async (id, states) => {
    for (const state of states) await admin("POST", `/objects/${id}/state`, { state, override: true });
  };
  // The seal: request approved, notice resolved; revision B effective from unit 5000.
  const s = D.CHANGES.seal;
  await advance(pr.seal.id, ["UNDER_REVIEW", "APPROVED"]);
  const cr = await as.alice("POST", "/changes/from", { fromId: pr.seal.id, kind: "CHANGE_REQUEST", name: s.request });
  await advance(cr.id, ["UNDER_REVIEW", "APPROVED"]);
  const cn = await as.alice("POST", "/changes/from", { fromId: cr.id, kind: "CHANGE_NOTICE", name: s.notice });
  const seal = part[s.part];
  await admin("POST", `/objects/${seal.id}/revise`);
  const wc = await admin("GET", `/objects/${seal.id}`);
  if (wc.checkedOutBy) await admin("POST", `/objects/${seal.id}/checkin`, { comment: "Lip profile revised (rev B)" });
  await admin("POST", `/changes/${cn.id}/links`, { role: "RESULTING", targetId: seal.id }).catch(() => null);
  await admin("POST", `/objects/${seal.id}/effectivity`, {
    target: "VERSION",
    revision: "B",
    kind: "SERIAL",
    from: s.fromSerial,
    endItemId: part[K.vehicle].id,
    changeId: cn.id,
  });
  await advance(cn.id, ["UNDER_REVIEW", "APPROVED", "IMPLEMENTATION", "RESOLVED"]);
  // The cell: a request under review.
  await advance(pr.cell.id, ["UNDER_REVIEW"]);
  const cellCr = await as.alice("POST", "/changes/from", { fromId: pr.cell.id, kind: "CHANGE_REQUEST", name: D.CHANGES.cellRequest });
  await advance(cellCr.id, ["UNDER_REVIEW"]);
  return `${D.CHANGES.problems.length} problem reports with discussions, 2 change requests, 1 change notice resolved (door seal rev. B from unit ${s.fromSerial})`;
});

// A baseline of the vehicle at start of production
await step("Baseline", async () => {
  const masters = new Set();
  const walk = (n) => {
    masters.add(part[n].id);
    for (const [c] of D.STRUCTURES[n] ?? []) walk(c);
  };
  walk(K.vehicle);
  const b = await admin("POST", "/baselines", {
    contextId: ctx.aurora.id,
    name: K.baseline[0],
    description: K.baseline[1],
  });
  await admin("POST", `/baselines/${b.id}/members`, { masterIds: [...masters], iterationIds: [] });
  await admin("POST", `/baselines/${b.id}/freeze`);
  return `Baseline "${K.baseline[0]}" (${masters.size} parts), frozen`;
});

// Saved reports (report builder), shared: named after the company, as each set brings the same ones.
await step("Reports", async () => {
  // Each report keeps to this company's containers.
  const own = Object.keys(D.CONTEXTS).map((k) => ctx[k].id);
  for (const r of D.REPORTS ?? []) {
    const d = r.definition;
    const scope = { field: { alias: d.from.alias, field: "context" }, operator: "IN", value: own, conditions: [] };
    const where = d.where ? { group: "AND", conditions: [d.where, scope] } : scope;
    await admin("POST", "/reports", {
      name: `${company}: ${r.name}`,
      description: r.description,
      definition: { ...d, where },
      answers: r.answers ?? {},
      groupBy: r.groupBy,
      shared: true,
      columns: ["number"],
      query: { text: "", typeIds: [], states: [], contextIds: [], attributes: {}, offset: 0, limit: 25 },
      recipients: [],
      alert: "ALWAYS",
      timeZone: "UTC",
    });
  }
  return `${(D.REPORTS ?? []).length} shared reports (Reports in the top bar)`;
});

console.log();
bold("Demo data loaded.");
console.log(`  Open http://localhost:5173 › Organizations › ${company}. Things to look at:`);
for (const line of D.TOUR) console.log(`  · ${line}`);
console.log("  Sign in as alice (chief engineer), bob (manufacturing) or carol (quality) to see their view.");
