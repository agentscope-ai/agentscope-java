import { test, expect, type Page, type Route } from '@playwright/test';

async function fixture(page: Page, handler: (path: string, route: Route, json: (data: unknown) => Promise<void>) => Promise<boolean | void>, roles: string[] = ['admin', 'developer', 'operator', 'member']) {
  const token = `test.${Buffer.from(JSON.stringify({ username: 'alice', roles })).toString('base64url')}.test`;
  await page.addInitScript(t => localStorage.setItem('claw_token', t), token);
  page.on('pageerror', error => { throw error; });
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (!path.startsWith('/api/')) return route.continue();
    const json = (data: unknown) => route.fulfill({ contentType: 'application/json', body: JSON.stringify(data) });
    if (path === '/api/auth/me') return json({ username: 'alice', roles, isAdmin: roles.includes('admin') });
    if (path === '/api/v1/me/scope') return json({ tenant: 'default', namespace: 'default', mode: 'single', selectorVisible: false, namespaces: [{ tenant: 'default', name: 'default', roles }] });
    if (await handler(path, route, json)) return;
    if (path.endsWith('/events/stream')) return route.fulfill({ contentType: 'text/event-stream', body: '' });
    return json({ items: [], events: [], turns: [], commands: [], summary: {}, preferences: {} });
  });
}

