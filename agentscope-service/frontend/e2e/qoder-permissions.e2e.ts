// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
import { test, expect, type Page } from '@playwright/test';

async function setup(page: Page) {
  const roles = ['admin'];
  page.on('pageerror', error => { throw error; });
  let executionOverrides: Record<string, unknown> = {};
  const writes: unknown[] = [];
  const token = `test.${Buffer.from(JSON.stringify({ username: 'alice', roles })).toString('base64url')}.test`;
  await page.addInitScript(t => localStorage.setItem('claw_token', t), token);
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
    if (path.endsWith('/hosted-settings') && route.request().method() === 'PATCH') { executionOverrides = route.request().postDataJSON().executionOverrides; writes.push(executionOverrides); }
    if (path.endsWith('/hosted-settings')) return json({ settings: { runtimeProfile: { id: 'profile', name: 'Qoder', provider: 'qoder' }, runtimePool: { id: 'pool', name: 'coding-default' }, bindingVersion: 1, policyVersion: 1, maxConcurrency: 0, executionOverrides } });
    return json({ items: [], runtimes: [], profiles: [], pools: [], summary: {}, preferences: {} });
  });
  await page.goto('/agent-center/agents/agent?tab=runtime&tenant=default&namespace=default');
  return writes;
}

test('Qoder full access is selectable, saved and restored without changing the default', async ({ page }) => {
  const writes = await setup(page);
  const mode = page.getByRole('combobox', { name: /^Permission mode/ });
  await expect(mode).toHaveValue('');
  await mode.selectOption('bypass_permissions');
  await expect(page.getByText('Tools run without Qoder approval prompts', { exact: false })).toBeVisible();
  await page.getByRole('button', { name: 'Save settings', exact: true }).click();
  await expect(page.getByText('Hosted execution settings saved.', { exact: false })).toBeVisible();
  expect(writes).toContainEqual(expect.objectContaining({ providerConfiguration: { permissionMode: 'bypass_permissions' } }));
  await page.reload();
  await expect(mode).toHaveValue('bypass_permissions');
  await mode.selectOption('default');
  await page.getByRole('button', { name: 'Save settings', exact: true }).click();
  await expect(page.getByText('Hosted execution settings saved.', { exact: false })).toBeVisible();
  await page.reload();
  await expect(mode).toHaveValue('default');
});
