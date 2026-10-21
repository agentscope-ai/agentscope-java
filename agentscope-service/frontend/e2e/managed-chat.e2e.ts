// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
import { test, expect } from '@playwright/test';

for (const archived of [false, true]) {
  test(`Managed Chat uses native snapshots and turns (${archived ? 'archived' : 'active'})`, async ({ page }) => {
    const token = `test.${Buffer.from(JSON.stringify({ username: 'alice', roles: ['admin'] })).toString('base64url')}.test`;
    await page.addInitScript(t => localStorage.setItem('claw_token', t), token);
    const errors: string[] = [], legacyReads: string[] = [], turns: unknown[] = [];
    const keys: string[] = [];
    let exists = archived;
    page.on('pageerror', error => errors.push(error.message));
    const chat = { id: 'chat', tenant: 'default', namespace: 'default', creatorRef: 'alice',
      agentId: 'worker', agentName: 'Worker', sessionId: 'descriptor', runtimeSessionId: 'native',
      runtimeKind: 'managed', title: 'Native conversation', pinned: false, version: 1,
      status: archived ? 'archived' : 'active', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
    await page.route('**/api/**', async route => {
      const path = new URL(route.request().url()).pathname;
      if (!path.startsWith('/api/')) return route.continue();
      const json = (data: unknown, status = 200) => route.fulfill({ json: data, status });
      if (path === '/api/auth/dev-session') return route.fulfill({ status: 404 });
      if (path === '/api/auth/me') return json({ userId: 'alice', username: 'alice', roles: ['admin'], isAdmin: true });
      if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: 'default', mode: 'single', selectorVisible: false,
        namespaces: [{ tenant: 'default', name: 'default', roles: ['admin'] }] });
      if (path === '/api/v1/chats' && route.request().method() === 'POST') { exists = true; return json({ chat }, 201); }
      if (path === '/api/v1/chats') return json({ items: exists ? [chat] : [] });
      if (path === '/api/v1/chat-agents') return json({ items: [{ id: 'worker', name: 'Worker', capability: { state: 'available' } }] });
      if (path === '/api/v1/chats/chat') return json({ chat });
      if (path === '/api/v1/sessions/descriptor') return json({ id: 'descriptor', sessionId: 'native',
        agentName: 'Worker', framework: 'managed', runtime: { kind: 'managed' }, namespace: 'default',
        phase: archived ? 'archived' : 'idle', capabilities: ['context-query', 'tasks-query', 'subagent-tasks-query'] });
      if (path.startsWith('/api/v1/sessions/') && path.includes('/events')) legacyReads.push(path);
      return json({ items: [], preferences: {} });
    });
    await page.route('**/v1/sessions/native**', async route => {
      const req = route.request(), path = new URL(req.url()).pathname;
      if (path.endsWith('/events/stream')) return route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' });
      if (path.endsWith('/snapshot')) {
        const item = { id: 'answer', type: 'item.completed', session_id: 'native', data: { item_id: 'answer',
          item: { id: 'answer', role: 'ASSISTANT', content: [{ type: 'text', text: 'Committed native answer' }] } } };
        const empty = { snapshot_id: 'fixed', as_of: 'cursor', data: [], has_more: false, next_cursor: null };
        return route.fulfill({ json: { snapshot_id: 'fixed', as_of: 'cursor', usage: {}, resources: { items: { ...empty, data: [item] } } } });
      }
      if (path.endsWith('/capabilities')) return route.fulfill({ json: { runtime_kind: 'managed', capabilities: { files: false, file_input: false, artifacts: false }, available_commands: [] } });
      if (path.endsWith('/tool-tasks')) return route.fulfill({ json: { data: [], has_more: false, next_cursor: null } });
      if (path.endsWith('/turns/turn')) return route.fulfill({ json: { id: 'turn', session_id: 'native', status: 'accepted' } });
      if (path.endsWith('/turns')) {
        if (req.method() === 'POST') {
          turns.push(req.postDataJSON());
          expect(req.headers()['idempotency-key']).toBeTruthy();
          keys.push(req.headers()['idempotency-key']);
          if (!archived && turns.length === 1) return route.fulfill({ status: 503, json: { error: 'Ambiguous initial submit' } });
          return route.fulfill({ json: { id: 'turn', session_id: 'native', status: 'accepted' }, status: 202 });
        }
        return route.fulfill({ json: { data: [], has_more: false, next_cursor: null } });
      }
      return route.fulfill({ json: { id: 'native', runtime_kind: 'managed', agent: { id: 'worker' }, environment_id: 'env',
        lifecycle: 'active', status: 'idle', created_at: new Date().toISOString(), updated_at: new Date().toISOString() } });
    });
    await page.goto(archived ? '/work/chat?chat=chat' : '/work/chat?new=1&agent=worker');
    if (!archived) {
      await page.getByPlaceholder('What would you like to work through?').fill('First message');
      await page.getByRole('button', { name: 'Send message', exact: true }).click();
      await expect(page.getByLabel('New turn message')).toHaveValue('First message');
      await page.getByRole('button', { name: 'Send', exact: true }).click();
      await expect.poll(() => turns.length).toBe(2);
      expect(keys[0]).toBe(keys[1]);
      expect(turns[0]).toEqual(turns[1]);
      await expect(page.getByLabel('New turn message')).toHaveValue('');
    }
    await expect(page.getByText('Committed native answer', { exact: true })).toBeVisible();
    await expect(page.getByRole('link', { name: 'Create issue' })).toBeVisible();
    if (archived) await expect(page.getByLabel('New turn message')).toHaveCount(0);
    else {
      await page.getByLabel('New turn message').fill('Follow up');
      await page.getByRole('button', { name: 'Send', exact: true }).click();
      await expect.poll(() => turns.length).toBe(3);
      expect(keys[2]).not.toBe(keys[1]);
      expect(turns[2]).toEqual({ input: { input: [{ role: 'user', content: [{ type: 'text', text: 'Follow up' }] }] } });
    }
    expect(legacyReads).toEqual([]);
    await page.getByRole('link', { name: 'Session diagnostics' }).click();
    await expect(page.getByText('Committed native answer', { exact: true })).toBeVisible();
    expect(legacyReads).toEqual([]);
    expect(errors).toEqual([]);
  });
}

