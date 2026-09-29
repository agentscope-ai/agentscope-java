import { test, expect } from '@playwright/test';

for (const method of ['context menu', 'keyboard']) {
  test(`Chat deletion and restoration through ${method}`, async ({ page }) => {
    const token = `test.${Buffer.from(JSON.stringify({ username: 'alice', roles: ['admin'] })).toString('base64url')}.test`;
    await page.addInitScript(t => localStorage.setItem('claw_token', t), token);
    const base = { tenant: 'default', namespace: 'default', creatorRef: 'alice', agentId: 'worker', agentName: 'Worker', sessionId: 'session', runtimeSessionId: 'runtime', pinned: false, version: 1, status: 'active', createdAt: new Date().toISOString(), updatedAt: new Date().toISOString() };
    let chats = [{ ...base, id: 'first', title: 'First conversation' }, { ...base, id: 'second', title: 'Second conversation' }];
    let deletes = 0;
    await page.route('**/api/**', async route => {
      const req = route.request(), url = new URL(req.url()), path = url.pathname;
      if (!path.startsWith('/api/')) return route.continue();
      const json = (data: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) });
      if (path === '/api/auth/me') return json({ username: 'alice', roles: ['admin'], isAdmin: true });
      if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: 'default', mode: 'single', selectorVisible: false });
      if (path.endsWith('/events/stream')) return route.fulfill({ contentType: 'text/event-stream', body: '' });
      if (path.endsWith('/events')) return json({ events: [] });
      if (path === '/api/v1/chats') return json({ items: chats.filter(c => c.status === (url.searchParams.has('deleted') ? 'deleted' : url.searchParams.has('archived') ? 'archived' : 'active')) });
      const id = path.split('/').at(-1);
      if (path.startsWith('/api/v1/chats/') && ['DELETE', 'PATCH'].includes(req.method())) {
        const status = req.method() === 'DELETE' ? 'deleted' : req.postDataJSON().status;
        if (req.method() === 'DELETE') deletes++;
        chats = chats.map(c => c.id === id ? { ...c, status, version: c.version + 1 } : c);
        return json({ chat: chats.find(c => c.id === id) });
      }
      return json({ items: [], preferences: {} });
    });
    await page.goto('/work/chat?chat=first');
    const first = page.getByRole('button', { name: /First conversation Worker/ });
    await expect(first).toBeVisible();
    if (method === 'context menu') await first.click({ button: 'right' });
    else { await first.focus(); await page.keyboard.press('Shift+F10'); }
    await page.getByRole('menuitem', { name: 'Delete chat' }).click();
    await expect(first).toHaveCount(0);
    await expect(page.getByRole('heading', { name: 'Second conversation' })).toBeVisible();
    await page.reload();
    await expect(first).toHaveCount(0);
    await page.getByRole('button', { name: 'deleted', exact: true }).click();
    await expect(first).toBeVisible();
    await page.getByRole('button', { name: 'More actions for First conversation' }).click();
    await page.getByRole('menuitem', { name: 'Restore chat' }).click();
    await expect(first).toHaveCount(0);
    await page.getByRole('button', { name: 'active', exact: true }).click();
    await expect(first).toBeVisible();
    expect(deletes).toBe(1);
  });
}
