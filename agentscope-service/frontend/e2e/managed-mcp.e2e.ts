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

// API fixtures keep this check independent of live accounts, credentials and MCP providers.
test('workspace MCP editor persists scoped policies and removes their server binding', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  let definition: { tools: unknown[]; mcpServers: unknown[] } = { tools: [], mcpServers: [] };
  const token = `test.${Buffer.from(JSON.stringify({ username: 'alice', roles: ['admin'] })).toString('base64url')}.test`;
  await page.addInitScript(token => localStorage.setItem('claw_token', token), token);
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    const json = (value: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(value) });
    if (path === '/api/auth/me') return json({ username: 'alice', roles: ['admin'], isAdmin: true });
    if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: 'default', mode: 'single', selectorVisible: false, namespaces: [{ tenant: 'default', name: 'default', roles: ['admin', 'developer', 'operator', 'member'] }] });
    if (path === '/api/workspaces/review/revisions' || path === '/api/workspaces/review/agents') return json({ items: [] });
    if (path === '/api/workspaces/review') return json({ id: 'review', name: 'Managed MCP review', version: 1 });
    if (path === '/api/workspaces/review/tools') {
      if (route.request().method() === 'PUT') definition = route.request().postDataJSON();
      return json(definition);
    }
    if (path.endsWith('/file')) return json({ content: '', path: 'AGENTS.md' });
    return json([]);
  });
  await page.goto('/managed/workspaces/review?tab=tools');
  await page.getByRole('button', { name: 'Add connection', exact: true }).click();
  await page.getByLabel('Name', { exact: true }).fill('crm');
  await page.getByLabel('Endpoint URL').fill('https://crm.example/mcp');
  await page.getByRole('button', { name: 'Add tool override' }).click();
  await page.getByRole('textbox', { name: 'Tool 1 name' }).fill('search');
  await page.getByRole('combobox', { name: 'Tool 1 permission' }).selectOption('always_allow');
  await page.screenshot({ path: '/tmp/managed-mcp-editor.png', fullPage: true });
  await page.getByRole('button', { name: 'Save connection', exact: true }).click();
  await expect(page.getByText('crm', { exact: true })).toBeVisible();
  expect(definition.mcpServers).toEqual([expect.objectContaining({ name: 'crm', transport: 'http', required: true })]);
  expect(definition.tools).toEqual([{
    type: 'mcp_toolset', mcpServerName: 'crm',
    defaultConfig: { enabled: false, permissionPolicy: { type: 'always_ask' } },
    configs: [{ name: 'search', enabled: true, permissionPolicy: { type: 'always_allow' } }],
  }]);
  await page.reload();
  await expect(page.getByText('crm', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Remove', exact: true }).click();
  await expect(page.getByText('crm', { exact: true })).toHaveCount(0);
  expect(definition).toEqual({ tools: [], mcpServers: [] });
  await page.setViewportSize({ width: 620, height: 900 });
  await page.getByRole('button', { name: 'Add connection', exact: true }).click();
  await page.getByLabel('Name', { exact: true }).fill('local-docs');
  await page.getByRole('combobox', { name: /^Transport/ }).selectOption('stdio');
  await expect(page.getByLabel('Endpoint URL')).toHaveCount(0);
  await page.getByLabel('Command', { exact: true }).fill('node');
  await page.getByLabel('Arguments, one per line').fill('server.js');
  await page.getByRole('button', { name: 'Save connection', exact: true }).click();
  expect(definition.mcpServers).toEqual([expect.objectContaining({ name: 'local-docs', transport: 'stdio', command: 'node', args: ['server.js'] })]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(errors).toEqual([]);
});
