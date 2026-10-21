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
import type { McpOAuthConnection } from '../src/api/mcpOAuth';

for (const scenario of ['agent', 'cancel', 'denied'] as const) {
  test(`MCP OAuth ${scenario}: provider window and Vault integration`, async ({ page, context }) => {
    const errors: string[] = [];
    page.on('pageerror', e => errors.push(e.message));
    const token = `test.${Buffer.from(JSON.stringify({ sub: 'alice', username: 'alice', roles: ['admin'] })).toString('base64url')}.test`;
    await page.addInitScript(t => localStorage.setItem('claw_token', t), token);
    const server = { name: 'crm', transport: 'http', url: 'https://crm.example/mcp' };
    let definition = { id: 'agent', name: 'CRM assistant', workspaceId: 'review', version: 1, mcpServers: [server], tools: [], defaultVaultIds: ['existing-vault'] };
    const catalog = { id: 'agent', agentKey: 'crm-assistant', displayName: 'CRM assistant', ownerId: 'alice', tenant: 'default', namespace: 'personal', status: 'active' };
    let connection: McpOAuthConnection | undefined;
    let status = 'pending';
    let completed = 0, cancelled = 0;
    let savedSecret = '';
    await context.route('**/oauth-fixture/**', async route => {
      const path = new URL(route.request().url()).pathname;
      if (path.endsWith('/consent')) {
        status = scenario === 'denied' ? 'failed' : 'authorized';
        return route.fulfill({ contentType: 'text/html', body: '<h1>Return to AgentScope</h1>' });
      }
      return route.fulfill({ contentType: 'text/html', body: '<h1>Example CRM authorization</h1><a href="/oauth-fixture/consent">Authorize CRM access</a>' });
    });
    await page.route('**/api/**', async route => {
      const req = route.request(), path = new URL(req.url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      const json = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
      if (path === '/api/auth/me') return json({ userId: 'alice', username: 'alice', roles: ['admin'] });
      if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: 'personal', mode: 'multi', selectorVisible: true, namespaces: [{ tenant: 'default', name: 'personal', kind: 'personal', roles: ['admin', 'developer'] }] });
      if (path === '/api/v1/agents/agent') return json({ agent: catalog });
      if (path === '/api/v1/agents/agent/definition') {
        if (req.method() === 'PATCH') { definition = { ...definition, ...req.postDataJSON(), version: definition.version + 1 }; }
        return json({ agent: catalog, definition });
      }
      if (path === '/api/v1/agents/agent/bindings') return json({ items: [{ id: 'binding', kind: 'managed', enabled: true }] });
      if (path === '/api/v1/agents/agent/overview') return json({ readiness: { state: 'ready', reasons: [] }, runtime: {}, activity: {}, inventory: {}, summary: {} });
      if (path === '/api/workspaces/review/revisions' || path === '/api/workspaces/review/agents') return json({ items: [] });
    if (path === '/api/workspaces/review') return json({ id: 'review', name: 'CRM workspace', version: 1 });
      if (path === '/api/workspaces/review/tools') return json({ mcpServers: [server], tools: [] });
      if (path.endsWith('/file')) return json({ content: '', path: 'AGENTS.md' });
      if (path === '/api/vaults') return json([{ id: 'vault', displayName: 'CRM team', createdAt: 1, updatedAt: 1 }]);
      if (path === '/api/vaults/vault/oauth-connections') {
        if (req.method() === 'POST') {
          const { serverName, endpoint, clientSecret, ...settings } = req.postDataJSON();
          savedSecret = clientSecret;
          connection = { id: 'connection', vaultId: 'vault', serverName, endpoint, settings, connected: false, hasClientSecret: true, callbackUrl: 'https://console.example/api/oauth/mcp/callback/connection' };
          return json(connection);
        }
        return json(connection ? [connection] : []);
      }
      if (path.endsWith('/authorize')) return json({ flowId: 'flow', authorizationUrl: 'http://127.0.0.1:5177/oauth-fixture/login', expiresAt: Date.now() + 600000 });
      if (path.endsWith('/flows/flow')) return json({ status, errorCode: status === 'failed' ? 'authorization_denied' : '', expiresAt: Date.now() + 600000 });
      if (path.endsWith('/complete')) { completed++; connection!.connected = true; status = 'completed'; return json({ vaultId: 'vault' }); }
      if (path.endsWith('/cancel')) { cancelled++; status = 'cancelled'; return route.fulfill({ status: 204 }); }
      if (path.endsWith('/disconnect')) { connection!.connected = false; return json({ disconnected: true }); }
      if (path === '/api/v1/inbox/summary') return json({ summary: { unread: 0, attentionTotal: 0, pendingApprovals: 0 } });
      return json([]);
    });
    await page.goto(scenario === 'agent' ? '/agent-center/agents/agent/definition/tools?tenant=default&namespace=personal' : '/managed/workspaces/review?tab=tools&tenant=default&namespace=personal');
    await page.getByRole('button', { name: 'Connect account', exact: true }).click();
    const dialog = page.getByRole('dialog');
    await dialog.getByRole('combobox', { name: 'Vault', exact: true }).selectOption('vault');
    await dialog.getByLabel('Authorization endpoint').fill('https://auth.example/authorize');
    await dialog.getByLabel('Token endpoint').fill('https://auth.example/token');
    await dialog.getByLabel('Client ID', { exact: true }).fill('crm-app');
    await dialog.getByLabel('Client secret', { exact: true }).fill('fake-client-secret');
    await dialog.getByLabel('Scopes', { exact: true }).fill('crm.read offline_access');
    await dialog.getByLabel('Resource (optional)').fill(server.url);
    await dialog.getByRole('button', { name: 'Save application', exact: true }).click();
    await expect(dialog.getByLabel('Callback URL')).toHaveValue('https://console.example/api/oauth/mcp/callback/connection');
    expect(savedSecret).toBe('fake-client-secret');
    expect(JSON.stringify(definition)).not.toContain('fake-client-secret');
    const popupPromise = page.waitForEvent('popup');
    await dialog.getByRole('button', { name: 'Connect account', exact: true }).click();
    const popup = await popupPromise;
    await expect(popup.getByRole('heading', { name: 'Example CRM authorization' })).toBeVisible();
    if (scenario === 'cancel') {
      await dialog.getByRole('button', { name: 'Cancel authorization' }).click();
      await expect(dialog.getByText('Authorization cancelled.', { exact: true })).toBeVisible();
      expect(cancelled).toBe(1); expect(completed).toBe(0);
    } else {
      await popup.getByRole('link', { name: 'Authorize CRM access' }).click();
      if (scenario === 'denied') {
        await expect(dialog.getByRole('alert')).toContainText('authorization_denied');
        expect(completed).toBe(0);
      } else {
        await expect(dialog.getByRole('status')).toContainText('Account connected and Vault added to this agent.');
        expect(definition.defaultVaultIds).toEqual(['existing-vault', 'vault']);
        expect(definition.workspaceId).toBe('review');
        expect(definition.mcpServers).toEqual([server]);
        expect(completed).toBe(1);
        await page.screenshot({ path: '/tmp/managed-oauth-connected.png', fullPage: true });
        await dialog.getByRole('button', { name: 'Disconnect from Vault' }).click();
        await expect(dialog.getByRole('status')).toContainText('Credential removed');
      }
    }
    expect(errors).toEqual([]);
  });
}
