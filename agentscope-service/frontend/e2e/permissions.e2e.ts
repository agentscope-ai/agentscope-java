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

test.setTimeout(90000);

type NS = { tenant: string; name: string; displayName: string; kind: string; owner: string; members: Record<string, string[]>; archived: boolean; version: number };
async function fixture(page: Page) {
  const token = `test.${Buffer.from(JSON.stringify({ sub: 'alice', username: 'alice', roles: ['admin'] })).toString('base64url')}.test`;
  await page.addInitScript(value => localStorage.setItem('claw_token', value), token);
  const users = [
    { userId: 'alice', username: 'alice', displayName: 'Alice', roles: ['user', 'admin'], disabled: false, version: 1, createdAt: Date.now() },
    { userId: 'bob', username: 'bob', displayName: 'Bob', roles: ['user'], disabled: false, version: 1, createdAt: Date.now() },
    { userId: 'carol', username: 'carol', displayName: 'Carol', roles: ['user'], disabled: false, version: 1, createdAt: Date.now() },
  ];
  const namespaces: NS[] = [
    { tenant: 'default', name: 'personal', displayName: 'Personal', kind: 'personal', owner: 'alice', members: {}, archived: false, version: 1 },
    { tenant: 'default', name: 'engineering', displayName: 'Engineering', kind: 'shared', owner: 'alice', members: { bob: ['member'] }, archived: false, version: 1 },
  ];
  const roles = (n: NS) => n.archived ? [] : n.owner === 'alice' ? ['admin', 'member', 'developer', 'operator'] : n.members.alice || [];
  const summary = (n: NS) => ({ ...n, roles: roles(n), canManage: users[0].roles.includes('admin') || roles(n).includes('admin'), memberCount: new Set([n.owner, ...Object.keys(n.members)]).size });
  let preference = ''; let revoked = false;
  const errors: string[] = []; page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/**', async route => {
    const req = route.request(), url = new URL(req.url()), path = url.pathname;
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me') return json(users[0]);
    if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: preference || 'personal', mode: 'multi', selectorVisible: true, namespaces: namespaces.filter(n => roles(n).length).map(summary) });
    if (path === '/api/v1/me/preferences') { if (req.method() === 'PUT') preference = req.postDataJSON().defaultNamespace; return json({ preferences: { defaultNamespace: preference } }); }
    if (path === '/api/user/profile') { if (req.method() === 'PUT') users[0].displayName = req.postDataJSON().displayName; return json(users[0]); }
    if (path === '/api/user/login-sessions') return json({ items: [{ id: 'current', current: true, userAgent: 'Current browser', lastSeenAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 3600000).toISOString() }, ...(!revoked ? [{ id: 'other', current: false, userAgent: 'Other browser', lastSeenAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 3600000).toISOString() }] : [])] });
    if (path.startsWith('/api/user/login-sessions/')) { revoked = true; return route.fulfill({ status: 204 }); }
    if (path === '/api/user/channel-connections') return json({ identities: [], subscriptions: [] });
    if (path.endsWith('/accounts')) { const q = url.searchParams.get('q') || ''; const ids = (url.searchParams.get('ids') || '').split(',').filter(Boolean); return json({ items: users.filter(a => (!ids.length || ids.includes(a.userId)) && `${a.username} ${a.displayName}`.toLowerCase().includes(q.toLowerCase())) }); }
    if (path === '/api/v1/namespaces') {
      if (req.method() === 'POST') { const input = req.postDataJSON(); namespaces.push({ tenant: 'default', kind: 'shared', archived: false, version: 1, ...input }); return json({ namespace: namespaces[namespaces.length - 1] }, 201); }
      return json({ items: namespaces.map(summary) });
    }
    if (path.startsWith('/api/v1/namespaces/')) {
      const name = path.split('/')[4], n = namespaces.find(item => item.name === name)!;
      if (path.endsWith('/audit')) return json({ items: [{ id: 1, name, namespace: n, actor: 'alice', version: n.version, createdAt: new Date().toISOString() }] });
      if (req.method() === 'PUT') { const input = req.postDataJSON(); expect(input.version).toBe(n.version); Object.assign(n, input, { version: n.version + 1 }); }
      return json({ namespace: n });
    }
    if (path === '/api/admin/users') return json(users);
    if (path.startsWith('/api/admin/users/')) { const a = users.find(u => u.userId === path.split('/')[4])!; Object.assign(a, req.postDataJSON(), { version: a.version + 1 }); return json(a); }
    if (path.startsWith('/api/v1/access/users/')) { const id = path.split('/')[5]; return json({ items: namespaces.filter(n => n.owner === id || n.members[id]).map(n => ({ ...n, roles: n.owner === id ? ['admin'] : n.members[id] })) }); }
    if (path === '/api/v1/inbox/summary') return json({ summary: { unread: 0, attentionTotal: 0, pendingApprovals: 0 } });
    return json({ items: [], events: [] });
  });
  return { namespaces, users, errors, get preference() { return preference; }, get revoked() { return revoked; } };
}

test('namespace creation, searchable membership, legacy redirect and draft isolation', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/work/permissions?tenant=default&namespace=forged');
  await expect(page).toHaveURL(/\/settings\/namespaces\/personal/);
  await expect(page.getByText('Personal namespace membership is fixed to its owner.')).toBeVisible();
  await page.getByRole('button', { name: 'Namespace', exact: true }).click();
  await expect(page.getByRole('menuitemradio', { name: /Personal/ })).toHaveAttribute('aria-checked', 'true');
  await page.screenshot({ path: '/tmp/agentscope-namespace-menu.png', fullPage: true });
  await page.getByRole('menuitem', { name: 'Create namespace', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Display name', { exact: true }).fill('Support');
  await dialog.getByLabel('Namespace name', { exact: true }).fill('support');
  await dialog.getByRole('combobox', { name: 'Find a member' }).fill('carol');
  await dialog.getByRole('option', { name: 'Carol (carol)' }).click();
  await dialog.getByRole('button', { name: 'Add member', exact: true }).click();
  await dialog.getByRole('button', { name: 'Create namespace', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Support', exact: true })).toBeVisible();
  expect(state.namespaces.find(n => n.name === 'support')?.members.carol).toEqual(['member']);
  await page.getByLabel('carol developer', { exact: true }).check();
  await page.getByRole('button', { name: 'Save membership', exact: true }).click();
  await expect.poll(() => state.namespaces.find(n => n.name === 'support')?.members.carol).toContain('developer');
  await page.getByRole('combobox', { name: 'Find a member' }).fill('unsaved-draft');
  await page.getByRole('button', { name: 'Namespace', exact: true }).click();
  await page.getByRole('menuitemradio', { name: /Engineering/ }).click();
  await expect(page.getByRole('button', { name: 'Namespace', exact: true })).toContainText('Engineering');
  await expect(page.getByRole('combobox', { name: 'Find a member' })).toHaveValue('');
  await page.screenshot({ path: '/tmp/agentscope-access-namespace.png', fullPage: true });
  expect(state.errors).toEqual([]);
});

test('user-centric grants update the same namespace and role revocation refreshes navigation', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/managed/admin/users');
  await expect(page).toHaveURL(/\/settings\/users/);
  await page.getByRole('button', { name: 'Bob (bob)', exact: true }).click();
  await page.getByRole('button', { name: 'Edit namespace access', exact: true }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByRole('checkbox', { name: /^developer / }).check();
  await dialog.getByRole('button', { name: 'Save access', exact: true }).click();
  await expect.poll(() => state.namespaces[1].members.bob).toContain('developer');
  await page.screenshot({ path: '/tmp/agentscope-access-users.png', fullPage: true });
  state.users[0].roles = ['user'];
  await page.evaluate(() => window.dispatchEvent(new Event('focus')));
  await expect(page).toHaveURL(/\/settings\/namespaces/);
  await expect(page.getByRole('navigation', { name: 'Access settings', exact: true }).getByRole('link', { name: 'Users', exact: true })).toHaveCount(0);
  expect(state.errors).toEqual([]);
});

test('profile defaults, effective access and login session revocation', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/managed/profile');
  await expect(page).toHaveURL(/\/settings\/profile/);
  await page.getByLabel('Display name', { exact: true }).fill('Alice Example');
  await page.getByRole('button', { name: 'Save profile', exact: true }).click();
  await expect.poll(() => state.users[0].displayName).toBe('Alice Example');
  await page.getByRole('button', { name: 'my namespaces', exact: true }).click();
  await page.getByLabel('Default namespace', { exact: true }).selectOption('engineering');
  await page.getByRole('button', { name: 'Save default', exact: true }).click();
  await expect.poll(() => state.preference).toBe('engineering');
  await expect(page.getByText('Private work requires an explicit sharing grant.', { exact: false }).first()).toBeVisible();
  await page.screenshot({ path: '/tmp/agentscope-access-profile.png', fullPage: true });
  await page.getByRole('button', { name: 'security', exact: true }).click();
  await page.getByRole('button', { name: 'Sign out other sessions', exact: true }).click();
  await expect.poll(() => state.revoked).toBe(true);
  await expect(page.getByText('Other browser', { exact: true })).toHaveCount(0);
  await expect(page.getByText('This session', { exact: true })).toBeVisible();
  expect(state.errors).toEqual([]);
});

test('namespace menu supports keyboard, outside dismissal and member access on mobile', async ({ page }) => {
  const state = await fixture(page);
  state.users[0].roles = ['user'];
  await page.goto('/settings/namespaces?tenant=default&namespace=personal');
  const trigger = page.getByRole('button', { name: 'Namespace', exact: true });
  await trigger.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('menu', { name: 'Namespace', exact: true })).toBeVisible();
  await expect(page.getByRole('menuitem', { name: 'Create namespace' })).toHaveCount(0);
  await page.keyboard.press('Escape');
  await expect(trigger).toBeFocused();
  await trigger.click();
  await page.getByRole('menuitemradio', { name: /Personal/ }).focus();
  await page.keyboard.press('ArrowDown');
  await expect(page.getByRole('menuitemradio', { name: /Engineering/ })).toBeFocused();
  await page.keyboard.press('Enter');
  await expect(trigger).toContainText('Engineering');
  await expect(page).toHaveURL(/namespace=engineering/);
  await trigger.click();
  await expect(page.getByRole('menu', { name: 'Namespace', exact: true })).toBeVisible();
  await page.getByRole('heading', { name: 'Namespaces', exact: true }).click();
  await expect(page.getByRole('menu', { name: 'Namespace', exact: true })).toHaveCount(0);
  await page.setViewportSize({ width: 390, height: 844 });
  await page.getByRole('button', { name: 'Open navigation' }).click();
  await trigger.click();
  const menu = page.getByRole('menu', { name: 'Namespace', exact: true });
  await expect(menu).toBeVisible();
  const bounds = await menu.boundingBox();
  expect(bounds!.x).toBeGreaterThanOrEqual(0);
  expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(390);
  await page.screenshot({ path: '/tmp/agentscope-namespace-menu-mobile.png', fullPage: true });
  await page.getByRole('menuitem', { name: 'Namespace access', exact: true }).click();
  await expect(page).toHaveURL(/\/settings\/namespaces\/engineering\?/);
  await expect(page.getByRole('heading', { name: 'Engineering', exact: true })).toBeVisible();
  expect(state.errors).toEqual([]);
});
