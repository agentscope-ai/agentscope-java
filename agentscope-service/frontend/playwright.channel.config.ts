import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e', testMatch: '**/channel-work.e2e.ts', timeout: 60000,
  use: { baseURL: 'http://127.0.0.1:5188', viewport: { width: 1440, height: 1100 }, screenshot: 'only-on-failure', trace: 'retain-on-failure' },
  webServer: { command: 'npm run preview -- --host 127.0.0.1 --port 5188 --strictPort', url: 'http://127.0.0.1:5188', reuseExistingServer: false },
});
