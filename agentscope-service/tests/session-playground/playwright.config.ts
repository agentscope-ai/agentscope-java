// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e', testMatch: '**/*.e2e.ts', timeout: 30000,
  use: { baseURL: 'http://127.0.0.1:18083', viewport: { width: 1440, height: 1100 }, screenshot: 'only-on-failure' },
  webServer: { command: 'npm run dev -- --port 18083', url: 'http://127.0.0.1:18083', reuseExistingServer: !process.env.CI },
});
