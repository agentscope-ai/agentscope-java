/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import { defineConfig } from '@playwright/test';
export default defineConfig({
  testDir: './e2e', testMatch: '**/workspace-definition.e2e.ts', timeout: 45000,
  use: { baseURL: 'http://127.0.0.1:5190', viewport: { width: 1440, height: 1100 }, screenshot: 'only-on-failure', trace: 'retain-on-failure' },
  webServer: { command: 'npm run preview -- --host 127.0.0.1 --port 5190 --strictPort', url: 'http://127.0.0.1:5190', reuseExistingServer: false },
});
