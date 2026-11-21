import { chromium, type FullConfig } from "@playwright/test";
import { ADMIN, signIn } from "./support";

/**
 * The built-in administrator must choose a password at its first sign-in before it can get a
 * token any other way: sign it in through the browser once, which does that if it's needed.
 */
export default async function globalSetup(config: FullConfig) {
  const browser = await chromium.launch();
  const page = await browser.newPage({ baseURL: config.projects[0].use.baseURL });
  await signIn(page, ADMIN);
  await browser.close();
}
