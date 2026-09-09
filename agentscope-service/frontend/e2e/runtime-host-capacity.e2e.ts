// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
import { test, expect, type Page } from '@playwright/test';

async function setup(page: Page, { roles = ['admin'], conflict = false } = {}) {
  page.on('pageerror', error => { throw error; });
  let capacity = 1;
  const writes: unknown[] = [];
  const token = `test.${Buffer.from(JSON.stringify({ username: 'alice', roles })).toString('base64url')}.test`;
  await page.addInitScript(t => localStorage.setItem('claw_token', t), token);
  const host = () => ({ id: 'host-1', hostKey: 'local-host', poolName: 'coding-default', state: 'online', active: 1, capacity, capabilities: { providers: { qoder: '1', codex: '1' } } });
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me') return json({ username: 'alice', roles });
    if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: 'default', mode: 'single', namespaces: [{ tenant: 'default', name: 'default', roles }] });
    if (path === '/api/v1/agents/agent') return json({ agent: { id: 'agent', agentKey: 'qoder', displayName: 'Qoder', tenant: 'default', namespace: 'default', status: 'active' } });
    if (path.endsWith('/overview')) return json({ readiness: { state: 'ready', reasons: [] }, work: {}, bindings: [] });
    if (path.endsWith('/definition')) return json({ definition: { id: 'agent', name: 'Qoder', version: 1 } });
    if (path.endsWith('/bindings')) return json({ items: [{ id: 'binding', agentId: 'agent', kind: 'hosted-runtime', enabled: true, configuration: {} }] });
    if (path.endsWith('/hosted-settings')) return json({ settings: { runtimeProfile: { id: 'profile', name: 'Qoder', provider: 'qoder' }, runtimePool: { id: 'pool', name: 'coding-default' }, maxConcurrency: 0, executionOverrides: {} } });
    if (path === '/api/v1/runtime-hosts') return json({ items: [host()] });
    if (path === '/api/v1/runtime-hosts/host-1/capacity') {
      const body = route.request().postDataJSON(); writes.push(body);
      if (conflict) return json({ error: 'store: conflict' }, 409);
      capacity = body.capacity; return json({ host: host() });
    }
    return json({ items: [], runtimes: [], profiles: [], pools: [], summary: {}, preferences: {} });
  });
  await page.goto('/agent-center/agents/agent?tab=runtime&tenant=default&namespace=default');
  return writes;
}

test('shared Host capacity saves independently and survives page refresh', async ({ page }) => {
  const writes = await setup(page);
  const form = page.getByRole('form', { name: 'Capacity for local-host' });
  await expect(form.getByText('online · 1 / 1 occupied')).toBeVisible();
  await expect(page.getByRole('spinbutton', { name: /^Agent concurrency/ })).toHaveValue('0');
  await form.getByLabel('Host capacity').fill('0');
  await expect(form.getByRole('button', { name: 'Save capacity' })).toBeDisabled();
  await form.getByLabel('Host capacity').fill('4');
  await form.getByRole('button', { name: 'Save capacity' }).click();
  await expect(form.getByRole('status')).toContainText('Capacity saved');
  expect(writes).toEqual([{ capacity: 4, expectedCapacity: 1 }]);
  await page.reload();
  await expect(form.getByLabel('Host capacity')).toHaveValue('4');
  await expect(page.getByRole('spinbutton', { name: /^Agent concurrency/ })).toHaveValue('0');
});

test('developer cannot edit shared Host capacity', async ({ page }) => {
  await setup(page, { roles: ['developer'] });
  await expect(page.getByText('A namespace administrator or operator can view and change Host capacity.')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Save capacity' })).toHaveCount(0);
});

test('concurrent edits show the server conflict without a false success', async ({ page }) => {
  await setup(page, { conflict: true });
  const form = page.getByRole('form', { name: 'Capacity for local-host' });
  await form.getByLabel('Host capacity').fill('4');
  await form.getByRole('button', { name: 'Save capacity' }).click();
  await expect(form.getByRole('alert')).toContainText('conflict');
  await expect(form.getByRole('status')).toHaveCount(0);
});