test('native execution keeps an initial draft after mount', async ({ page }) => {
  const submissions: Array<{ key: string | undefined; body: unknown }> = [];
  await page.route('**/api/**', route => new URL(route.request().url()).pathname.startsWith('/api/')
    ? route.fulfill({ json: { localDev: true, items: [] } }) : route.continue());
  await page.route('**/v1/sessions/**', route => {
    if (route.request().method() === 'POST') {
      submissions.push({ key: route.request().headers()['idempotency-key'], body: route.request().postDataJSON() });
      return route.fulfill(submissions.length === 1 ? { status: 503, json: { error: 'Ambiguous submit' } }
        : { status: 202, json: { id: 'turn', session_id: 'native', status: 'accepted' } });
    }
    if (new URL(route.request().url()).pathname.endsWith('/events/stream'))
      return route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' });
    return route.fulfill({ json: { data: [], capabilities: { files: false, file_input: false, artifacts: false },
    snapshot_id: 'fixed', as_of: 'cursor', resources: {}, usage: {}, id: 'native', agent: { id: 'worker' },
    lifecycle: 'active', created_at: new Date().toISOString(), updated_at: new Date().toISOString() } });
  });
  await page.goto('/');
  await page.evaluate(async () => {
    const { default: React } = await import('/node_modules/.vite/deps/react.js');
    const { default: ReactDOM } = await import('/node_modules/.vite/deps/react-dom_client.js');
    const { default: SessionExecution } = await import('/src/components/SessionExecution.tsx');
    const container = document.createElement('div'); document.body.replaceChildren(container);
    ReactDOM.createRoot(container).render(React.createElement(SessionExecution, { sessionId: 'native', initialMessage: 'Retained retry draft', initialRequestKey: 'original-submit-key' }));
  });
  await expect(page.getByLabel('New turn message')).toHaveValue('Retained retry draft');
  await page.getByRole('button', { name: 'Send', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('Ambiguous submit');
  await page.getByRole('button', { name: 'Send', exact: true }).click();
  await expect.poll(() => submissions.length).toBe(2);
  expect(submissions[0].key).toBe('original-submit-key');
  expect(submissions[0]).toEqual(submissions[1]);
});
