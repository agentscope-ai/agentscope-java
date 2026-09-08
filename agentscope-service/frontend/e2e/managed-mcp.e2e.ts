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
    if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: 'default', mode: 'single', selectorVisible: false });
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
  expect(errors).toEqual([]);
});
