import { defineConfig } from "@playwright/test";

export default defineConfig({
  testDir: "./e2e",
  testMatch: "**/*.e2e.ts",
  fullyParallel: true,
  timeout: 30000,
  use: { baseURL: "http://127.0.0.1:5177", viewport: { width: 1440, height: 1000 }, screenshot: "only-on-failure", trace: "retain-on-failure" },
  webServer: { command: "npm run dev -- --host 127.0.0.1 --port 5177 --strictPort", url: "http://127.0.0.1:5177", reuseExistingServer: !process.env.CI },
});
