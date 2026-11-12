import { test, expect, type Page } from '@playwright/test';

// Exercise the production component and client with HTTP fixtures; no running backend or model.
async function mount(page: Page) {
  await page.goto('/');
  await page.evaluate(async () => {
    const { default: React } = await import('/node_modules/.vite/deps/react.js');
    const { default: ReactDOM } = await import('/node_modules/.vite/deps/react-dom_client.js');
    const { ServiceTurnPanel } = await import('/src/components/ServiceTurnPanel.tsx');
    const { ServiceClient } = await import('/src/api/serviceSessions.ts');
    const container = document.createElement('div');
    document.body.replaceChildren(container);
    ReactDOM.createRoot(container).render(React.createElement(ServiceTurnPanel, {
      client: new ServiceClient('session', '', () => ({ 'X-API-Key': 'fixture-key' })),
      humanClient: new ServiceClient('session', '', () => ({ Authorization: 'Bearer fixture-user' })),
      turnId: 'turn',
    }));
  });
}

function turnSnapshot(status: string, actions: Array<Record<string, unknown>> = []) {
  const page = (data: Array<Record<string, unknown>> = []) => ({ snapshot_id: 'fixture', as_of: 'before', data, next_cursor: null, has_more: false });
  return { snapshot_id: 'fixture', as_of: 'before', resources: {
    turns: page([{ type: 'turn.updated', data: { turn_id: 'turn', status } }]),
    required_actions: page(actions.map(data => ({ type: 'required_action.created', data }))),
    items: page(), tools: page(), steps: page(), artifacts: page(),
  }, usage: {} };
}

test('interrupted Managed Turn resumes with a new snapshot and SSE connection', async ({ page }) => {
  let resumed = false, snapshots = 0, streams = 0;
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    const json = (value: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(value) });
    if (path === '/api/v1/agent-sessions/session') return json({ runtimeKind: 'managed' });
    if (path.endsWith('/turns/turn/capabilities')) return json({ available_commands: resumed ? ['cancel'] : ['resume'], supported_commands: { resume: true, cancel: true } });
    if (path.endsWith('/turns/turn/resume')) {
      expect(route.request().method()).toBe('POST');
      expect(route.request().headers()['idempotency-key']).toBeTruthy();
      resumed = true;
      return json({ command_id: 'resume-command', command_status: 'accepted', turn_id: 'turn' });
    }
    if (path.endsWith('/turns/turn/snapshot')) {
      snapshots++;
      return json(turnSnapshot(resumed ? 'queued' : 'interrupted'));
    }
    if (path.endsWith('/turns/turn/events/stream')) {
      streams++;
      expect(route.request().headers()['last-event-id']).toBe('before');
      return route.fulfill({ contentType: 'text/event-stream', body: 'id: after\nevent: turn.completed\ndata: ' + JSON.stringify({
        schema_version: 1, id: 'end', cursor: 'after', turn_id: 'turn', type: 'turn.completed',
        created_at: 1, data: { status: 'completed' },
      }) + '\n\n' });
    }
    return json({ localDev: true, items: [] });
  });
  await mount(page);
  await expect(page.getByRole('button', { name: 'Resume', exact: true })).toBeEnabled();
  expect(streams).toBe(0);
  await page.getByRole('button', { name: 'Resume', exact: true }).click();
  await expect(page.getByText('completed', { exact: true }).first()).toBeVisible();
  expect(snapshots).toBe(2);
  expect(streams).toBe(1);
  expect(errors).toEqual([]);
});

test('Managed confirmation buttons send allow using the selected human identity', async ({ page }) => {
  let answer: unknown;
  const errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    const json = (value: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(value) });
    if (path === '/api/v1/agent-sessions/session') return json({ runtimeKind: 'managed' });
    if (path.endsWith('/turns/turn/capabilities')) return json({ available_commands: ['actions', 'cancel'], supported_commands: { actions: true, cancel: true } });
    if (path.endsWith('/turns/turn/snapshot')) return json(turnSnapshot('requires_action', [{ request_id: 'request', kind: 'confirmation' }]));
    if (path.endsWith('/turns/turn/events/stream')) return route.fulfill({ contentType: 'text/event-stream', body: ': heartbeat\n\n' });
    if (path.endsWith('/turns/turn/actions') && route.request().method() === 'POST') {
      answer = route.request().postDataJSON();
      expect(route.request().headers()['authorization']).toBe('Bearer fixture-user');
      expect(route.request().headers()['x-api-key']).toBeUndefined();
      return json({ command_id: 'approval-command', command_status: 'accepted', turn_id: 'turn' });
    }
    return json({ localDev: true, items: [] });
  });
  await mount(page);
  await page.getByRole('checkbox').check();
  await page.getByRole('button', { name: 'Approve', exact: true }).click();
  await expect(page.getByText('Request status', { exact: true })).toBeVisible();
  expect(answer).toEqual({ answers: [{ request_id: 'request', allow: true }] });
  expect(errors).toEqual([]);
});
