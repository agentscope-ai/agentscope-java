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

import { test, expect, type Page } from '@playwright/test';
import type { AccessGroup, ResourcePolicy } from '../src/api/resourceAccess';

async function fixture(page: Page) {
  const state = { user: 'alice', version: 1, groups: {} as Record<string, AccessGroup>, policy: { mode: 'inherit' } as ResourcePolicy, requests: [] as { id: string; user: string; resource: string; action: string; reason: string; status: string; createdAt: string }[], imported: undefined as unknown, errors: [] as string[] };
  const token = `test.${Buffer.from(JSON.stringify({ sub: 'alice', username: 'alice', roles: ['user'] })).toString('base64url')}.test`;
  await page.addInitScript(value => localStorage.setItem('claw_token', value), token);
  page.on('pageerror', e => state.errors.push(e.message));
  const users = ['alice', 'bob'].map(id => ({ userId: id, username: id, displayName: id === 'alice' ? 'Alice' : 'Bob', roles: ['user'], disabled: false, version: 1 }));
  const roles = () => state.user === 'alice' ? ['admin', 'member', 'developer', 'operator'] : ['member'];
  const ns = () => ({ tenant: 'default', name: 'engineering', displayName: 'Engineering', kind: 'shared', owner: 'alice', members: { bob: ['member'] }, roles: roles(), canManage: state.user === 'alice', memberCount: 2, archived: false, version: state.version, groups: state.groups, resources: { 'agent:worker': state.policy }, requests: state.requests });
  const resources = [{ kind: 'agent', id: 'worker', name: 'Support worker', dependencies: ['vault:credential'] }, { kind: 'vault', id: 'credential', name: 'Support credential', dependencies: [] }];
  const actions = ['discover', 'use', 'inspect', 'edit', 'publish', 'manage'];
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname, method = req.method();
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me' || path === '/api/user/profile') return json(users.find(u => u.userId === state.user));
    if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: 'engineering', mode: 'multi', selectorVisible: true, namespaces: [ns()] });
    if (path === '/api/v1/me/preferences') return json({ preferences: {} });
    if (path.endsWith('/accounts')) { const q = url.searchParams.get('q') || ''; return json({ items: users.filter(u => u.username.includes(q.toLowerCase())) }); }
    if (path === '/api/v1/namespaces') return json({ items: [ns()] });
    if (path === '/api/v1/namespaces/engineering') return json({ namespace: ns() });
    if (path.endsWith('/groups')) {
      if (method === 'PUT') { expect(req.postDataJSON().version).toBe(state.version); state.groups = req.postDataJSON().groups; state.version++; }
      return json({ groups: state.groups, version: state.version, canManage: state.user === 'alice' });
    }
    if (path.endsWith('/resources')) return json({ items: resources.map(resource => ({ resource, actions: state.user === 'alice' ? actions : ['discover'] })), version: state.version });
    if (/\/resources\/[^/]+\/[^/]+\/access$/.test(path)) {
      if (method === 'PUT') { expect(req.postDataJSON().version).toBe(state.version); state.policy = req.postDataJSON().policy; state.version++; }
      const resource = resources.find(r => r.id === path.split('/').at(-2))!;
      return json({ resource, decisions: actions.map(action => ({ action, allowed: state.user === 'alice' || action === 'discover', reason: state.user === 'alice' ? 'Allowed by namespace roles' : 'Ask a resource manager for access', sources: [] })), version: state.version, canManage: state.user === 'alice', ...(state.user === 'alice' ? { policy: state.policy, groups: state.groups, dependents: resource.kind === 'vault' ? [resources[0]] : [] } : {}) });
    }
    if (path.endsWith('/requests')) {
      if (method === 'POST') { const input = req.postDataJSON(); expect(input.version).toBe(state.version); state.requests.push({ ...input, id: 'request-1', user: state.user, status: 'pending', createdAt: new Date().toISOString() }); state.version++; }
      return json({ items: state.requests, version: state.version, canManage: state.user === 'alice' }, method === 'POST' ? 201 : 200);
    }
    if (path.endsWith('/review')) { expect(req.postDataJSON().version).toBe(state.version); state.requests[0].status = req.postDataJSON().approve ? 'approved' : 'denied'; state.version++; return json({ version: state.version }); }
    if (path.endsWith('/audit')) return json({ items: [{ id: 1, namespace: ns(), actor: 'alice', version: state.version, createdAt: new Date().toISOString() }] });
    if (path.endsWith('/shared-templates')) return json({ canImport: state.user === 'alice', items: [{ sourceNamespace: 'templates', id: 'triage', name: 'Triage template', description: 'Reusable support workflow', revisionId: 'rev-1', revision: 2, sourceVersion: 3, dependencies: ['agent:source-worker'] }] });
    if (path.endsWith('/import-template')) { state.imported = req.postDataJSON(); return json({ definition: { id: 'imported-workflow' } }, 201); }
    if (path === '/api/v1/inbox/summary') return json({ summary: { unread: 0, attentionTotal: 0, pendingApprovals: 0 } });
    return json({ items: [], events: [] });
  });
  return state;
}

