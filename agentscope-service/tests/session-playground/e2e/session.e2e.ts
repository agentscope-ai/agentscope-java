// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
import { expect, test } from '@playwright/test';

const metadata = { id: 's', agentId: 'a', environmentId: 'e', status: 'active', createdAt: 1, updatedAt: 1 };
const event = (id: string, type: string, data: Record<string, unknown>) => ({ schema_version: 1, id, cursor: id, session_id: 's', type, created_at: '2026-10-12T01:00:00Z', data: { turn_id: 't', ...data } });
const snapshot = { snapshot_id: 'fixed', as_of: '0', usage: { model_calls: 2, totals: { totalTokens: 35 } }, resources: Object.fromEntries(['items', 'tools', 'turns', 'runs', 'required_actions'].map(kind => [kind, { snapshot_id: 'fixed', as_of: '0', expires_at: Date.now() + 120000, data: [], has_more: false, next_cursor: null }])) };
const frame = (e: ReturnType<typeof event>) => `id: ${e.cursor}\nevent: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`;

test('snapshot → waiting tool → real result → finalized reply; disconnect only closes observation', async ({ page }) => {
  const requests: string[] = [];
  await page.route('**/api/v1/agent-sessions/s**', async route => {
    const url = new URL(route.request().url()); requests.push(`${route.request().method()} ${url.pathname}`);
    if (url.pathname.endsWith('/snapshot')) return route.fulfill({ json: snapshot });
    if (url.pathname.endsWith('/events/stream')) {
      const rows = url.searchParams.get('after') === '0' ? [event('1', 'tool.requested', { tool_call_id: 'call', name: 'execute', input: { command: 'printf done' } }), event('2', 'tool.waiting', { tool_call_id: 'call', status: 'waiting' })] : url.searchParams.get('after') === '2' ? [
        event('3', 'tool.completed', { tool_call_id: 'call', status: 'completed', result: { id: 'call', state: 'success', output: [{ type: 'text', text: 'real tool result' }] } }),
        event('4', 'item.started', { item_id: 'reply', item: { role: 'assistant', status: 'in_progress', content: [] } }),
        event('5', 'item.delta', { item_id: 'reply', content: [{ type: 'text', text: 'partial reply' }] }),
        event('6', 'item.finalized', { item_id: 'reply', final_output: true, item: { role: 'assistant', status: 'completed', content: [{ type: 'text', text: 'final reply' }] } }),
        event('7', 'turn.completed', { status: 'completed' }),
      ] : [];
      return route.fulfill({ contentType: 'text/event-stream', body: rows.map(frame).join('') || ': keepalive\n\n' });
    }
    return route.fulfill({ json: metadata });
  });
  await page.goto('/?session=s'); await page.getByRole('button', { name: '连接并观察' }).click();
  await expect(page.locator('.tool-card .status')).toHaveText('waiting');
  await expect(page.locator('.tool-output')).toHaveText('real tool result');
  await expect(page.locator('.messages .markdown')).toHaveText('final reply');
  await expect(page.locator('.messages .message')).toHaveCount(1);
  await page.getByRole('button', { name: '断开 SSE' }).click();
  await expect(page.getByRole('button', { name: '连接 SSE' })).toBeEnabled();
  expect(requests.some(r => r.startsWith('POST'))).toBe(false);
  await page.getByRole('button', { name: '快照 / 状态' }).click();
  await expect(page.locator('.cursor code')).toHaveText('7');
  await page.screenshot({ path: 'test-results/playground-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'test-results/playground-mobile.png', fullPage: true });
});

test('lost POST response retains the exact body and key across reload; retry cannot submit a different instruction', async ({ page }) => {
  const submissions: { body: string | null; key: string | undefined }[] = [];
  await page.route('**/api/v1/agent-sessions/s**', async route => {
    const url = new URL(route.request().url());
    if (route.request().method() === 'POST') {
      submissions.push({ body: route.request().postData(), key: route.request().headers()['idempotency-key'] });
      if (submissions.length === 1) return route.abort('connectionfailed');
      return route.fulfill({ status: 202, json: { id: 't', sessionId: 's', status: 'queued', createdAt: 1 } });
    }
    if (url.pathname.endsWith('/snapshot')) return route.fulfill({ json: snapshot });
    if (url.pathname.endsWith('/events/stream')) return route.fulfill({ contentType: 'text/event-stream', body: ': keepalive\n\n' });
    return route.fulfill({ json: metadata });
  });
  await page.goto('/?session=s'); await page.getByRole('button', { name: '连接并观察' }).click();
  await page.getByRole('textbox', { name: '用户指令' }).fill('one instruction'); await page.getByRole('button', { name: '发送指令' }).click();
  await expect(page.getByRole('button', { name: '以原幂等键重试' })).toBeEnabled();
  await page.reload(); await page.getByRole('button', { name: '连接并观察' }).click();
  await page.getByRole('button', { name: '以原幂等键重试' }).click();
  await expect(page.getByText('最近受理回执 · t · queued')).toBeVisible();
  expect(submissions).toHaveLength(2); expect(submissions[0]).toEqual(submissions[1]); expect(submissions[0].key).toBeTruthy();
  await expect(page.getByRole('textbox', { name: '用户指令' })).toBeEnabled();
  await page.getByRole('textbox', { name: 'Session ID / Session URL' }).fill('another-session');
  await expect(page.getByRole('textbox', { name: '用户指令' })).toBeDisabled();
  await expect(page.getByRole('button', { name: '发送指令' })).toBeDisabled();
});
