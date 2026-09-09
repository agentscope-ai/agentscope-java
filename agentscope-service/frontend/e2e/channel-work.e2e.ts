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

async function setup(page: Page, roles: string[]) {
  const token = `test.${Buffer.from(JSON.stringify({ sub: 'alice', username: 'alice', roles: ['user'] })).toString('base64url')}.test`;
  await page.addInitScript(t => localStorage.setItem('claw_token', t), token);
  const settings = { enabled: true, defaultTarget: { targetType: 'agent', targetRef: 'agent-a' }, routes: [], allowGroupWork: false, notifyEvents: ['result', 'status'], version: 1 };
  let saved: Record<string, unknown> | undefined;
  let retried = false;
  const errors: string[] = [];
  page.on('pageerror', e => errors.push(e.message));
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    const json = (body: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: 'engineering', mode: 'multi', selectorVisible: true, namespaces: [{ tenant: 'default', name: 'engineering', displayName: 'Engineering', kind: 'shared', roles }] });
    if (path === '/api/auth/me') return json({ userId: 'alice', username: 'alice', roles: ['user'] });
    if (path === '/api/channels/channel-a/collaboration') {
      expect(req.headers()['x-agentscope-namespace']).toBe('engineering');
      if (req.method() === 'PUT') { saved = req.postDataJSON(); return json({ ...saved, version: 2 }); }
      return json(settings);
    }
    if (path === '/api/channels/channel-a/activity') return json({ identities: [{ accountId: 'org', senderId: 'human' }], links: [], deliveries: [{ id: 'delivery-a', state: retried ? 'pending' : 'failed', attempts: 10, providerMessageId: '', lastError: 'ProviderRejected' }] });
    if (path === '/api/channels/channel-a/pairing') return json({ command: '/bind one-time-test-code', expiresInSeconds: 600 });
    if (path.endsWith('/deliveries/delivery-a/retry')) { retried = true; return route.fulfill({ status: 204 }); }
    if (path === '/api/channels/channel-a') return json({ channelId: 'channel-a', type: 'feishu', dmScope: 'PER_PEER', defaultAgentId: '', disabled: false, started: true, properties: {}, bindings: [] });
    if (path === '/api/channels/types') return json([{ type: 'feishu', label: 'Feishu', transport: 'callback', fields: [] }]);
    if (path === '/api/v1/agents') return json({ items: [{ id: 'agent-a', name: 'Release analyst', status: 'active', agentKey: 'release', runtimeKind: 'managed' }] });
    if (path === '/api/v1/teams') return json({ items: [{ id: 'team-a', name: 'Release response team', status: 'active' }] });
    if (path === '/api/v1/inbox/summary') return json({ summary: { unread: 0, attentionTotal: 0, pendingApprovals: 0 } });
    return json({ items: [], events: [] });
  });
  return { saved: () => saved, retried: () => retried, errors };
}

test('namespace developer configures Team reception and independent result subscriptions', async ({ page }) => {
  const state = await setup(page, ['developer']);
  await page.goto('/agent-center/entrypoints/channel-a?tenant=default&namespace=engineering');
  await expect(page.getByRole('heading', { name: '工作接待', exact: true })).toBeVisible();
  await page.getByLabel('默认接待对象类型').selectOption('team');
  await page.getByLabel('默认接待对象', { exact: true }).selectOption('team-a');
  await page.getByLabel('状态变化', { exact: true }).uncheck();
  await page.getByRole('button', { name: '保存工作配置' }).click();
  await expect.poll(state.saved).toMatchObject({ defaultTarget: { targetType: 'team', targetRef: 'team-a' }, notifyEvents: ['result'], version: 1 });
  await page.getByRole('button', { name: '重试', exact: true }).click();
  await expect.poll(state.retried).toBe(true);
  await expect(page.getByRole('cell', { name: /^等待发送/ })).toBeVisible();
  await page.getByRole('heading', { name: 'channel-a', exact: true }).scrollIntoViewIfNeeded();
  await page.screenshot({ path: '/tmp/agentscope-channel-work-ui.png', fullPage: true });
  expect(state.errors).toEqual([]);
});

test('namespace member pairs own external account without configuration privileges', async ({ page }) => {
  const state = await setup(page, ['member']);
  await page.goto('/agent-center/entrypoints/channel-a?tenant=default&namespace=engineering');
  await expect(page.getByRole('heading', { name: '我的外部账号' })).toBeVisible();
  await expect(page.getByRole('button', { name: '保存工作配置' })).toHaveCount(0);
  await expect(page.getByLabel('默认接待对象类型')).toBeDisabled();
  await page.getByRole('button', { name: '生成绑定码' }).click();
  await expect(page.getByText('/bind one-time-test-code')).toBeVisible();
  expect(state.errors).toEqual([]);
});
