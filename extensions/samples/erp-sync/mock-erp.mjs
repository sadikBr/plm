// A stand-in ERP for trying the erp-sync sample's webhooks (version 2.0). No dependencies:
//   ERP_SECRET=demo-key PORT=8099 node mock-erp.mjs
// It checks every request's signature the way a real service should, then answers with what the
// PLM shows: a message, a refresh, or a file to download.
import { createServer } from "node:http";
import { createHmac, timingSafeEqual } from "node:crypto";

const secret = process.env.ERP_SECRET ?? "demo-key";
const port = Number(process.env.PORT ?? 8099);
let orders = 4710;
const stock = new Map();
const stockOf = (number) => {
  if (!stock.has(number)) {
    const seed = [...number].reduce((s, c) => s + c.charCodeAt(0), 0);
    stock.set(number, [
      { plant: "P100", name: "Detroit", free: (seed % 40) + 3, reserved: seed % 5, leadDays: 4 },
      { plant: "P200", name: "Lyon", free: (seed * 7) % 25, reserved: (seed * 3) % 4, leadDays: 9 },
    ]);
  }
  return stock.get(number);
};

// The panel's page. It talks to the PLM only through postMessage: it is sent the object, and asks
// the PLM to call this service's endpoint for it (so it never holds a token or the API key).
const STOCK_PAGE = `<!doctype html><html><head><meta charset="utf-8"><style>
  body { margin: 0; font: 13px/1.4 system-ui, sans-serif; color: #1c2430; background: #fff; }
  body.dark { color: #e7ebf1; background: #1b1f26; }
  table { width: 100%; border-collapse: collapse; }
  th, td { text-align: left; padding: 7px 14px; border-bottom: 1px solid #e6e8ec; }
  body.dark th, body.dark td { border-color: #323844; }
  th { font-weight: 600; color: #5b6573; font-size: 12px; }
  td.n { text-align: right; font-variant-numeric: tabular-nums; }
  button { font: inherit; padding: 2px 10px; border: 1px solid #c9ced6; border-radius: 4px; background: #fff; cursor: pointer; }
  .foot { padding: 8px 14px; color: #5b6573; font-size: 12px; }
</style></head><body><div id="app" class="foot">Loading…</div><script>
  var object, who, calls = 0, pending = {};
  function ask(path, method, body) {
    return new Promise(function (resolve) {
      var id = ++calls; pending[id] = resolve;
      parent.postMessage({ type: "plm:fetch", id: id, path: path, method: method || "GET", body: body }, "*");
    });
  }
  function render(r) {
    var app = document.getElementById("app");
    if (r.status !== 200) { app.className = "foot"; app.textContent = (r.body && r.body.message) || "The ERP didn't answer"; return size(); }
    var rows = r.body.plants.map(function (p) {
      return "<tr><td>" + p.plant + " " + p.name + "</td><td class=n>" + p.free + "</td><td class=n>" + p.reserved +
        "</td><td class=n>" + p.leadDays + " d</td><td><button data-plant=" + p.plant + ">Reserve 1</button></td></tr>";
    }).join("");
    app.className = "";
    app.innerHTML = "<table><thead><tr><th>Plant</th><th style='text-align:right'>Free</th><th style='text-align:right'>Reserved</th>" +
      "<th style='text-align:right'>Lead time</th><th></th></tr></thead><tbody>" + rows + "</tbody></table>" +
      "<div class=foot>Live from the ERP for " + object.number + ", for " + (who || r.body.user) + "</div>";
    app.querySelectorAll("button").forEach(function (b) {
      b.onclick = function () { ask("stock/" + object.number + "/reserve", "POST", { plant: b.dataset.plant }).then(render); };
    });
    size();
  }
  function size() { parent.postMessage({ type: "plm:resize", height: document.body.scrollHeight + 2 }, "*"); }
  window.addEventListener("message", function (e) {
    if (e.source !== parent) return;
    var m = e.data || {};
    if (m.type === "plm:context") {
      object = m.object;
      who = m.user && m.user.name;
      if (m.theme === "dark") document.body.className = "dark";
      ask("stock/" + object.number).then(render);
    }
    if (m.type === "plm:response" && pending[m.id]) { pending[m.id](m); delete pending[m.id]; }
  });
  parent.postMessage({ type: "plm:ready" }, "*");
</script></body></html>`;

function verify(req, body) {
  const timestamp = req.headers["x-plm-timestamp"];
  const signature = String(req.headers["x-plm-signature"] ?? "");
  const expected = "sha256=" + createHmac("sha256", secret).update(`${timestamp}.${body}`).digest("hex");
  const fresh = Math.abs(Date.now() / 1000 - Number(timestamp)) < 300; // refuse replays
  return fresh && signature.length === expected.length && timingSafeEqual(Buffer.from(signature), Buffer.from(expected));
}

const send = (res, status, payload) => {
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(payload));
};

