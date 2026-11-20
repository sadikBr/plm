// A stand-in ERP for trying the erp-sync sample's webhooks (version 2.0). No dependencies:
//   ERP_SECRET=demo-key PORT=8099 node mock-erp.mjs
// It checks every request's signature the way a real service should, then answers with what the
// PLM shows: a message, a refresh, or a file to download.
import { createServer } from "node:http";
import { createHmac, timingSafeEqual } from "node:crypto";

const secret = process.env.ERP_SECRET ?? "demo-key";
const port = Number(process.env.PORT ?? 8099);
let orders = 4710;

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
    if (req.method !== "POST") return send(res, 405, { message: "POST only" });
    if (!verify(req, body)) return send(res, 401, { message: "The signature doesn't match: check the API key in the extension's settings" });
    const call = JSON.parse(body);
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
      const csv = `part,name,version,state,unit cost\n${part.number},"${part.name}",${part.version},${part.state},42.50\n`;
      return send(res, 200, {
        message: `Cost report for ${part.number}`,
        download: { filename: `cost-${part.number}.csv`, contentType: "text/csv", content: Buffer.from(csv).toString("base64") },
      });
    }
    send(res, 404, { message: `No such service: ${req.url}` });
  });
}).listen(port, () => console.log(`Mock ERP listening on http://localhost:${port} (secret: ${secret})`));
