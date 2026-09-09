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

const sessionId = '00000000-0000-4000-8000-000000000001';
const events = [
  { seq: 1, eventType: 'user.message', role: 'user', content: 'Check the workspace and summarize.' },
  { seq: 2, eventType: 'span.model_request_start', occurredAt: '2026-09-08T00:00:00Z' },
  { seq: 3, eventType: 'agent.thinking', role: 'assistant', content: 'First inspect the available files.' },
  { seq: 4, eventType: 'span.model_request_end', occurredAt: '2026-09-08T00:00:03.500Z' },
  { seq: 5, eventType: 'agent.tool_use', role: 'assistant', toolName: 'read_file', toolInput: { path: 'README.md' }, frameworkMeta: { toolCallId: 'call-one' } },
  { seq: 6, eventType: 'agent.tool_result', role: 'tool', toolName: 'read_file', toolOutput: 'File is unavailable', frameworkMeta: { toolCallId: 'call-one', state: 'ERROR' } },
  { seq: 7, eventType: 'agent.message', role: 'assistant', content: 'Please provide the file.' },
  { seq: 8, eventType: 'session.status_idle' },
].map(e => ({ id: e.seq, sessionFk: sessionId, ...e, frameworkMeta: { managedEventId: `evt-${e.seq}`, managedSeq: e.seq, sourceKey: `managed:evt-${e.seq}`, turnId: '', attemptId: '', ...e.frameworkMeta } }));

test('conversation shows reasoning, paired tools and explanatory event details after reload', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', error => { errors.push(error.message); console.error(error.message); });
  const token = `test.${Buffer.from(JSON.stringify({ username: 'alice', roles: ['admin'] })).toString('base64url')}.test`;
  await page.addInitScript(token => localStorage.setItem('claw_token', token), token);
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    if (!path.startsWith('/api/')) return route.continue();
    const json = (data: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) });
    if (path === '/api/auth/me') return json({ username: 'alice', roles: ['admin'], isAdmin: true });
    if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: 'default', mode: 'single', selectorVisible: false, namespaces: [{ tenant: 'default', name: 'default', roles: ['admin', 'developer', 'operator', 'member'] }] });
    if (path.endsWith('/events/stream')) return route.fulfill({ contentType: 'text/event-stream', body: '' });
    if (path.endsWith('/events')) return json({ events: url.searchParams.has('after') ? [] : events });
    if (path === `/api/v1/sessions/${sessionId}`) return json({ id: sessionId, sessionId: 'test-session', agentName: 'MA1', phase: 'idle', capabilities: [], framework: 'managed' });
    return json({ items: [], commands: [], turns: [], agents: [], summary: {} });
  });
  await page.goto(`/work/sessions/${sessionId}`);
  await expect(page.getByText('Model request finished', { exact: true })).toBeVisible();
  await expect(page.getByText('· 3.5 s', { exact: true })).toBeVisible();
  await page.getByText('Thinking', { exact: true }).click();
  await expect(page.getByText('First inspect the available files.')).toBeVisible();
  await page.getByRole('button', { name: 'read_file error' }).click();
  await expect(page.getByText('File is unavailable', { exact: true })).toBeVisible();
  await expect(page.getByText('Events #5 → #6')).toBeVisible();
  await page.screenshot({ path: '/tmp/session-conversation.png', fullPage: true });
  await page.getByRole('button', { name: /Events 8/ }).click();
  await page.getByRole('button', { name: /Model request started/ }).click();
  await expect(page.getByText('Emitted by the managed agent runtime and stored in the session event log.')).toHaveCount(0);
  await expect(page.getByText('Related records', { exact: true })).toHaveCount(0);
  const sessionLink = page.locator(`a[href="/work/sessions/${sessionId}"]`);
  await expect(sessionLink).not.toBeVisible();
  await expect(page.getByText('Deduplication key', { exact: true })).not.toBeVisible();
  await page.screenshot({ path: '/tmp/session-events-simple.png', fullPage: true });
  await page.getByText('Diagnostic details', { exact: true }).click();
  await expect(sessionLink).toBeVisible();
  await expect(page.getByText('Deduplication key', { exact: true })).toBeVisible();
  await page.screenshot({ path: '/tmp/session-events.png', fullPage: true });
  await page.getByLabel('Filter events').selectOption('error');
  await expect(page.locator('[data-event-id]')).toHaveCount(1);
  await expect(page.getByText('read_file · failed', { exact: true })).toBeVisible();
  await page.reload();
  await page.getByText('Thinking', { exact: true }).click();
  await expect(page.getByText('First inspect the available files.')).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  expect(errors).toEqual([]);
});
