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

import { test, expect } from '@playwright/test';

for (const entry of ['/', '/login']) {
  test(`local development login settles from ${entry} without reissuing sessions`, async ({ page }) => {
    let sessions = 0;
    let scopes = 0;
    const errors: string[] = [];
    const account = { userId: 'local-developer', username: 'local-developer', roles: ['user', 'admin'] };
    page.on('pageerror', error => errors.push(error.message));
    if (entry === '/login') {
      await page.addInitScript(() => localStorage.setItem('managed_agents_token', 'previous-session'));
    }
    await page.clock.install();
    await page.route('**/api/**', async route => {
      const path = new URL(route.request().url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      const json = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
      if (path === '/api/auth/dev-session') {
        sessions += 1;
        // The server issues a fresh JWT ID on every initialization request.
        return json({ ...account, token: `session-${sessions}` });
      }
      if (path === '/api/auth/me') return json({ ...account, isAdmin: true });
      if (path === '/api/v1/me/scope') {
        scopes += 1;
        return json({ tenant: 'default', namespace: 'default', mode: 'single', selectorVisible: false, namespaces: [{ tenant: 'default', name: 'default', roles: ['admin'] }] });
      }
      return json({ items: [], summary: { attentionTotal: 0 }, preferences: {} });
    });

    await page.goto(entry);
    await expect(page).toHaveURL(/\/work\/overview$/, { timeout: 5000 });
    await expect(page.getByRole('heading', { name: 'Overview', exact: true })).toBeVisible();
    expect(sessions).toBeGreaterThan(0);
    // StrictMode may initialize each mounted login component twice.
    expect(sessions).toBeLessThanOrEqual(entry === '/login' ? 4 : 2);
    const initialized = sessions;
    const resolved = scopes;

    await page.clock.runFor(31_000);
    await expect.poll(() => scopes).toBeGreaterThan(resolved);
    expect(sessions).toBe(initialized);
    await page.getByRole('link', { name: 'Agents', exact: true }).first().click();
    await expect(page).toHaveURL(/\/agent-center\/agents$/);
    expect(sessions).toBe(initialized);
    expect(errors).toEqual([]);
  });
}

test('production sign-in remains accessible without resolving an authenticated scope', async ({ page }) => {
  let scopes = 0;
  await page.addInitScript(() => localStorage.setItem('managed_agents_token', 'expired-production-session'));
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    if (path === '/api/v1/me/scope') scopes += 1;
    return route.fulfill({ status: path === '/api/auth/me' ? 401 : 404 });
  });
  await page.goto('/');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('button', { name: 'Sign in', exact: true })).toBeVisible();
  expect(scopes).toBe(0);
});
