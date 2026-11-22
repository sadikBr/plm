import { expect, type Browser, type Page } from "@playwright/test";

export const KEYCLOAK = process.env.PLM_OIDC_ISSUER ?? "http://localhost:8180/realms/plm";
export const API = process.env.PLM_API_URL ?? "http://localhost:8080";

/** The built-in site administrator, made with the installation. */
export const ADMIN = "plmadmin";

/**
 * Dev passwords are the user names. The built-in administrator chooses its own at the first
 * sign-in: set PLM_ADMIN_PASSWORD to it (the tests choose "plmadmin" if it's still unset).
 */
export function passwordOf(user: string) {
  return user === ADMIN ? (process.env.PLM_ADMIN_PASSWORD ?? ADMIN) : user;
}

export async function signIn(page: Page, user: string) {
  await page.goto("/");
  await page.getByRole("button", { name: "Sign in" }).click();
  await page.locator("#username").fill(user);
  await page.locator("#password").fill(passwordOf(user));
  await page.locator("#kc-login").click();
  const signedIn = page.getByTestId("current-user");
  // The built-in administrator's first sign-in asks for a new password.
  const newPassword = page.locator("#password-new");
  await expect(signedIn.or(newPassword)).toBeVisible();
  if (await newPassword.isVisible()) {
    await newPassword.fill(passwordOf(user));
    await page.locator("#password-confirm").fill(passwordOf(user));
    await page.locator("input[type=submit], button[type=submit]").first().click();
  }
  await expect(signedIn).toBeVisible();
}

export async function asUser(browser: Browser, user: string) {
  const page = await (await browser.newContext()).newPage();
  await signIn(page, user);
  return page;
}

/** The site's page (where organizations are made); signing in lands on the home page. */
export async function openSite(page: Page) {
  await page.getByRole("navigation", { name: "Navigator" }).getByRole("link", { name: "Site", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Site" })).toBeVisible();
}

/**
 * Opens a page's tab by name: in the tab strip, or under "More" when the strip is too narrow for it.
 */
export async function openTab(page: Page, name: string) {
  const sections = page.getByRole("navigation", { name: "Sections" });
  const tab = sections.getByRole("link", { name, exact: true });
  const more = sections.getByRole("button", { name: /^More/ });
  // Wait until the row is laid out: the tab is either in it or behind "More".
  await expect(tab.or(more).first()).toBeVisible();
  if (!(await tab.isVisible())) await more.click();
  await tab.click();
}
