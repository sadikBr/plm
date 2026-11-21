import { expect, test, type Page } from "@playwright/test";
import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { ADMIN, API, KEYCLOAK, asUser, passwordOf } from "./support";

const STEP = fileURLToPath(new URL("../../backend/modules/cad/src/test/resources/step/as1_pe_203.stp", import.meta.url));
const DRAWING_PDF = fileURLToPath(new URL("./fixtures/as1-drawing.pdf", import.meta.url));
const CAD_DRAWING = "00000000-0000-7000-8000-00000000010c";
const CHANGE_NOTICE = "00000000-0000-7000-8000-000000000106";

/** The text of a PDF's pages, through pdf.js. */
async function pdfText(bytes: Buffer): Promise<string> {
  const pdfjs = await import("pdfjs-dist/legacy/build/pdf.mjs");
  const fonts = fileURLToPath(new URL("../node_modules/pdfjs-dist/standard_fonts/", import.meta.url));
  const doc = await pdfjs.getDocument({ data: new Uint8Array(bytes), standardFontDataUrl: fonts }).promise;
  let text = "";
  for (let i = 1; i <= doc.numPages; i++) {
    const page = await doc.getPage(i);
    text += (await page.getTextContent()).items.map((it) => ("str" in it ? it.str : "")).join(" ") + "\n";
  }
  return text;
}

/**
 * Phase 6 exit: a STEP assembly is imported as CAD documents associated with parts and builds their
 * structure; it is converted on check-in, explored in the browser (structure, measure, section,
 * markups); and on release its drawing is published as a watermarked PDF.
 */
