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

import { expect, test, type Page } from '@playwright/test';

type WireEvent = { type: string; content?: unknown; managed_agents: { intent: string; turn_id?: string } };
async function openExecution(page: Page, observerUnavailable = false, failFirstWrite = false, finishWithoutObserver = false) {
  const writes: Array<{ path: string; event: WireEvent; key?: string }> = [];
  const requests: string[] = [];
  let attempts = 0, cancelAttempts = 0, outputAttempts = 0, turnReads = 0;
  const turn = { id: 't', session_id: 's', status: observerUnavailable && !finishWithoutObserver ? 'completed' : 'running', created_at: '2026-10-19T00:00:00Z', updated_at: '2026-10-19T00:00:01Z', inputs_open: !observerUnavailable, admission_sequence: 1, delivery_status: 'queued' };
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    if (path === '/api/auth/dev-session') return route.fulfill({ json: { token: 'local-test', userId: 'u', username: 'local-test', roles: ['admin'] } });
    if (path === '/api/auth/me') return route.fulfill({ json: { userId: 'u', username: 'local-test', roles: ['admin'], isAdmin: true } });
    if (path === '/api/v1/me/scope') return route.fulfill({ json: { tenant: 'default', namespace: 'default', mode: 'single', selectorVisible: false, namespaces: [{ tenant: 'default', name: 'default', roles: ['admin'] }] } });
    return route.fulfill({ json: { items: [], summary: { attentionTotal: 0 }, preferences: {} } });
  });
  await page.route('**/v1/sessions/s**', async route => {
    const request = route.request(), url = new URL(request.url()); requests.push(url.pathname);
    if (request.method() === 'POST') {
      const event = request.postDataJSON().events[0] as WireEvent;
      writes.push({ path: url.pathname, event, key: request.headers()['idempotency-key'] });
      if (failFirstWrite && ++attempts === 1) { await route.fulfill({ status: 503, json: { type: 'error', error: { type: 'api_error', message: 'Temporary admission response failure' } } }); return; }
      if (event.managed_agents.intent === 'cancel' && ++cancelAttempts === 1) { await route.fulfill({ status: 503, json: {} }); return; }
      const isTurn = event.managed_agents.intent === 'new_turn';
      await route.fulfill({ json: { data: [{ id: 'event', type: event.type, processed_at: null, managed_agents: { ...event.managed_agents, session_id: 's', turn_id: isTurn ? 'new-turn' : 't', admitted_at: '2026-10-19T00:00:01Z', receipt: { id: isTurn ? 'new-turn' : 'input', type: isTurn ? 'turn' : 'input', status: 'accepted' } } }] } }); return;
    }
    if (url.pathname.endsWith('/events/stream')) {
      expect(url.search).toBe(''); expect(request.headers()['last-event-id']).toBeUndefined();
      await route.fulfill(observerUnavailable ? { status: 503, json: {} } : { contentType: 'text/event-stream', body: ': connected\n\n' }); return;
    }
    if (url.pathname.endsWith('/turns/t/output')) { if (finishWithoutObserver && ++outputAttempts === 1) { await route.fulfill({ status: 503, json: {} }); return; } await route.fulfill({ json: { turn_status: turn.status, output_available: observerUnavailable, data: observerUnavailable ? [{ id: 'answer', type: 'agent.message', content: [{ type: 'text', text: 'Independent final result' }], processed_at: '2026-10-19T00:00:02Z' }] : [], next_cursor: null, has_more: false } }); return; }
    if (/\/turns\/[^/]+\/capabilities$/.test(url.pathname)) {
      await route.fulfill({ json: { runtime_kind: 'managed', available_commands: observerUnavailable ? [] : ['steer', 'inject', 'cancel'], supported_commands: { actions: false }, files: false, file_input: false, artifacts: false } }); return;
    }
    if (url.pathname.endsWith('/capabilities')) { await route.fulfill({ json: { protocol_revision: 'managed-agents-2026-10-19-draft3', capabilities: { files: false, file_input: false, artifacts: false, checkpoints: false, budget: false, webhooks: false } } }); return; }
    if (url.pathname.endsWith('/events')) { await route.fulfill({ json: { data: [], next_page: null } }); return; }
    if (url.pathname.endsWith('/turns')) { await route.fulfill({ json: { data: url.searchParams.get('status') === 'running' && observerUnavailable ? [] : [turn], next_cursor: null, has_more: false } }); return; }
    if (/\/turns\/[^/]+$/.test(url.pathname)) { if (finishWithoutObserver && ++turnReads >= 2) turn.status = 'completed'; await route.fulfill({ json: turn }); return; }
    await route.fulfill({ json: { id: 's', type: 'session', agent: { id: 'a', version: 1 }, environment_id: 'e', status: observerUnavailable ? 'idle' : 'running', created_at: '2026-10-19T00:00:00Z', updated_at: '2026-10-19T00:00:01Z', managed_agents: { runtime_kind: 'managed', lifecycle: 'active', configuration_revision: 1 } } });
  });
  await page.goto('/managed/sessions/s?tab=execution');
  await expect(page.getByRole('heading', { name: 'Execution', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: 't', exact: true })).toBeVisible();
  return { writes, requests };
}

