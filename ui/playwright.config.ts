import { defineConfig } from "@playwright/test";
import { PORT, STATE_FILE } from "./e2e/dispatch";

// End-to-end tests of the bundled pages against a real `dispatch ui` (the -Pui jar) and a config in a temporary folder.
// The tests change that one config, so they run one at a time. Build first: (cd ui && npm run build) && ./mvnw -Pui package
export default defineConfig({
  testDir: "e2e",
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 30_000,
  reporter: "list",
  globalSetup: "./e2e/global-setup.ts",
  use: { baseURL: `http://127.0.0.1:${PORT}`, storageState: STATE_FILE, browserName: "chromium", trace: "retain-on-failure" },
});
