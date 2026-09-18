// Synthetic documentation data. Never use a developer's database or credentials.
export const timestamp = '2026-09-10T01:00:00Z';
const scope = { tenant: 'default', namespace: 'demo' };
const dated = { ...scope, createdAt: timestamp, updatedAt: timestamp, version: 1 };
export const agents = [
  ['research', 'Research assistant', 'managed', 'Read source material and prepare evidence-based summaries.'],
  ['review', 'Code reviewer', 'hosted-runtime', 'Review changes and explain risks before merging.'],
  ['support', 'Support assistant', 'external-application', 'Answer product questions using the support knowledge base.'],
].map(([id, displayName, runtimeKind, description]) => ({ ...dated, id, agentKey: id, displayName, runtimeKind, description, status: 'active', ownerRef: 'alex', ownerType: 'user' }));
const team = { ...dated, id: 'research-team', name: 'Research team', description: 'Research, review and deliver a concise report.', leaderAgentId: 'research', status: 'active', members: [{ agentId: 'review', role: 'reviewer' }], policy: { maxActiveTasks: 8, maxFanout: 4 } };
const issues = [
  ['report', 'DEMO-12', 'Prepare the weekly research brief', 'in_review', 'high'],
  ['review-docs', 'DEMO-11', 'Review the deployment guide', 'in_progress', 'normal'],
  ['faq', 'DEMO-10', 'Summarize frequently asked questions', 'done', 'normal'],
].map(([id, identifier, title, status, priority]) => ({ ...dated, id, identifier, title, status, priority, description: 'Summarize the supplied source notes. Include links to evidence, open questions and a short recommendation. Submit the final brief for human review.', kind: 'user_work', visibility: 'work_hub', completionPolicy: 'review', assigneeType: 'agent', assigneeRef: 'research', creator: { type: 'human', ref: 'alex' }, access: { mode: 'namespace' } }));
const inbox = [
  { id: 'review-request', type: 'review_request', title: 'Review requested: Weekly research brief', body: 'The brief is ready. Please check the sources and recommendation.', issueId: 'report', needsAction: true },
  { id: 'mention', type: 'mention', title: 'Your input is needed on the deployment guide', body: 'Please confirm the target deployment environment.', issueId: 'review-docs', needsAction: false },
].map(item => ({ ...item, severity: 'attention', read: false, archived: false, actor: { type: 'agent', ref: 'research' }, createdAt: timestamp }));
const chat = { ...dated, id: 'planning', title: 'Plan the weekly research brief', creatorRef: 'alex', agentId: 'research', agentName: 'Research assistant', sessionId: 'demo-session', runtimeSessionId: 'demo-runtime', status: 'active', pinned: true };
const events = [
  { eventType: 'user.message', role: 'user', content: 'Help me plan a weekly research brief. What should I include?' },
  { eventType: 'agent.message', role: 'assistant', content: 'Use a short, repeatable structure:\n\n1. **Key findings** — three changes worth knowing about.\n2. **Evidence** — source links and publication dates.\n3. **Impact** — what each finding means for the team.\n4. **Open questions** — anything that still needs verification.\n\nWhen the scope is clear, use **Create issue** to assign the brief and request a review.' },
  { eventType: 'session.status_idle' },
].map((event, i) => ({ ...event, id: i + 1, seq: i + 1, sessionFk: 'demo-session', occurredAt: timestamp, frameworkMeta: { sourceKey: `docs:${i}`, managedEventId: `docs-${i}`, managedSeq: i + 1 } }));
const workflow = { ...dated, id: 'research-brief', name: 'Research brief', description: 'Collect evidence, draft a brief and review the result.', draftVersion: 1, draftSpec: { nodes: [
  { key: 'research', type: 'agent', agentId: 'research', issueMode: 'inherit', failurePolicy: 'fail_fast', input: { request: 'run.input.request' } },
  { key: 'review', type: 'agent', agentId: 'review', issueMode: 'inherit', failurePolicy: 'fail_fast', input: { request: 'run.input.request' } },
], edges: [{ from: 'research', to: 'review', on: ['succeeded'] }] } };
const automation = { ...dated, id: 'weekly-brief', name: 'Weekly research brief', enabled: true, actionType: 'create_issue', execution: { runbook: 'Read the weekly source notes, prepare a brief with evidence links, and submit it for review.', assigneeType: 'agent', assigneeRef: 'research', outputMode: 'create_issue', completionPolicy: 'review', concurrencyPolicy: 'skip', queueTimeoutSeconds: 3600, runTimeoutSeconds: 3600 }, triggers: [{ id: 'weekly', type: 'cron', schedule: '0 9 * * 1', timezone: 'Asia/Shanghai', enabled: true }] };
const channel = { channelId: 'team-feishu', type: 'feishu', dmScope: 'PER_PEER', defaultAgentId: 'research', disabled: false, started: true, properties: {}, bindings: [] };
const millis = Date.parse(timestamp);
const resource = { ownerId: 'alex', createdAt: millis, updatedAt: millis };