test('namespace user groups and resource grants are reviewable and saved', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/settings/namespaces/engineering?tab=user+groups');
  await page.getByLabel('Group name', { exact: true }).fill('Builders');
  await page.getByLabel('Group identifier', { exact: true }).fill('builders');
  await page.getByRole('button', { name: 'Add group', exact: true }).click();
  await page.getByRole('combobox', { name: 'Add person to builders' }).fill('bob');
  await page.getByRole('option', { name: 'Bob (bob)', exact: true }).click();
  await page.getByRole('button', { name: 'Add person', exact: true }).click();
  await page.getByLabel('builders developer', { exact: true }).check();
  await page.getByRole('button', { name: 'Save user groups', exact: true }).click();
  await expect.poll(() => state.groups.builders?.members).toEqual(['bob']);
  expect(state.groups.builders.roles).toContain('developer');
  await page.screenshot({ path: '/tmp/agentscope-resource-groups.png', fullPage: true });
  await page.getByRole('button', { name: 'resources', exact: true }).click();
  await page.getByRole('link', { name: /Support worker/ }).click();
  await expect(page.getByRole('heading', { name: 'Your effective permissions' })).toBeVisible();
  await page.getByLabel('Access policy', { exact: true }).selectOption('restricted');
  await page.getByLabel('Grant access to a group', { exact: true }).selectOption('builders');
  await page.getByRole('button', { name: 'Add group grant', exact: true }).click();
  await page.getByRole('button', { name: 'Save resource permissions', exact: true }).click();
  await expect.poll(() => state.policy.mode).toBe('restricted');
  expect(state.policy.groups?.builders).toEqual(['discover', 'use']);
  await page.screenshot({ path: '/tmp/agentscope-resource-grants.png', fullPage: true });
  await page.goto('/settings/namespaces/engineering?tab=access+log');
  await page.getByText('Version 3', { exact: false }).click();
  await expect(page.getByText('Group builders: discover, use')).toBeVisible();
  expect(state.errors).toEqual([]);
});

test('member requests access and namespace manager approves it', async ({ page }) => {
  const state = await fixture(page); state.user = 'bob';
  await page.goto('/settings/namespaces/engineering/resources/agent/worker');
  await expect(page.getByRole('heading', { name: 'Resource grants', exact: true })).toHaveCount(0);
  await page.getByLabel('Access request reason', { exact: true }).fill('Need to handle incoming support requests');
  await page.getByRole('button', { name: 'Request access', exact: true }).click();
  await expect.poll(() => state.requests.length).toBe(1);
  state.user = 'alice';
  await page.goto('/settings/namespaces/engineering?tab=requests');
  await page.getByRole('button', { name: 'Approve access', exact: true }).click();
  await expect.poll(() => state.requests[0].status).toBe('approved');
  await page.screenshot({ path: '/tmp/agentscope-resource-requests.png', fullPage: true });
  expect(state.errors).toEqual([]);
});

test('template import requires local dependency mapping and works on mobile', async ({ page }) => {
  const state = await fixture(page);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/settings/namespaces/engineering?tab=shared+templates');
  await page.getByRole('button', { name: 'Import template', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Create local draft', exact: true })).toBeDisabled();
  await page.getByLabel('Replace agent:source-worker', { exact: true }).selectOption('agent:worker');
  await page.getByRole('button', { name: 'Create local draft', exact: true }).click();
  await expect(page.getByRole('link', { name: /Open imported Workflow/ })).toHaveAttribute('href', /namespace=engineering/);
  expect(state.imported).toMatchObject({ sourceNamespace: 'templates', sourceVersion: 3, bindings: { 'agent:source-worker': 'agent:worker' } });
  await page.screenshot({ path: '/tmp/agentscope-resource-template-mobile.png', fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBeTruthy();
  expect(state.errors).toEqual([]);
});