test('supported controls use event intents and retain the key after an uncertain response', async ({ page }) => {
  const fixture = await openExecution(page, false, true);
  await page.getByLabel('Input operation').selectOption('steer');
  await page.getByLabel('New turn message').fill('Check the database first');
  await page.getByRole('button', { name: 'Send', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await page.getByRole('button', { name: 'Send', exact: true }).click();
  await expect(page.getByLabel('New turn message')).toHaveValue('');
  expect(fixture.writes).toHaveLength(2);
  expect(fixture.writes[0].key).toBeTruthy(); expect(fixture.writes[0].key).toBe(fixture.writes[1].key);
  expect(fixture.writes[0].path).toBe('/v1/sessions/s/events');
  expect(fixture.writes[0].event.managed_agents).toEqual({ intent: 'steer', turn_id: 't' });
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect.poll(() => fixture.writes.length).toBe(3);
  await expect(page.getByRole('alert')).toBeVisible();
  await page.getByRole('button', { name: 'Cancel', exact: true }).click();
  await expect.poll(() => fixture.writes.length).toBe(4);
  expect(fixture.writes[2].key).toBe(fixture.writes[3].key);
  expect(fixture.writes[2].event).toEqual({ type: 'user.interrupt', managed_agents: { intent: 'cancel', turn_id: 't' } });
  await expect(page.getByLabel('Attach a file')).toHaveCount(0);
  await expect(page.getByRole('button', { name: 'Resume', exact: true })).toHaveCount(0);
});

test('final output remains readable while the live observer is unavailable', async ({ page }) => {
  const fixture = await openExecution(page, true);
  await expect(page.getByText('Independent final result', { exact: true })).toBeVisible();
  expect(fixture.requests.some(path => path.includes('/snapshot'))).toBe(false);
  expect(fixture.requests.some(path => path.endsWith('/output'))).toBe(true);
  await page.getByRole('button', { name: 'Refresh result', exact: true }).click();
  await expect(page.getByText('Independent final result', { exact: true })).toBeVisible();
  await page.screenshot({ path: 'test-results/session-protocol-output.png', fullPage: true });
});


test('polls Turn completion and retries output projection while SSE remains unavailable', async ({ page }) => {
  const fixture = await openExecution(page, true, false, true);
  await expect(page.getByText('Independent final result', { exact: true })).toBeVisible({ timeout: 15000 });
  expect(fixture.writes).toHaveLength(0);
  expect(fixture.requests.filter(path => path.endsWith('/turns/t')).length).toBeGreaterThanOrEqual(2);
  expect(fixture.requests.filter(path => path.endsWith('/output')).length).toBeGreaterThanOrEqual(2);
});
