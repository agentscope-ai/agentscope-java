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

async function fixture(page: Page, kind = 'hosted-runtime', edit = true, linked = true, overrides: string[] = []) {
  const token = `test.${Buffer.from(JSON.stringify({ sub: 'alice', username: 'alice', roles: ['user'] })).toString('base64url')}.test`;
  await page.addInitScript(t => localStorage.setItem('claw_token', t), token);
  const catalog = { id: 'worker', agentKey: 'worker', displayName: 'Review agent', tenant: 'default', namespace: 'personal', ownerRef: 'alice', ownerType: 'user', status: 'active', version: 1 };
  const bindings = [{ id: 'binding', agentId: 'worker', kind, enabled: true, priority: 100, configuration: {} }];
  let definition: Record<string, any> = { id: 'worker', name: 'Review agent', version: 1, tools: [], mcpServers: [], skills: [{ name: 'review' }], workspaceId: 'pack', workspaceBinding: { version: 1, digest: 'old', overrides: [], instructions: 'Be concise' }, definitionDigest: 'agent-digest', system: 'Published one\nBe concise' };
  if (!linked) { definition.workspaceId = ''; definition.workspaceBinding = null; }
  else definition.workspaceBinding.overrides = overrides;
  let draft = 'Private draft';
  const fileWrites: string[] = [];
  const saves: any[] = [], errors: string[] = [];
  page.on('pageerror', e => { errors.push(e.message); console.error(e.message); });
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    const json = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/auth/me') return json({ userId: 'alice', username: 'alice', roles: ['user'] });
    if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: 'personal', mode: 'multi', selectorVisible: true, namespaces: [{ tenant: 'default', name: 'personal', displayName: 'Personal', kind: 'personal', owner: 'alice', roles: edit ? ['admin', 'developer', 'member'] : ['member'] }] });
    if (path === '/api/v1/me/preferences') return json({ preferences: {} });
    if (path === '/api/v1/agents/worker') return json({ agent: catalog });
    if (path.endsWith('/tools/catalog/builtins')) return json([{ id: 'web_search', description: 'Search the web' }]);
    if (path.endsWith('/tools/catalog/mcp-servers')) return json([]);
    if (path.endsWith('/bindings')) return json({ items: bindings });
    if (path.endsWith('/definition')) {
      if (req.method() === 'PATCH') { saves.push(req.postDataJSON()); definition = { ...definition, ...req.postDataJSON(), version: definition.version + 1 }; }
      return json({ agent: catalog, definition });
    }
    if (path.endsWith('/workspace-capabilities')) return json({ bindings: [{ bindingId: 'binding', kind, runtime: kind === 'hosted-runtime' ? 'Codex' : 'External application', status: kind === 'hosted-runtime' ? 'provider-adapted' : 'application-managed', capabilities: [{ name: 'instructions', mode: 'native', requested: true, supported: true }, { name: 'subagents', mode: 'unsupported', requested: true, supported: false }] }], applications: [] });
    if (path.startsWith('/api/v1/agents/worker/versions/')) return json({ version: { version: definition.version, snapshot: { definitionFiles: { 'AGENTS.md': !definition.workspaceBinding && definition.version > 1 ? draft : (!definition.workspaceBinding || definition.workspaceBinding.version === 1) ? 'Published one' : 'Published two', 'skills/review/SKILL.md': 'Review skill' } } } });
    if (path === '/api/agents/worker/workspace/files') return json([{ name: 'AGENTS.md', path: 'AGENTS.md', type: 'file' }]);
    if (path === '/api/agents/worker/workspace/file') {
      if (req.method() === 'PUT') { draft = req.postDataJSON().content; fileWrites.push(draft); return json({}); }
      return route.fulfill({ contentType: 'text/plain', body: draft });
    }
    if (path === '/api/workspaces') return json([{ id: 'pack', name: 'Review pack' }]);
    if (path === '/api/workspaces/pack/revisions') return json({ items: [2, 1].map(version => ({ version, digest: `digest-${version}`, skills: [{ name: 'review' }], files: { 'AGENTS.md': 'Draft must never appear here' } })) });
    if (path.endsWith('/overview')) return json({ readiness: { state: 'ready' }, instances: { healthy: 1 }, usage: {} });
    return json({ items: [], instances: [], events: [], summary: {} });
  });
  return { saves, errors, fileWrites };
}