test("a STEP assembly is imported, converted, explored, and its drawing released and published", async ({ browser, request }) => {
  test.setTimeout(300_000);
  const suffix = Date.now().toString(36);
  const token = async (user: string) => {
    const r = await request.post(`${KEYCLOAK}/protocol/openid-connect/token`, {
      form: { grant_type: "password", client_id: "plm-web", username: user, password: passwordOf(user) },
    });
    return ((await r.json()) as { access_token: string }).access_token;
  };
  const headers = { Authorization: `Bearer ${await token(ADMIN)}` };
  const call = async (method: "GET" | "POST" | "PUT" | "DELETE", path: string, data?: unknown) => {
    const r = await request.fetch(`${API}/api/v1${path}`, { method, headers, data });
    expect(r.ok(), `${method} ${path}: ${await r.text()}`).toBeTruthy();
    return r.status() === 204 ? null : r.json();
  };

  // ---- set up by API: a product, the publishing rule for released drawings, watermarked downloads ----
  const site = await call("GET", "/contexts/site");
  const org = await call("POST", "/contexts", { parentId: site.id, kind: "ORGANIZATION", name: `CAD Co ${suffix}` });
  const product = await call("POST", "/contexts", { parentId: org.id, kind: "PRODUCT", name: `Linear guide ${suffix}` });
  await call("PUT", "/publish-settings", { watermarkDownloads: true });
  const rules = (await call("GET", "/publish-rules")) as { id: string; types: string[]; trigger: string; state: string; active: boolean }[];
  const ownRule = rules.some((r) => r.active && r.trigger === "STATE" && r.state === "RELEASED" && r.types.includes("CadDrawing"))
    ? null
    : await call("POST", "/publish-rules", {
        name: `Released drawings ${suffix}`,
        trigger: "STATE",
        state: "RELEASED",
        types: ["CadDrawing"],
        watermark: true,
      });

  try {
    const admin = await asUser(browser, ADMIN);

    // ---- the STEP file is imported: CAD documents, with a part each and the parts' structure ----
    await admin.goto(`/contexts/${product.id}`);
    await admin.getByRole("button", { name: "Import STEP…" }).click();
    const dialog = admin.getByRole("dialog");
    await dialog.locator("#step-file").setInputFiles(STEP);
    await expect(dialog.getByText("9 models, 4 of them assemblies")).toBeVisible();
    await expect(dialog.getByLabel("Make a part for each model, owned by it")).toBeChecked();
    await expect(dialog.getByLabel("Build the assembly parts' structures from their models")).toBeChecked();
    await dialog.getByRole("button", { name: "Import 9 models" }).click();
    await expect(admin.locator(".title-row .num")).toContainText("CAD-");
    const rootId = new URL(admin.url()).pathname.split("/").pop()!;
    const root = await call("GET", `/objects/${rootId}`);
    const owner = ((await call("GET", `/objects/${rootId}/cad/associations`)) as { kind: string; part: { id: string } }[]).find(
      (a) => a.kind === "OWNER",
    )!;
    await admin.goto(`/objects/${owner.part.id}?tab=structure`);
    await expect(admin.getByRole("table", { name: /structure/i }).first()).toContainText("PLATE");
    await expect(admin.getByRole("table", { name: /structure/i }).first()).toContainText("ROD");

    // ---- converted on check-in, explored in the browser ----
    await admin.goto(`/objects/${rootId}?tab=view3d`);
    const tree = admin.getByLabel("Model tree");
    await expect(tree.getByText("ROD ASM")).toBeVisible({ timeout: 120_000 });
    await expect(tree.getByText("PLATE")).toBeVisible();
    await tree.locator("li", { hasText: "ROD ASM" }).getByRole("button", { name: "Hide" }).click();
    await expect(tree.locator("li", { hasText: "ROD ASM" }).getByRole("button", { name: "Show" })).toBeVisible();
    const canvas = admin.locator(".viewer-canvas canvas");
    const box = (await canvas.boundingBox())!;
    const clickAt = (page: Page, fx: number, fy: number) => page.mouse.click(box.x + box.width * fx, box.y + box.height * fy);

    await admin.getByRole("button", { name: "Measure" }).click();
    await clickAt(admin, 0.42, 0.45);
    await clickAt(admin, 0.55, 0.7);
    await expect(admin.locator(".viewer-panel dl")).toContainText("Distance");
    await expect(admin.locator(".viewer-panel dl")).toContainText("mm");

    await admin.getByRole("button", { name: "Section" }).click();
    await admin.getByRole("radio", { name: "Along Z" }).click();
    await admin.getByRole("slider", { name: "Position" }).fill("0.4");
    await expect(admin.locator(".viewer-panel")).toContainText("Z =");

    await admin.getByRole("button", { name: "Markups" }).click();
    await admin.getByRole("button", { name: "New markup" }).click();
    await admin.locator(".viewer-panel").getByLabel("Title").fill(`Clearance ${suffix}`);
    await admin.getByRole("button", { name: "Add a pin" }).click();
    await clickAt(admin, 0.45, 0.5);
    await admin.getByLabel("Pin 1").fill("Check the nut height");
    await admin.getByRole("button", { name: "Save markup" }).click();
    await expect(admin.locator(".markup-list")).toContainText(`Clearance ${suffix}`);
    await expect(admin.locator(".viewer-overlay .pin")).toHaveCount(1);
    const markups = (await call("GET", `/objects/${rootId}/markups`)) as { title: string; shapes: unknown[]; topicId: string }[];
    expect(markups.find((m) => m.title === `Clearance ${suffix}`)?.shapes).toHaveLength(1);

    // ---- the assembly's drawing: a CAD drawing of it, with its PDF ----
    const drawing = await call("POST", "/objects", { typeId: CAD_DRAWING, contextId: product.id, name: `AS1 assembly drawing ${suffix}` });
    await call("POST", `/objects/${drawing.id}/checkout`);
    const pdf = readFileSync(DRAWING_PDF);
    const ticket = await call("POST", "/vault/uploads", { fileName: "as1-GA.pdf", contentType: "application/pdf", size: pdf.length });
    const put = await request.put(ticket.uploadUrl.startsWith("/") ? `${API}${ticket.uploadUrl}` : ticket.uploadUrl, {
      data: pdf,
      headers: { "Content-Type": "application/pdf" },
    });
    expect(put.ok()).toBeTruthy();
    await call("POST", `/objects/${drawing.id}/content`, { uploadId: ticket.uploadId, role: "PRIMARY" });
    await call("POST", `/objects/${drawing.id}/cad/dependencies`, { childId: rootId, kind: "DRAWING" });
    await call("POST", `/objects/${drawing.id}/checkin`, { comment: "First issue" });

    // ---- released through a change notice ----
    const cn = await call("POST", "/objects", { typeId: CHANGE_NOTICE, contextId: product.id, name: `Release AS1 drawing ${suffix}` });
    await call("POST", `/changes/${cn.id}/links`, { targetId: drawing.id, role: "RESULTING" });
    await admin.goto(`/objects/${cn.id}`);
    for (const to of ["Under Review", "Approved", "Implementation", "Resolved"]) {
      await expect(admin.getByRole("dialog")).toHaveCount(0);
      await admin.locator(".toolbar").getByRole("button", { name: "Set state" }).click();
      await admin.getByRole("menuitem", { name: to, exact: true }).click();
      await admin.getByRole("dialog").getByRole("button", { name: "Set state" }).click();
      await expect(admin.locator(".title-row .state")).toHaveText(to);
    }
    await admin.goto(`/objects/${drawing.id}`);
    await expect(admin.locator(".title-row .state")).toHaveText("Released");

    // ---- published as a stamped PDF; downloads carry who and when ----
    const publications = admin.getByRole("table", { name: "Publications" });
    await expect(async () => {
      await admin.reload();
      await expect(publications).toContainText("its PDF, stamped", { timeout: 2_000 });
    }).toPass({ timeout: 60_000 });
    const [publication] = (await call("GET", `/objects/${drawing.id}/publications`)) as { id: string; status: string }[];
    const published = await request.get(`${API}/api/v1/publications/${publication!.id}/pdf`, { headers });
    expect(published.ok()).toBeTruthy();
    const text = await pdfText(await published.body());
    expect(text).toContain(drawing.number);
    expect(text).toContain("Released");
    expect(text).toMatch(/Downloaded by Site Administrator/);

    // ---- and the drawing is in the 2D view ----
    await admin.goto(`/objects/${drawing.id}?tab=view2d`);
    await expect(admin.getByText("Page 1 of 1")).toBeVisible({ timeout: 30_000 });
    expect(root.number).toMatch(/^CAD-/);
  } finally {
    if (ownRule) await request.delete(`${API}/api/v1/publish-rules/${ownRule.id}`, { headers });
  }
});
