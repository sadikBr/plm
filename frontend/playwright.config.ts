import { defineConfig, devices } from "@playwright/test";

/**
 * End-to-end tests against the full local stack. Start it first:
 *   make up && make run      (backend on :8080, Keycloak on :8180)
 *   npm run dev              (UI on :5173)
 * then: npm run e2e
 */
export default defineConfig({
  testDir: "./e2e",
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  retries: 0,
  reporter: [["list"]],
  use: {
    baseURL: process.env.PLM_UI_URL ?? "http://localhost:5173",
    trace: "off",
    screenshot: "only-on-failure",
  },
  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],
});
