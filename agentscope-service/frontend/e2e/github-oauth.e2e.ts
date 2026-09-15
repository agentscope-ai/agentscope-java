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

for (const scenario of ['ready', 'mcp-denied', 'unconfigured', 'attach-failed'] as const) {
  test(`GitHub account connection: ${scenario}`, async ({ page, context }) => {
    const errors: string[] = []; page.on('pageerror', e => errors.push(e.message));
    const token = `test.${Buffer.from(JSON.stringify({ sub: 'alice', username: 'alice', roles: ['user'] })).toString('base64url')}.test`;
    await page.addInitScript(t => localStorage.setItem('claw_token', t), token);
    const server = { name: 'github', transport: 'streamable-http', url: 'https://api.githubcopilot.com/mcp/' };
    let definition = { id: 'agent', name: 'GitHub assistant', version: 1, mcpServers: [server], tools: [], defaultVaultIds: [] as string[] };
    const catalog = { id: 'agent', agentKey: 'github-assistant', displayName: 'GitHub assistant', ownerId: 'alice', tenant: 'default', namespace: 'personal', status: 'active' };
    let connected = false, authorized = false, created = false, verified = false, attachFailed = scenario === 'attach-failed';
    const connection = () => ({ id: 'connection', vaultId: 'vault', provider: 'github', serverName: 'github', endpoint: server.url, settings: {}, connected, callbackUrl: 'https://console.example/api/oauth/mcp/callback/github' });
    await context.route('**/github-fixture/**', async route => {
      if (route.request().url().endsWith('/consent')) { authorized = true; return route.fulfill({ contentType: 'text/html', body: '<h1>Return to AgentScope</h1>' }); }
      return route.fulfill({ contentType: 'text/html', body: '<h1>Authorize GitHub</h1><a href="/github-fixture/consent">Authorize AgentScope</a>' });
    });
    await page.route('**/api/**', async route => {
      const req = route.request(), path = new URL(req.url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      const json = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
      if (path === '/api/auth/me') return json({ userId: 'alice', username: 'alice', roles: ['user'] });
      if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: 'personal', mode: 'multi', selectorVisible: true, namespaces: [{ tenant: 'default', name: 'personal', kind: 'personal', roles: ['admin', 'developer'] }] });
      if (path === '/api/v1/agents/agent') return json({ agent: catalog });
      if (path === '/api/v1/agents/agent/definition') {
        if (req.method() === 'PATCH') {
          if (attachFailed) { attachFailed = false; return route.fulfill({ status: 409, json: { error: 'Agent definition changed' } }); }
          definition = { ...definition, ...req.postDataJSON(), version: definition.version + 1 };
        }
        return json({ agent: catalog, definition });
      }
      if (path === '/api/v1/agents/agent/bindings') return json({ items: [{ id: 'binding', kind: 'managed', enabled: true }] });
      if (path === '/api/v1/agents/agent/overview') return json({ readiness: { state: 'ready', reasons: [] }, runtime: {}, activity: {}, inventory: {}, summary: {} });
      if (path === '/api/oauth/providers/github') return json({ configured: scenario !== 'unconfigured', scope: 'read:user' });
      if (path === '/api/vaults') return json([{ id: 'vault', displayName: 'Personal GitHub', createdAt: 1, updatedAt: 1 }]);
      if (path === '/api/vaults/vault/oauth-connections') {
        if (req.method() === 'POST') {
          expect(req.postDataJSON()).toEqual({ provider: 'github', serverName: 'github', endpoint: server.url }); created = true; return json(connection());
        }
        return json(created ? [connection()] : []);
      }
      if (path.endsWith('/authorize')) return json({ flowId: 'flow', authorizationUrl: 'http://127.0.0.1:5177/github-fixture/login', expiresAt: Date.now() + 600000 });
      if (path.endsWith('/flows/flow')) return json({ status: authorized ? 'authorized' : 'pending', expiresAt: Date.now() + 600000 });
      if (path.endsWith('/complete')) { connected = true; return json({ vaultId: 'vault' }); }
      if (path.endsWith('/verify')) { verified = true; return json({ login: 'octocat', userId: 42, status: scenario === 'mcp-denied' ? 'authorized' : 'ready', toolCount: scenario === 'mcp-denied' ? 0 : 12, errorCode: scenario === 'mcp-denied' ? 'mcp_access_denied' : '', checkedAt: Date.now() }); }
      if (path.endsWith('/disconnect')) { connected = false; return json({ disconnected: true }); }
      if (path === '/api/v1/inbox/summary') return json({ summary: { unread: 0, attentionTotal: 0, pendingApprovals: 0 } });
      return json([]);
    });
    await page.goto('/agent-center/agents/agent/definition/tools?tenant=default&namespace=personal');
    await page.getByRole('button', { name: 'Connect GitHub', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('combobox', { name: 'Vault', exact: true }).selectOption('vault');
    await expect(dialog.getByLabel('Client ID', { exact: true })).toHaveCount(0);
    await expect(dialog.getByLabel('Authorization endpoint')).toHaveCount(0);
    if (scenario === 'unconfigured') {
      await expect(dialog.getByText(/administrator must configure/)).toBeVisible();
      await expect(dialog.getByRole('button', { name: 'Connect GitHub account' })).toBeDisabled();
      expect(created).toBe(false); return;
    }
    const popupPromise = page.waitForEvent('popup');
    await dialog.getByRole('button', { name: 'Connect GitHub account' }).click();
    const popup = await popupPromise;
    await popup.getByRole('link', { name: 'Authorize AgentScope' }).click();
    await expect(dialog.getByText('@octocat')).toBeVisible();
    expect(verified).toBe(true);
    if (scenario === 'attach-failed') {
      await expect(dialog.getByRole('alert')).toContainText('agent could not be updated');
      await dialog.getByRole('button', { name: 'Retry adding Vault to agent' }).click();
      await expect(dialog.getByText('Vault added to this agent. Start a new session to use it.')).toBeVisible();
    }
    expect(definition.defaultVaultIds).toEqual(['vault']);
    expect(definition.mcpServers).toEqual([server]);
    if (scenario === 'mcp-denied') await expect(dialog.getByText(/mcp_access_denied/)).toBeVisible();
    else await expect(dialog.getByText('MCP connection verified · 12 tools discovered')).toBeVisible();
    await page.screenshot({ path: `/tmp/github-oauth-${scenario}.png`, fullPage: true });
    await dialog.getByRole('button', { name: 'Disconnect from Vault' }).click();
    await expect(dialog.getByText('@octocat')).toHaveCount(0);
    expect(errors).toEqual([]);
  });
}

test('Administrator configures GitHub once with a stable callback and write-only secret', async ({ page }) => {
  const token = `test.${Buffer.from(JSON.stringify({ sub: 'admin', username: 'admin', roles: ['admin'] })).toString('base64url')}.test`;
  await page.addInitScript(t => localStorage.setItem('claw_token', t), token);
  let saved: Record<string, unknown> | undefined;
  await page.route('**/api/**', route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    const json = (body: unknown) => route.fulfill({ json: body });
    if (path === '/api/auth/me') return json({ userId: 'admin', username: 'admin', roles: ['admin'] });
    if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: 'personal', namespaces: [{ tenant: 'default', name: 'personal', roles: ['admin'] }] });
    if (path === '/api/admin/integrations/github') {
      if (req.method() === 'PUT') saved = req.postDataJSON();
      return json({ enabled: Boolean(saved), clientId: saved?.clientId ?? '', hasClientSecret: Boolean(saved), scope: saved?.scope ?? '', revision: saved ? 1 : 0, callbackUrl: 'https://console.example/api/oauth/mcp/callback/github' });
    }
    return json([]);
  });
  await page.goto('/settings/integrations');
  await expect(page.getByLabel('Callback URL')).toHaveValue('https://console.example/api/oauth/mcp/callback/github');
  await page.getByLabel('Enable GitHub account connections').check();
  await page.getByLabel('Client ID', { exact: true }).fill('github-client');
  await page.getByLabel('Client secret', { exact: true }).fill('fixture-secret');
  await page.getByLabel('OAuth scopes').fill('read:user');
  await page.getByRole('button', { name: 'Save GitHub application' }).click();
  await expect(page.getByRole('status')).toContainText('GitHub application saved');
  expect(saved).toEqual({ enabled: true, clientId: 'github-client', clientSecret: 'fixture-secret', scope: 'read:user', revision: 0 });
  await expect(page.getByLabel('Client secret', { exact: true })).toHaveValue('');
  await page.screenshot({ path: '/tmp/github-oauth-admin.png', fullPage: true });
});