test('Workspace discovers MCP before editing and combines installed skills with marketplace', async ({ page }) => {
  let tools = { tools: [], mcpServers: [] } as any;
  let saves = 0;
  let installed: any[] = [];
  await fixture(page, async (path, route, json) => {
    if (path === '/api/workspaces/pack') { await json({ id: 'pack', name: 'Review workspace', version: 1 }); return true; }
    if (path.endsWith('/file')) { await json({ content: 'Instructions' }); return true; }
    if (path.endsWith('/subagents')) { await json([]); return true; }
    if (path.endsWith('/tools')) { if (route.request().method() === 'PUT') { tools = route.request().postDataJSON(); saves++; } await json(tools); return true; }
    if (path === '/api/toolsets/builtin') { await json([{ id: 'read_file', group: 'files', description: 'Read files' }]); return true; }
    if (path === '/api/toolsets/mcp-catalog') { await json([{ id: 'docs', name: 'Documentation tools', transport: 'http', url: 'https://example.test/mcp' }]); return true; }
    if (path === '/api/workspaces/pack/skills') { await json(installed); return true; }
    if (path === '/api/marketplaces') { await json([{ id: 'market', name: 'Team skills', type: 'git' }]); return true; }
    if (path === '/api/marketplaces/market/skills') { await json([{ name: 'Review', dirName: 'review' }]); return true; }
    if (path.endsWith('/marketplace-install')) { installed = [{ name: 'Review', dirName: 'review', origin: 'marketplace' }]; await json({}); return true; }
    return false;
  });
  await page.goto('/agent-center/workspaces/pack?tab=tools');
  const catalog = page.getByRole('heading', { name: 'MCP catalog' });
  const connections = page.getByRole('heading', { name: 'MCP connections' });
  expect((await catalog.boundingBox())!.y).toBeLessThan((await connections.boundingBox())!.y);
  await page.getByRole('button', { name: 'Configure connection', exact: true }).click();
  await expect(page.getByLabel('Endpoint URL')).toHaveValue('https://example.test/mcp');
  expect(saves).toBe(0);
  await page.getByRole('button', { name: 'Save connection' }).click();
  await expect.poll(() => saves).toBe(1);
  expect(tools.mcpServers[0].name).toBe('docs');
  expect(tools.tools[0].defaultConfig.enabled).toBe(false);
  await expect(page.getByRole('button', { name: 'Configured', exact: true })).toBeDisabled();
  await page.goto('/agent-center/workspaces/pack?tab=marketplace');
  await expect(page).toHaveURL(/tab=skills/);
  await expect(page.getByRole('button', { name: 'Marketplace', exact: true })).toHaveCount(0);
  await page.getByLabel('Marketplace source').selectOption('market');
  await page.getByRole('button', { name: 'Install', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Already installed' })).toBeDisabled();
  await page.getByRole('button', { name: 'Installed skills' }).click();
  await expect(page.getByRole('button', { name: /Review Marketplace/ })).toBeVisible();
  await page.screenshot({ path: '/tmp/agentscope-skills-unified.png', fullPage: true });
});

test('Task details promote result, recorded input and attempt history above diagnostics', async ({ page }) => {
  const createdAt = '2026-09-09T00:00:00Z';
  await fixture(page, async (path, _route, json) => {
    if (path === '/api/v1/agent-tasks/task') { await json({ task: { id: 'task', agentId: 'worker', issueId: 'issue', orchestrationRunId: 'run', runNodeId: 'node', triggerType: 'mention', status: 'completed', createdAt, version: 1, originator: { type: 'human', ref: 'alice' }, runtimeBinding: { internalMarker: 'raw-binding-marker' }, result: { output: '# Review complete\n\nAll checks passed.' }, inputs: [{ id: 'input', commentId: 'comment', commentVersion: 1, sequence: 1, state: 'processed' }] }, inputSummaries: [{ inputId: 'input', state: 'recorded', content: 'Review the release changes.' }] }); return true; }
    if (path === '/api/v1/execution-attempts') { await json({ attempts: [{ id: 'attempt', attempt: 1, state: 'succeeded', createdAt, startedAt: createdAt, completedAt: '2026-09-09T00:00:10Z', backendKind: 'hosted-runtime', sessionRef: 'session', result: { output: '# Review complete\n\nAll checks passed.' } }] }); return true; }
    return false;
  });
  await page.goto('/work/executions/tasks/task');
  await expect(page.getByRole('heading', { name: 'Review complete' })).toBeVisible();
  await expect(page.getByText('Review the release changes.')).toBeVisible();
  await expect(page.getByText('All checks passed.', { exact: true })).toHaveCount(1);
  await expect(page.getByText('raw-binding-marker', { exact: false })).not.toBeVisible();
  await expect(page.getByRole('link', { name: 'View conversation and events' })).toHaveAttribute('href', '/work/sessions/session');
  await page.screenshot({ path: '/tmp/agentscope-task-result.png', fullPage: true });
});

test('Hosted Session with no telemetry reports missing data and its actual provider', async ({ page }) => {
  await fixture(page, async (path, _route, json) => {
    if (path === '/api/v1/sessions/session') { await json({ id: 'session', sessionId: 'runtime-session', agentName: 'Review agent', phase: 'idle', capabilities: [], runtime: { kind: 'hosted-runtime', provider: 'codex', source: 'execution_attempt', profile: 'Reviewer', pool: 'Local' } }); return true; }
    return false;
  });
  await page.goto('/work/sessions/session');
  await expect(page.getByText('Hosted · codex', { exact: true })).toBeVisible();
  const usage = page.getByText('Session token usage', { exact: true }).locator('../..');
  await expect(usage.getByText('Not reported', { exact: true })).toBeVisible();
  await expect(page.getByText('unknown', { exact: true })).toHaveCount(0);
  await expect(page.getByText('No instance bound')).toHaveCount(0);
  await expect(page.getByText('This runtime does not provide a context inspection capability.')).toBeVisible();
});

test('Team Agent picker is opaque, scrolls with keyboard and defaults workers to automatic runtime', async ({ page }) => {
  let submitted: any;
  const agents = Array.from({ length: 22 }, (_, index) => ({ id: `worker-${index}`, agentKey: `worker-${index}`, displayName: `Worker ${String(index).padStart(2, '0')} with a long descriptive name`, status: 'active' }));
  await fixture(page, async (path, route, json) => {
    if (path === '/api/v1/teams/team/overview') { await json({ team: { id: 'team', name: 'Review team', status: 'active', leaderAgentId: 'lead', version: 1, members: [], policy: {} }, overview: { readiness: 'ready', reason: 'Ready', members: [], endpoints: { published: 0 } } }); return true; }
    if (path === '/api/v1/agents') { await json({ items: agents }); return true; }
    if (path.endsWith('/bindings')) { await json({ items: [] }); return true; }
    if (path === '/api/v1/teams/team/members') { submitted = route.request().postDataJSON(); await json({ member: { id: 'member', ...submitted } }); return true; }
    return false;
  });
  await page.setViewportSize({ width: 620, height: 900 });
  await page.goto('/agent-center/teams/team?tab=orchestration');
  await page.getByRole('button', { name: 'Add worker', exact: true }).click();
  await page.getByPlaceholder('Unique role, for example researcher').fill('reviewer');
  const picker = page.getByRole('combobox', { name: 'Team member Agent' });
  await picker.click();
  const options = page.getByRole('listbox');
  await expect(options).toHaveCSS('background-color', 'rgb(255, 255, 255)');
  for (let index = 0; index < 16; index++) await picker.press('ArrowDown');
  const activeId = await picker.getAttribute('aria-activedescendant');
  const active = page.locator(`[id="${activeId}"]`);
  await expect(active).toBeInViewport();
  const box = (await options.boundingBox())!;
  expect(box.x).toBeGreaterThanOrEqual(0);
  expect(box.x + box.width).toBeLessThanOrEqual(620);
  await picker.press('Enter');
  await expect(picker).toHaveValue(/Worker 16/);
  await expect(page.getByLabel('Runtime selection', { exact: true })).not.toBeVisible();
  await page.getByRole('button', { name: 'Add member', exact: true }).click();
  await expect.poll(() => submitted?.agentId).toBe('worker-16');
  expect(submitted.runtimeBindingPolicy).toBeUndefined();
  await expect(page.getByRole('heading', { name: 'Channel 工作接待', exact: true })).toHaveCount(0);
});

test('Vault associations distinguish same-name resources by stable identity', async ({ page }) => {
  await fixture(page, async (path, _route, json) => {
    if (path === '/api/vaults') { await json([{ id: 'vault-one', displayName: 'Shared connection' }, { id: 'vault-two', displayName: 'Shared connection' }]); return true; }
    if (path.endsWith('/credentials')) { await json([]); return true; }
    if (path.endsWith('/resources')) { await json({ items: [{ resource: { kind: 'agent', id: 'agent-one', name: 'Review agent', dependencies: ['vault:vault-one'] } }, { resource: { kind: 'agent', id: 'agent-two', name: 'Review agent', dependencies: ['vault:vault-two'] } }] }); return true; }
    return false;
  });
  await page.goto('/agent-center/vaults');
  await page.getByText('vault-one', { exact: true }).click();
  const associations = page.getByRole('region', { name: 'Resource associations' });
  await expect(associations.getByRole('link', { name: 'Review agent' })).toHaveAttribute('href', '/agent-center/agents/agent-one/definition');
  await page.getByText('vault-two', { exact: true }).click();
  await expect(associations.getByRole('link', { name: 'Review agent' })).toHaveAttribute('href', '/agent-center/agents/agent-two/definition');
  await expect(associations.getByText('agent · agent-tw', { exact: true })).toBeVisible();
});

test('Measured zero telemetry remains different from missing telemetry', async ({ page }) => {
  await fixture(page, async (path, _route, json) => {
    if (path === '/api/v1/sessions/session') { await json({ id: 'session', sessionId: 'runtime-session', phase: 'idle', capabilities: [], runtime: { kind: 'managed', source: 'session_report' }, snapshot: { tokenUsageReported: true, contextPressureReported: true, capturedAt: '2026-09-09T00:00:00Z' } }); return true; }
    return false;
  });
  await page.goto('/work/sessions/session');
  const usage = page.getByText('Session token usage', { exact: true }).locator('../..');
  await expect(usage.getByText('0', { exact: true })).toBeVisible();
  await expect(usage.getByText('Input 0 · output 0', { exact: true })).toBeVisible();
  await expect(page.getByText('0%', { exact: true })).toBeVisible();
});

test('Editing a worker preserves its pinned policy until explicitly cleared', async ({ page }) => {
  const policy = { selectionMode: 'ordered', fallbackMode: 'disabled', candidates: [{ binding: { agentId: 'worker', bindingId: 'binding', kind: 'hosted-runtime' }, requiredCapabilities: { approvals: true } }] };
  const team = { id: 'team', name: 'Review team', status: 'active', leaderAgentId: 'lead', version: 1, members: [{ id: 'member', agentId: 'worker', role: 'reviewer', instructions: '', runtimeBindingPolicy: policy as any }], policy: {} };
  const updates: any[] = [];
  await fixture(page, async (path, route, json) => {
    if (path === '/api/v1/teams/team/overview') { await json({ team, overview: { readiness: 'ready', reason: 'Ready', members: [], endpoints: { published: 0 } } }); return true; }
    if (path === '/api/v1/teams/team/members/member') {
      const update = route.request().postDataJSON(); updates.push(update);
      expect(update.expectedTeamVersion).toBe(team.version);
      Object.assign(team.members[0], update); team.version++;
      await json({ member: team.members[0] }); return true;
    }
    return false;
  });
  await page.goto('/agent-center/teams/team?tab=orchestration');
  await page.getByRole('button', { name: 'Edit', exact: true }).last().click();
  await page.getByLabel('Worker role', { exact: true }).fill('senior-reviewer');
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect.poll(() => updates.length).toBe(1);
  expect(updates[0].runtimeBindingPolicy).toEqual(policy);
  await expect(page.getByText(/Pinned runtime selection/)).toBeVisible();
  await page.getByRole('button', { name: 'Edit', exact: true }).last().click();
  await page.getByLabel('Use the Agent’s automatic runtime selection', { exact: true }).check();
  await page.getByRole('button', { name: 'Save', exact: true }).click();
  await expect.poll(() => updates.length).toBe(2);
  expect(updates[1].runtimeBindingPolicy).toBeNull();
  await expect(page.getByText(/Pinned runtime selection/)).toHaveCount(0);
});

test('External agents show reported framework versions and support framework search', async ({ page }) => {
  await fixture(page, async (path, _route, json) => {
    if (path === '/api/v1/agents') { await json({ items: ['a', 'b'].map(id => ({ id, agentKey: id, displayName: 'External ' + id, status: 'active' })) }); return true; }
    if (path.endsWith('/bindings')) { await json({ items: [{ kind: 'external-application', enabled: true }] }); return true; }
    if (path === '/api/v1/agent-instances') { await json({ items: [
      { agentId: 'a', framework: 'agentscope-java', frameworkVersion: '1.2', health: 'healthy' },
      { agentId: 'b', framework: 'deepagents', frameworkVersion: '0.8', health: 'unhealthy' },
    ] }); return true; }
    return false;
  });
  await page.goto('/agent-center/agents');
  await expect(page.getByText('AgentScope Java 1.2', { exact: true })).toBeVisible();
  await expect(page.getByText('DeepAgents 0.8 · offline', { exact: true })).toBeVisible();
  await page.getByPlaceholder('Search agents').fill('deepagents');
  await expect(page.getByText('External b', { exact: true })).toBeVisible();
  await expect(page.getByText('External a', { exact: true })).toHaveCount(0);
});

test('New Managed session can create a default environment without manual selection', async ({ page }) => {
  let submitted: any;
  let environmentCreated = false;
  await fixture(page, async (path, route, json) => {
    if (path === '/api/v1/agents') { await json({ items: [{ id: 'managed', displayName: 'Managed', status: 'active' }] }); return true; }
    if (path === '/api/v1/agents/managed') { await json({ agent: { id: 'managed', displayName: 'Managed', status: 'active', definition: {} } }); return true; }
    if (path === '/api/agents/managed') { await json({ id: 'managed', name: 'Managed' }); return true; }
    if (path.endsWith('/bindings')) { await json({ items: [{ kind: 'managed', enabled: true }] }); return true; }
    if (path === '/api/environments') {
      if (route.request().method() === 'POST') { environmentCreated = true; await json({ id: 'auto-env', name: 'default-local', type: 'local' }); }
      else await json([]);
      return true;
    }
    if (['/api/vaults', '/api/memory-stores', '/api/files'].includes(path)) { await json([]); return true; }
    if (path === '/api/sessions' && route.request().method() === 'POST') { submitted = route.request().postDataJSON(); await json({ id: 'created', agentId: 'managed', phase: 'idle' }); return true; }
    if (path === '/api/sessions/created') { await json({ id: 'created', agentId: 'managed', phase: 'idle' }); return true; }
    return false;
  });
  await page.goto('/managed/sessions/new?agentId=managed');
  await page.getByRole('button', { name: 'Create session', exact: true }).click();
  await expect.poll(() => submitted?.environmentId).toBe('auto-env');
  expect(environmentCreated).toBe(true);
});

test('Endpoint contract provides copyable authenticated jobs and polling examples', async ({ page }) => {
  await page.addInitScript(() => {
    Object.defineProperty(navigator, 'clipboard', { value: { writeText: async (value: string) => { (window as any).__copiedCode = value; } } });
  });
  await fixture(page, async (path, _route, json) => {
    if (path === '/api/v1/endpoints/review/readiness') { await json({ readiness: { state: 'ready' } }); return true; }
    if (path === '/api/v1/endpoints/review') { await json({ endpoint: { id: 'review', name: 'Review API', slug: 'review', maxPayloadBytes: 1048576, timeoutSeconds: 300, eventSchemaVersion: 'v1', status: 'published', invocationMode: 'job', targetType: 'team', targetRef: 'team', activeRelease: 2, authPolicy: { type: 'api_key' }, inputSchema: { type: 'object', properties: { topic: { type: 'string', default: 'release' } } } } }); return true; }
    return false;
  });
  await page.goto('/agent-center/endpoints/review?tab=contract');
  await page.getByText('API integration examples', { exact: true }).click();
  const submission = page.getByRole('heading', { name: '1. Submit a request', exact: true }).locator('..').locator('..');
  await submission.getByRole('button', { name: 'Copy', exact: true }).click();
  const copied = await page.evaluate(() => (window as any).__copiedCode);
  expect(copied).toContain('/invoke/v1/endpoints/review/jobs');
  expect(copied).toContain('X-API-Key: $ENDPOINT_TOKEN');
  expect(copied).toContain('Idempotency-Key: $REQUEST_KEY');
  expect(copied).toContain('"input"');
  await expect(page.getByRole('heading', { name: '2. Check status' })).toBeVisible();
  await expect(page.getByText('invocation.errorMessage', { exact: false })).toBeVisible();
});

test('Failed task shows its cause, retry errors and results with sibling artifacts', async ({ page }) => {
  let retried = false;
  const createdAt = '2026-09-09T00:00:00Z';
  await fixture(page, async (path, route, json) => {
    if (path === '/api/v1/agent-tasks/failed') { await json({ task: { id: 'failed', agentId: 'worker', issueId: 'issue', status: 'failed', version: 1, createdAt, errorCode: 'runtime_unavailable', errorMessage: 'The runtime disconnected.', result: { output: 'Partial review', artifacts: [{ name: 'review.txt', description: 'Preserved notes' }] }, inputs: [] } }); return true; }
    if (path === '/api/v1/execution-attempts') { await json({ attempts: [{ id: 'a1', attempt: 1, state: 'failed', createdAt, failureMessage: 'Host went offline' }, { id: 'a2', attempt: 2, state: 'failed', createdAt, failureMessage: 'Retry exhausted' }] }); return true; }
    if (path === '/api/v1/agent-tasks/failed/retry') { retried = true; await route.fulfill({ status: 409, contentType: 'application/json', body: JSON.stringify({ error: 'Reconnect the runtime first' }) }); return true; }
    return false;
  });
  await page.goto('/work/executions/tasks/failed');
  await expect(page.getByText('The runtime disconnected.', { exact: false })).toBeVisible();
  await expect(page.getByText('review.txt', { exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: 'Attempt 2' })).toBeVisible();
  await expect(page.getByRole('link', { name: '← Execution' })).toHaveAttribute('href', '/work/executions');
  await page.getByRole('button', { name: 'Retry', exact: true }).click();
  await expect.poll(() => retried).toBe(true);
  await expect(page.getByRole('alert').filter({ hasText: 'Reconnect the runtime first' })).toBeVisible();
});


test('Developer can manage self_hosted environments, bind an Agent and select the session environment', async ({ page }) => {
  const environments: any[] = [];
  let definition = { id: 'managed', name: 'Managed reviewer', scope: 'user', version: 1, defaultEnvironmentId: '', defaultVaultIds: [], defaultMemoryStoreIds: [] };
  const agent = { id: 'managed', agentKey: 'managed', displayName: 'Managed reviewer', tenant: 'default', namespace: 'default', status: 'active', ownerRef: 'alice', ownerType: 'user' };
  let savedEnvironment: string | undefined;
  let submitted: any;
  await fixture(page, async (path, route, json) => {
    if (path === '/api/environments') {
      if (route.request().method() === 'POST') {
        const body = route.request().postDataJSON();
        environments.push({ id: 'worker-env', name: body.name, type: body.type, config: {}, createdAt: 1, updatedAt: 1 });
        await json(environments[0]);
      } else await json(environments);
      return true;
    }
    if (path === '/api/hands/status') { await json({ brainInstanceId: 'brain', pendingWorkItems: 0, localSandboxRegistrySize: 0, workerHeartbeats: {}, sessionHandsMetrics: {} }); return true; }
    if (path === '/api/v1/agents') { await json({ items: [agent] }); return true; }
    if (path === '/api/v1/agents/managed') { await json({ agent }); return true; }
    if (path === '/api/v1/agents/managed/definition') {
      if (route.request().method() === 'PATCH') {
        const body = route.request().postDataJSON();
        savedEnvironment = body.defaultEnvironmentId;
        definition = { ...definition, ...body, version: definition.version + 1 };
      }
      await json({ agent, definition }); return true;
    }
    if (path.endsWith('/bindings')) { await json({ items: [{ id: 'binding', agentId: 'managed', kind: 'managed', enabled: true, configuration: {} }] }); return true; }
    if (path.endsWith('/overview')) { await json({ readiness: { state: 'ready', reasons: [] }, runtime: {}, activity: {}, inventory: {}, summary: {} }); return true; }
    if (['/api/vaults', '/api/memory-stores', '/api/files'].includes(path)) { await json([]); return true; }
    if (path === '/api/sessions' && route.request().method() === 'POST') { submitted = route.request().postDataJSON(); await json({ id: 'created', agentId: 'managed', phase: 'idle' }); return true; }
    if (path === '/api/sessions/created') { await json({ id: 'created', agentId: 'managed', phase: 'idle' }); return true; }
    return false;
  }, ['developer', 'member']);
  await page.goto('/agent-center/environments');
  await expect(page.getByRole('link', { name: 'Environments', exact: true })).toBeVisible();
  await page.getByRole('button', { name: /New environment/ }).click();
  await page.getByLabel('Name', { exact: true }).fill('My worker');
  await page.getByLabel('Type', { exact: true }).selectOption('self_hosted');
  await page.getByRole('button', { name: 'Create', exact: true }).click();
  await expect(page.getByText('My worker', { exact: true })).toBeVisible();
  expect(environments[0].type).toBe('self_hosted');
  await page.goto('/agent-center/agents/managed?tab=runtime');
  await page.getByLabel('Default environment', { exact: true }).selectOption('worker-env');
  await page.getByRole('button', { name: 'Save changes', exact: true }).click();
  await expect.poll(() => savedEnvironment).toBe('worker-env');
  await page.reload();
  await expect(page.getByLabel('Default environment', { exact: true })).toHaveValue('worker-env');
  await expect(page.getByRole('link', { name: 'Manage environments' })).toHaveAttribute('href', /\/agent-center\/environments/);
  await page.goto('/managed/sessions/new?agentId=managed');
  await expect(page.getByLabel('Environment', { exact: true })).toBeVisible();
  await expect(page.getByLabel('Environment', { exact: true })).toHaveValue('worker-env');
  await page.getByRole('button', { name: 'Create session', exact: true }).click();
  await expect.poll(() => submitted?.environmentId).toBe('worker-env');
});


test('Workflow session links use only the control-plane reference and never the runtime UUID', async ({ page }) => {
  const runtimeId = '61e1cdf1-dc37-46e0-a246-ffa934a32b6c';
  let sessionRef: string | undefined;
  await fixture(page, async (path, _route, json) => {
    if (path === '/api/v1/orchestration-runs/run/graph') {
      await json({ run: { id: 'run', state: 'waiting', mode: 'dynamic', createdAt: '2026-09-09T00:00:00Z' }, nodes: [{ id: 'node', nodeKey: 'review', type: 'agent', state: 'waiting', role: 'reviewer' }], edges: [], tasks: [{ id: 'task', agentId: 'agent', runNodeId: 'node', status: 'waiting' }], attempts: [{ id: 'attempt', agentTaskId: 'task', nodeId: 'node', runId: 'run', backendKind: 'hosted-runtime', attempt: 1, state: 'waiting', sessionId: runtimeId, sessionRef }] }); return true;
    }
    return false;
  });
  await page.goto('/work/executions/run');
  await expect(page.getByText('Session diagnostics are not available yet. See this task’s execution details.')).toBeVisible();
  await expect(page.locator(`a[href*="/work/sessions/${runtimeId}"]`)).toHaveCount(0);
  sessionRef = 'control-plane-session';
  await page.reload();
  const session = page.getByRole('link', { name: 'Session ↗', exact: true });
  await expect(session).toHaveAttribute('href', '/work/sessions/control-plane-session');
  await expect(page.locator(`a[href*="/work/sessions/${runtimeId}"]`)).toHaveCount(0);
});
