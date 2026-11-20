import { expect, type Browser, type Page } from "@playwright/test";

export const KEYCLOAK = process.env.PLM_OIDC_ISSUER ?? "http://localhost:8180/realms/plm";
export const API = process.env.PLM_API_URL ?? "http://localhost:8080";

export async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.locator("#username").fill(user);
  await page.locator("#password").fill(user);
  await page.locator("#kc-login").click();
  await expect(page.getByRole("button", { name: "Sign out" })).toBeVisible();
}

export async function asUser(browser: Browser, user: string) {
  const page = await (await browser.newContext()).newPage();
  await signIn(page, user);
  return page;
}

