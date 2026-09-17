/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { test, expect, type Page } from '@playwright/test';

const channelId = 'weixin-test';
const detailPath = `/agent-center/entrypoints/${channelId}?tenant=default&namespace=engineering`;

async function setup(page: Page, options: { connected?: boolean; starting?: boolean; shared?: boolean; startFails?: boolean; expired?: boolean; completeFails?: boolean; verify?: boolean; slowPoll?: boolean; roles?: string[] } = {}) {
  const token = `test.${Buffer.from(JSON.stringify({ sub: 'alice', username: 'alice', roles: ['user'] })).toString('base64url')}.test`;
  await page.addInitScript(t => localStorage.setItem('claw_token', t), token);
  const state = { creates: 0, starts: 0, polls: 0, completes: 0, cancels: 0, maxPolls: 0, inflight: 0, verifies: [] as string[], connected: !!options.connected, starting: !!options.starting, linked: false, disabled: !options.connected, disconnected: false, flowStatus: 'WAITING_SCAN', errors: [] as string[], paths: [] as string[] };
  page.on('pageerror', e => state.errors.push(e.message));
  const flow = () => ({ flowId: 'flow-' + state.starts, status: state.flowStatus, expiresAt: Date.now() + (options.expired && state.starts === 1 ? 700 : 120000), pollAfterMs: 2000 });
  const channel = () => ({ channelId, type: 'weixin', dmScope: 'PER_PEER', defaultAgentId: 'agent-a', disabled: state.disabled, started: state.connected && !state.disabled, properties: {}, bindings: [] });
  await page.route('**/api/**', async route => {
    const req = route.request(), path = new URL(req.url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    const json = (body: unknown, status = 200) => route.fulfill({ status, contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: 'engineering', mode: 'multi', selectorVisible: true, namespaces: [{ tenant: 'default', name: 'engineering', displayName: options.shared ? 'Engineering' : 'My workspace', kind: options.shared ? 'shared' : 'personal', roles: options.roles || ['admin', 'member', 'developer', 'operator'] }] });
    if (path === '/api/auth/me') return json({ userId: 'alice', username: 'alice', roles: ['user'] });
    if (path === '/api/channels/types') return json([{ type: 'weixin', label: 'Personal Weixin', transport: 'polling', fields: [], hint: 'Create the channel, then authorize Personal Weixin by QR code.' }]);
    if (path === '/api/channels') {
      if (req.method() === 'POST') { state.creates++; expect(req.postDataJSON()).toMatchObject({ channelId, type: 'weixin', defaultAgentId: 'agent-a', properties: {} }); return json(channel()); }
      return json([channel()]);
    }
    if (path === '/api/channels/' + channelId) return json(channel());
    if (path.includes('/weixin/')) {
      expect(req.headers()['x-agentscope-namespace']).toBe('engineering');
      state.paths.push(path);
      if (path.endsWith('/status')) return json({ connected: state.connected, disabled: state.disabled, accountId: state.connected ? 'bot-account-123' : null, status: state.connected ? state.disabled ? 'DISABLED' : state.starting ? 'STARTING' : 'RUNNING' : state.disconnected ? 'DISCONNECTED' : 'PENDING_LINK' });
      if (path.endsWith('/link-flows') || path.endsWith('/relink')) {
        state.starts++; state.flowStatus = 'WAITING_SCAN';
        if (options.startFails && state.starts === 1) return json({ errorCode: 'weixin_provider_unavailable' }, 502);
        return json({ ...flow(), qrcodeImage: 'https://weixin.qq.com/authorize?ticket=test-qr' });
      }
      if (path.endsWith('/poll')) {
        state.polls++; state.inflight++; state.maxPolls = Math.max(state.maxPolls, state.inflight);
        if (options.slowPoll) await new Promise(resolve => setTimeout(resolve, 3200));
        state.inflight--;
        if (options.slowPoll && state.polls === 1) return json({ errorCode: 'weixin_retry_later' }, 429);
        if (!options.slowPoll) state.flowStatus = options.verify ? 'NEED_VERIFY_CODE' : 'AUTHORIZED';
        return json(flow());
      }
      if (path.endsWith('/verify')) {
        state.verifies.push(req.postDataJSON().verifyCode); state.flowStatus = 'AUTHORIZED'; return json(flow());
      }
      if (path.endsWith('/complete')) {
        state.completes++;
        if (options.completeFails) return json({ errorCode: 'weixin_account_in_use' }, 409);
        state.connected = true; state.disabled = false; state.flowStatus = 'COMPLETED'; return json(flow());
      }
      if (path.endsWith('/cancel')) { state.cancels++; state.flowStatus = 'CANCELLED'; return json(flow()); }
      if (path.endsWith('/disconnect')) { state.connected = false; state.disabled = true; state.disconnected = true; return json({ status: 'DISCONNECTED', connected: false }); }
      return json(flow());
    }
    if (path.endsWith('/enable') || path.endsWith('/disable')) { state.disabled = path.endsWith('/disable'); return route.fulfill({ status: 204 }); }
    if (path.endsWith('/activity')) return json({ identities: state.linked ? [{ accountId: 'bot', senderId: 'alice' }] : [], links: [], deliveries: [] });
    if (path.endsWith('/collaboration')) return json({ enabled: false, defaultTarget: { targetType: 'agent', targetRef: 'agent-a' }, routes: [], allowGroupWork: false, notifyEvents: [], version: 1 });
    if (path.endsWith('/pairing')) return json({ command: '/bind test-only-code', expiresInSeconds: 600 });
    if (path === '/api/v1/agents') return json({ items: [{ id: 'agent-a', name: 'Test Agent', displayName: 'Test Agent', status: 'active', agentKey: 'test', runtimeKind: 'managed' }] });
    if (path === '/api/v1/inbox/summary') return json({ summary: { unread: 0, attentionTotal: 0, pendingApprovals: 0 } });
    return json({ items: [], events: [] });
  });
  return state;
}

test('create, display a real QR bitmap, authorize, complete, pause, resume and disconnect', async ({ page }) => {
  const state = await setup(page);
  await page.goto('/agent-center/entrypoints?tenant=default&namespace=engineering');
  await page.getByRole('button', { name: /New channel/ }).click();
  await page.getByLabel('Channel id', { exact: true }).fill(channelId);
  await page.getByRole('button', { name: '创建并连接微信' }).click();
  await expect(page.getByText('请选择接收微信私聊的默认 Agent。')).toBeVisible();
  expect(state.creates).toBe(0);
  await page.getByRole('combobox', { name: 'Channel default Agent', exact: true }).click();
  await page.getByRole('option', { name: /Test Agent/ }).click();
  await page.getByRole('button', { name: '创建并连接微信' }).click();
  await expect(page.getByRole('img', { name: '微信授权二维码' })).toBeVisible();
  expect(state.creates).toBe(1); expect(state.starts).toBe(1);
  const pixels = await page.getByRole('img', { name: '微信授权二维码' }).evaluate((img: HTMLImageElement) => {
    const canvas = document.createElement('canvas'); canvas.width = 264; canvas.height = 264;
    const ctx = canvas.getContext('2d')!; ctx.drawImage(img, 0, 0, 264, 264);
    const data = ctx.getImageData(0, 0, 264, 264).data;
    let dark = 0; for (let i = 0; i < data.length; i += 4) if (data[i] < 100) dark++;
    return dark;
  });
  expect(pixels).toBeGreaterThan(5000);
  await page.screenshot({ path: '/tmp/weixin-ui-20260916/desktop-qr.png', fullPage: true });
  await expect(page.getByRole('button', { name: '完成连接', exact: true })).toBeVisible();
  expect(state.completes).toBe(0);
  await page.getByRole('button', { name: '完成连接', exact: true }).click();
  await expect(page.getByRole('button', { name: '暂停接收' })).toBeVisible();
  await expect(page.getByText('bot-account-123', { exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '生成绑定码' })).toBeEnabled();
  await page.getByRole('button', { name: '生成绑定码' }).click();
  await expect(page.getByText('/bind test-only-code')).toBeVisible();
  state.linked = true;
  await page.getByRole('button', { name: '刷新绑定状态' }).click();
  await expect(page.getByText('微信聊天账号已关联，可以向机器人发送消息。')).toBeVisible();
  await expect(page.getByRole('heading', { name: '工作接待', exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '暂停接收' }).click();
  await expect(page.getByRole('button', { name: '恢复接收' })).toBeVisible();
  await page.getByRole('button', { name: '恢复接收' }).click();
  await page.getByRole('button', { name: '断开连接', exact: true }).click();
  await page.getByRole('button', { name: '确认断开' }).click();
  await expect(page.getByRole('button', { name: '扫码连接微信', exact: true })).toBeVisible();
  expect(state.errors).toEqual([]);
});

test('verification code and refresh resume never persist QR or code and never start a second flow', async ({ page }) => {
  const state = await setup(page, { verify: true });
  await page.goto(detailPath);
  await page.getByRole('button', { name: '扫码连接微信', exact: true }).click();
  await expect(page.getByLabel('微信验证码', { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByLabel('微信验证码', { exact: true })).toBeVisible();
  expect(state.starts).toBe(1);
  await page.getByLabel('微信验证码', { exact: true }).fill('928314');
  await page.getByRole('button', { name: '验证并继续' }).click();
  await expect(page.getByRole('button', { name: '完成连接', exact: true })).toBeVisible();
  expect(state.verifies).toEqual(['928314']);
  const storage = await page.evaluate(() => JSON.stringify({ ...sessionStorage }));
  expect(storage).toContain('flow-1'); expect(storage).not.toContain('928314'); expect(storage).not.toContain('ticket');
  await page.getByRole('button', { name: '取消授权' }).click();
  expect(state.cancels).toBe(1);
  expect(await page.evaluate(() => JSON.stringify({ ...sessionStorage }))).not.toContain('flow-1');
  expect(state.errors).toEqual([]);
});

test('expired QR can be regenerated and fits a mobile viewport', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  const state = await setup(page, { expired: true, slowPoll: true });
  await page.goto(detailPath);
  await page.getByRole('button', { name: '扫码连接微信', exact: true }).click();
  await expect(page.getByText('二维码已过期', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '扫码连接微信', exact: true }).click();
  await expect(page.getByRole('img', { name: '微信授权二维码' })).toBeVisible();
  expect(state.starts).toBe(2);
  await page.screenshot({ path: '/tmp/weixin-ui-20260916/mobile-qr.png', fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  const panel = page.getByRole('region', { name: '微信连接', exact: true });
  const bounds = await panel.boundingBox(); expect(bounds!.width).toBeLessThan(390);
  expect(state.errors).toEqual([]);
});

test('failed QR request keeps the created Channel and retries from detail', async ({ page }) => {
  const state = await setup(page, { startFails: true });
  await page.goto('/agent-center/entrypoints?tenant=default&namespace=engineering');
  await page.getByRole('button', { name: /New channel/ }).click();
  await page.getByLabel('Channel id', { exact: true }).fill(channelId);
  await page.getByRole('combobox', { name: 'Channel default Agent', exact: true }).click();
  await page.getByRole('option', { name: /Test Agent/ }).click();
  await page.getByRole('button', { name: '创建并连接微信' }).click();
  await expect(page.getByRole('alert')).toContainText('暂时无法连接微信');
  await page.getByRole('button', { name: '扫码连接微信', exact: true }).click();
  await expect(page.getByRole('img', { name: '微信授权二维码' })).toBeVisible();
  expect(state.creates).toBe(1); expect(state.starts).toBe(2);
});

test('account already linked elsewhere keeps completion failure visible', async ({ page }) => {
  const state = await setup(page, { completeFails: true });
  await page.goto(detailPath);
  await page.getByRole('button', { name: '扫码连接微信', exact: true }).click();
  await expect(page.getByRole('button', { name: '完成连接', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '完成连接', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('已绑定其他 Channel');
  expect(state.connected).toBe(false);
  await expect(page.getByRole('button', { name: '生成绑定码' })).toBeDisabled();
});

test('long polls and rate limits stay serial, and cancellation stops the loop', async ({ page }) => {
  const state = await setup(page, { slowPoll: true });
  await page.goto(detailPath);
  await page.getByRole('button', { name: '扫码连接微信', exact: true }).click();
  await expect.poll(() => state.polls, { timeout: 15000 }).toBeGreaterThanOrEqual(2);
  expect(state.maxPolls).toBe(1);
  await page.getByRole('button', { name: '取消授权' }).click();
  const count = state.polls;
  await page.waitForTimeout(5500);
  expect(state.polls).toBe(count); expect(state.cancels).toBe(1);
});

test('connected channel never starts authorization by itself; relink retains current connection', async ({ page }) => {
  const state = await setup(page, { connected: true });
  await page.goto(detailPath);
  await expect(page.getByRole('button', { name: '重新扫码授权' })).toBeVisible();
  expect(state.starts).toBe(0);
  await page.getByRole('button', { name: '重新扫码授权' }).click();
  await expect(page.getByRole('img', { name: '微信授权二维码' })).toBeVisible();
  expect(state.paths.some(path => path.endsWith('/relink'))).toBe(true);
  await page.getByRole('button', { name: '取消授权' }).click();
  expect(state.connected).toBe(true);
});

test('Agent entry preselects Weixin and its default Agent', async ({ page }) => {
  const state = await setup(page);
  await page.goto('/agent-center/entrypoints?create=weixin&agentId=agent-a&tenant=default&namespace=engineering');
  await expect(page.getByRole('combobox', { name: 'Platform', exact: true })).toHaveValue('weixin');
  await expect(page.getByRole('combobox', { name: 'Channel default Agent', exact: true })).toHaveValue('Test Agent');
  await page.getByLabel('Channel id', { exact: true }).fill(channelId);
  await page.getByRole('button', { name: '创建并连接微信' }).click();
  await expect(page.getByRole('img', { name: '微信授权二维码' })).toBeVisible();
  expect(state.creates).toBe(1);
});

test('pairing waits for runtime readiness and shared scope shows its limitation', async ({ page }) => {
  const state = await setup(page, { connected: true, starting: true, shared: true });
  await page.goto(detailPath);
  await expect(page.getByText('当前为共享空间，普通微信私聊仅支持个人空间中已绑定的账号。请在个人空间中创建 Channel 并选择自己的 Agent。')).toBeVisible();
  await expect(page.getByRole('button', { name: '生成绑定码' })).toBeDisabled();
  state.starting = false;
  await page.getByRole('button', { name: '刷新连接状态' }).click();
  await expect(page.getByRole('button', { name: '生成绑定码' })).toBeEnabled();
  expect(state.starts).toBe(0);
});