createServer((req, res) => {
  let body = "";
  req.on("data", (chunk) => (body += chunk));
  req.on("end", () => {
    // The "ERP stock" panel (roadmap 5.15): a page the PLM shows in a sandboxed frame.
    if (req.method === "GET" && req.url.startsWith("/ui/stock")) {
      res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
      return res.end(STOCK_PAGE);
    }
    // Its endpoint, called through the PLM (/api/v1/ext/acme.erp-sync/stock/...): signed, with the user.
    if (req.url.startsWith("/api/stock/")) {
      if (!verify(req, body)) return send(res, 401, { message: "The signature doesn't match" });
      const [number, verb] = req.url.slice("/api/stock/".length).split("?")[0].split("/");
      const plants = stockOf(number);
      if (req.method === "POST" && verb === "reserve") {
        const p = plants.find((x) => x.plant === JSON.parse(body || "{}").plant) ?? plants[0];
        if (p.free < 1) return send(res, 409, { message: `Nothing left at ${p.plant}` });
        p.free -= 1;
        p.reserved += 1;
        console.log(`${new Date().toISOString()} ${req.headers["x-plm-user"]} reserved one ${number} at ${p.plant}`);
      }
      return send(res, 200, { number, plants, user: req.headers["x-plm-user"] });
    }
    if (req.method !== "POST") return send(res, 405, { message: "POST only" });
    if (!verify(req, body)) return send(res, 401, { message: "The signature doesn't match: check the API key in the extension's settings" });
    const call = JSON.parse(body);
    if (req.url.startsWith("/events-down")) return send(res, 503, { message: "The ERP is down for maintenance" });
    if (req.url.startsWith("/events")) {
      // Event webhooks (roadmap 2.6): deduplicate on the delivery id, then process.
      const e = call.event;
      console.log(`${new Date().toISOString()} event ${e.type} ${e.payload.number ?? ""} ${e.payload.from ?? ""}→${e.payload.to ?? ""} (delivery ${req.headers["x-plm-delivery"]})`);
      res.writeHead(204);
      return res.end();
    }
    const part = call.object;
    console.log(`${new Date().toISOString()} ${call.action} ${part.number} by ${call.user.id}`, call.parameters);

    if (req.url.startsWith("/send")) {
      const { plant, quantity, urgent } = call.parameters;
      if (quantity > 100) return send(res, 409, { message: `Plant ${plant} can't make more than 100 at once` });
      orders += 1;
      return send(res, 200, {
        message: `Production order SO-${orders} created: ${quantity} × ${part.number} at ${plant}${urgent ? ", scheduled first" : ""}`,
        level: "info",
        refresh: true,
      });
    }
    if (req.url.startsWith("/report")) {
      // api:read (roadmap 2.7): the call carries a 15-minute token acting as the user, so the
      // service can read more than the object it was sent: here, every iteration of it.
      const rows = (iterations) =>
        iterations.map((i) => `${part.number},"${part.name}",${i.version ?? part.version},${i.state ?? part.state},42.50`).join("\n");
      const report = async () => {
        let iterations = [part];
        let who = call.user.id;
        if (call.api) {
          const auth = { headers: { Authorization: `Bearer ${call.api.token}` } };
          const me = await fetch(`${call.api.baseUrl}/api/v1/me`, auth);
          if (me.ok) who = (await me.json()).user.displayName;
          const r = await fetch(`${call.api.baseUrl}/api/v1/objects/${part.id}/iterations`, auth);
          if (r.ok) iterations = await r.json();
          else console.log(`Reading iterations as ${call.user.id} failed: ${r.status}`);
        }
        const csv = `part,name,version,state,unit cost\n${rows(iterations)}\n`;
        send(res, 200, {
          message: `Cost report for ${part.number}${call.api ? ` (${iterations.length} iterations, read as ${who})` : ""}`,
          download: { filename: `cost-${part.number}.csv`, contentType: "text/csv", content: Buffer.from(csv).toString("base64") },
        });
      };
      return void report().catch((e) => send(res, 502, { message: `The report failed: ${e.message}` }));
    }
    if (req.url.startsWith("/rollup")) {
      // A background action (roadmap 3.11): accept at once (202), then work, reporting progress to
      // job.progressUrl, and post the final answer to job.completeUrl. Both carry the job's token.
      send(res, 202, { message: "Accepted" });
      const { job } = call;
      const headers = { "Content-Type": "application/json", "X-PLM-Job-Token": job.token };
      const plants = call.parameters.plants === "All" ? ["P100", "P200", "P300", "P400"] : [call.parameters.plants];
      const steps = Number(process.env.ROLLUP_STEPS ?? 8);
      void (async () => {
        const lines = ["plant,material,labour,overhead,total"];
        for (let i = 1; i <= steps; i++) {
          await new Promise((r) => setTimeout(r, Number(process.env.ROLLUP_STEP_MS ?? 1500)));
          const plant = plants[(i - 1) % plants.length];
          const r = await fetch(job.progressUrl, { method: "POST", headers, body: JSON.stringify({ done: i, total: steps, message: `Costing ${part.number} at ${plant}` }) });
          if (r.ok && (await r.json()).cancelRequested) return console.log(`Roll-up of ${part.number} cancelled`);
          if (i <= plants.length) lines.push(`${plant},${(30 + i * 1.7).toFixed(2)},${(12 + i).toFixed(2)},4.10,${(46.1 + i * 2.7).toFixed(2)}`);
        }
        const csv = lines.join("\n") + "\n";
        await fetch(job.completeUrl, {
          method: "POST",
          headers,
          body: JSON.stringify({
            message: `Cost roll-up of ${part.number}: ${plants.length} plant${plants.length === 1 ? "" : "s"}, lowest at ${plants[0]}`,
            download: { filename: `rollup-${part.number}.csv`, contentType: "text/csv", content: Buffer.from(csv).toString("base64") },
          }),
        });
        console.log(`Roll-up of ${part.number} done`);
      })().catch((e) => console.log(`Roll-up failed: ${e.message}`));
      return;
    }
    send(res, 404, { message: `No such service: ${req.url}` });
  });
}).listen(port, () => console.log(`Mock ERP listening on http://localhost:${port} (secret: ${secret})`));