export async function installFixtures(context) {
  const token = `docs.${Buffer.from(JSON.stringify({ username: 'alex', sub: 'alex', roles: ['admin'] })).toString('base64url')}.fixture`;
  await context.addInitScript(token => {
    localStorage.setItem('claw_token', token);
    localStorage.setItem('theme', 'light');
  }, token);
  const unexpected = new Set();
  await context.route('**/api/**', async route => {
    const request = route.request();
    const url = new URL(request.url());
    const path = url.pathname;
    if (!path.startsWith('/api/')) return route.continue();
    const json = body => route.fulfill({ contentType: 'application/json', body: JSON.stringify(body) });
    if (path === '/api/v1/entity-identities:resolve') return json({ items: request.postDataJSON().refs.map(ref => ({ ...ref, name: agents.find(agent => agent.id === ref.ref)?.displayName || ({ alex: 'Alex', 'research-brief': 'Research brief', 'research-team': 'Research team' }[ref.ref]) || ref.ref, resolved: true })) });
    if (path === '/api/v1/automations/schedule-preview') return json({ nextRuns: ['2026-09-14T01:00:00Z', '2026-09-21T01:00:00Z', '2026-09-28T01:00:00Z'] });
    // No requests reach a running backend, and capture flows cannot mutate resources.
    if (!['GET', 'HEAD'].includes(request.method())) {
      if (path === '/api/v1/inbox/review-request/read') return json({ item: { ...inbox[0], read: true } });
      unexpected.add(`${request.method()} ${path}`);
      return route.fulfill({ status: 405, body: 'Documentation capture is read-only' });
    }
    if (path === '/api/auth/me') return json({ userId: 'alex', username: 'alex', roles: ['admin'], isAdmin: true });
    if (path === '/api/v1/me/scope') return json({ ...scope, mode: 'multi', selectorVisible: true, namespaces: [{ tenant: 'default', name: 'demo', displayName: 'Demo', kind: 'shared', roles: ['admin', 'developer', 'operator', 'member'] }] });
    if (path === '/api/v1/me/preferences') return json({ preferences: {} });
    if (path === '/api/v1/agents') return json({ items: agents });
    for (const agent of agents) {
      if (path === `/api/v1/agents/${agent.id}`) return json({ agent });
      if (path === `/api/v1/agents/${agent.id}/bindings`) return json({ items: [{ id: `${agent.id}-binding`, agentId: agent.id, kind: agent.runtimeKind, enabled: true, priority: 100, configuration: {} }] });
    }
    if (path === '/api/v1/teams') return json({ items: [team] });
    if (path === '/api/v1/issues') return json({ items: url.searchParams.has('parentIssueId') ? [] : issues });
    if (path === '/api/v1/issues/report/summary') return json({ summary: {} });
    for (const issue of issues) {
      if (path === `/api/v1/issues/${issue.id}`) return json({ issue });
      if (path === `/api/v1/issues/${issue.id}/comments`) return json({ items: [{ ...dated, id: 'result', issueId: issue.id, threadRootId: 'result', author: { type: 'agent', ref: 'research' }, content: 'The weekly brief is ready for review. It includes three findings, supporting source links and the questions that need follow-up.', type: 'result' }], nextCursor: '' });
    }
    if (path === '/api/v1/inbox/summary') return json({ summary: { unread: 2, actionRequired: 1, pendingApprovals: 0, attentionTotal: 2, byType: { review_request: 1, mention: 1 } } });
    if (path === '/api/v1/inbox') return json({ items: inbox, hasMore: false, nextCursor: '' });
    if (path === '/api/v1/inbox/review-request') return json({ item: inbox[0] });
    if (path === '/api/v1/chat-agents') return json({ items: agents.map(agent => ({ id: agent.id, name: agent.displayName, description: agent.description, capability: { state: 'available', reason: '' } })) });
    if (path === '/api/v1/chats') return json({ items: [chat] });
    if (path === '/api/v1/chats/planning') return json({ chat });
    if (path.endsWith('/events/stream')) return route.fulfill({ contentType: 'text/event-stream', body: '' });
    if (path.endsWith('/events') && (path.includes('demo-session') || path.includes('/chats/'))) return json({ events: Number(url.searchParams.get('after') || 0) ? [] : events });
    if (path === '/api/v1/orchestration-definitions') return json({ definitions: [workflow] });
    if (path === '/api/v1/orchestration-definitions/research-brief') return json({ definition: workflow });
    if (path === '/api/v1/orchestration-definitions/research-brief/revisions') return json({ revisions: [{ id: 'brief-v1', definitionId: workflow.id, revision: 1, spec: workflow.draftSpec, publishedAt: timestamp }] });
    if (path === '/api/v1/automations') return json({ items: [automation] });
    if (path === '/api/v1/automations/weekly-brief') return json({ automation });
    if (path === '/api/channels') return json([channel]);
    if (path === '/api/channels/types') return json([{ type: 'feishu', label: 'Feishu', transport: 'callback', fields: [] }]);
    if (path === '/api/workspaces') return json([{ ...resource, id: 'research-pack', name: 'Research workspace', description: 'Shared instructions, evidence review skills and read-only tools.', version: 1, agentsMdExists: true, skillCount: 2, subagentCount: 1, tools: [], mcpServers: [] }]);
    if (path === '/api/environments') return json([{ ...resource, id: 'local-demo', name: 'Local development', type: 'local', config: {} }, { ...resource, id: 'worker-demo', name: 'Research worker', type: 'self_hosted', config: {} }]);
    if (path === '/api/v1/namespaces/demo/resources') return json({ version: 1, items: [{ resource: { kind: 'agent', id: 'research', name: 'Research assistant', dependencies: ['memory:research-memory', 'vault:research-vault'] }, actions: ['read'] }] });
    if (path === '/api/hands/status') return json({ brainInstanceId: 'demo', pendingWorkItems: 0, localSandboxRegistrySize: 0, workerHeartbeats: {}, sessionHandsMetrics: {} });
    if (path === '/api/memory-stores') return json([{ ...resource, id: 'research-memory', name: 'Research knowledge', description: 'Reviewed findings and reusable research notes.' }]);
    if (path === '/api/memory-stores/research-memory/memories') return json([{ ...resource, id: 'style', storeId: 'research-memory', path: 'guides/research-brief.md', content: 'Keep briefs concise. Cite source URLs and distinguish evidence from assumptions.', headVersion: 1 }]);
    if (path === '/api/vaults') return json([{ ...resource, id: 'research-vault', displayName: 'Research credentials' }]);
    if (path === '/api/vaults/research-vault/credentials') return json([{ id: 'docs-credential', type: 'static_bearer', label: 'Documentation API', target: 'https://api.example.com', createdAt: millis }]);
    // Explicit empty ancillary collections keep API drift visible.
    if ([
      '/api/v1/agent-instances', '/api/v1/runtime-profiles', '/api/v1/runtime-hosts',
      '/api/v1/orchestration-runs', '/api/v1/automations/weekly-brief/runs',
      '/api/v1/issues/report/activity', '/api/v1/issues/report/subscribers',
      '/api/v1/issues/report/artifacts', '/api/v1/issues/report/children',
      '/api/v1/agent-tasks', '/api/v1/tasks', '/api/v1/endpoints',
    ].includes(path)) return json({ items: [], runs: [], events: [], subscribers: [], artifacts: [] });
    unexpected.add(`${request.method()} ${path}`);
    return route.fulfill({ status: 501, body: `Missing documentation fixture: ${path}` });
  });
  return unexpected;
}
