import { defineConfig } from '@playwright/test';

// Exercise the embedded production console rather than the Vite module server.
export default defineConfig({
  testDir: './e2e', testMatch: '**/permissions.e2e.ts', timeout: 90000,
  use: { baseURL: 'http://127.0.0.1:5186', viewport: { width: 1440, height: 1000 }, screenshot: 'only-on-failure', trace: 'retain-on-failure' },
  webServer: { command: 'npm run preview -- --host 127.0.0.1 --port 5186 --strictPort', url: 'http://127.0.0.1:5186', reuseExistingServer: false },
});