test('Hosted Agent pins published files and explicitly updates revision and skill selection', async ({ page }) => {
  const state = await fixture(page);
  await page.goto('/agent-center/agents/worker/definition/workspace?tenant=default&namespace=personal');
  await expect(page.getByRole('heading', { name: 'Workspace definition', exact: true })).toBeVisible();
  await expect(page.getByText('Published one', { exact: true })).toBeVisible();
  await expect(page.getByText('Draft must never appear here', { exact: true })).toHaveCount(0);
  await expect(page.getByText('Requires a compatible runtime before execution.')).toBeVisible();
  await page.getByLabel('Workspace revision', { exact: true }).selectOption('2');
  await page.getByLabel('Enabled skills', { exact: true }).check();
  await page.getByLabel('review', { exact: true }).uncheck();
  await page.getByRole('button', { name: 'Save definition binding' }).click();
  await expect.poll(() => state.saves.length).toBe(1);
  expect(state.saves[0].workspaceBinding).toMatchObject({ version: 2, overrides: ['skills'], instructions: 'Be concise' });
  expect(state.saves[0].skills).toEqual([]);
  await expect(page.getByText('Published two', { exact: true })).toBeVisible();
  await page.screenshot({ path: '/tmp/agentscope-workspace-definition.png', fullPage: true });
  expect(state.errors).toEqual([]);
});

for (const entry of [
  { name: 'local definition', linked: false, edit: true, overrides: [], editable: true },
  { name: 'inherited definition', linked: true, edit: true, overrides: [], editable: false },
  { name: 'tools override', linked: true, edit: true, overrides: ['tools'], editable: true },
  { name: 'read-only member', linked: true, edit: false, overrides: ['tools'], editable: false },
]) {
  test(`tool configuration respects ${entry.name}`, async ({ page }) => {
    const state = await fixture(page, 'hosted-runtime', entry.edit, entry.linked, entry.overrides);
    await page.goto('/agent-center/agents/worker/definition/tools?tenant=default&namespace=personal');
    const configure = page.getByRole('button', { name: '+ Add / configure', exact: true });
    if (!entry.editable) {
      await expect(configure).toHaveCount(0);
      await expect(page.getByRole('button', { name: 'Disable', exact: true })).toHaveCount(0);
    } else {
      await configure.click();
      const dialog = page.getByRole('dialog', { name: 'Configure tools' });
      await expect(dialog).toBeVisible();
      if (entry.linked) await expect(dialog.getByRole('button', { name: 'MCP servers', exact: true })).toHaveCount(0);
      await dialog.getByRole('checkbox').first().uncheck();
      await dialog.getByRole('button', { name: /Save/ }).click();
      await expect.poll(() => state.saves.length).toBe(1);
      expect(state.saves[0].tools[0].configs).toContainEqual(expect.objectContaining({ name: 'web_search', enabled: false }));
      await dialog.getByRole('button', { name: 'Close', exact: true }).click();
      await expect(dialog).toHaveCount(0);
    }
    if (entry.linked) await expect(page.getByRole('link', { name: 'Edit in Workspace →' })).toHaveAttribute('href', /namespace=personal/);
    expect(state.errors).toEqual([]);
  });
}

test('External member sees compatibility and fixed definition without edit controls', async ({ page }) => {
  const state = await fixture(page, 'external-application', false);
  await page.goto('/agent-center/agents/worker/definition/workspace?tenant=default&namespace=personal');
  await expect(page.getByText('External applications must explicitly enable', { exact: false })).toBeVisible();
  await expect(page.getByLabel('Definition workspace')).toBeDisabled();
  await expect(page.getByRole('button', { name: 'Save definition binding' })).toHaveCount(0);
  await expect(page.getByText('Published one', { exact: true })).toBeVisible();
  expect(state.errors).toEqual([]);
});


test('Private file edits stay draft until explicitly published', async ({ page }) => {
  const state = await fixture(page, 'managed', true, false);
  await page.goto('/agent-center/agents/worker/definition/workspace?tenant=default&namespace=personal');
  await page.getByRole('button', { name: 'Private definition draft' }).click();
  await page.getByText('AGENTS.md', { exact: true }).click();
  await expect(page.getByLabel('Definition file content')).toHaveValue('Private draft');
  await page.getByLabel('Definition file content').fill('Edited private draft');
  await page.getByRole('button', { name: 'Save draft', exact: true }).click();
  await expect.poll(() => state.fileWrites).toEqual(['Edited private draft']);
  expect(state.saves).toHaveLength(0);
  await page.getByRole('button', { name: 'Publish private files' }).click();
  await expect.poll(() => state.saves.length).toBe(1);
  await expect(page.locator('pre').filter({ hasText: 'Edited private draft' })).toBeVisible();
  expect(state.errors).toEqual([]);
});
