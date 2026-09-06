import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Copy, ExternalLink, MessageSquare, RefreshCw } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  getAgent,
  getAgentDetailOverview,
  listCatalogAgentInstances,
  listCatalogBindings,
  rotateAgentRegistrationCredential,
  setCatalogBindingEnabled,
  type CatalogAgentInstance,
  type TelemetryState,
} from '@/api/agents';
import { getRoles } from '@/api/auth';
import { listTasks } from '@/api/collaboration';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { EmptyState } from '@/components/EmptyState';
import { EntityIdentityText, useEntityIdentities } from '@/components/EntityIdentity';
import { JsonViewer } from '@/components/JsonViewer';
import { Page, PageHeader } from '@/components/Page';
import { PressureGauge } from '@/components/PressureGauge';
import { PublishEndpointCard } from '@/components/PublishEndpointCard';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { agentSessionDetailPath, fetchRuntimeSessions, phaseTone } from '@/features/operate/api';
import { formatRelative } from '@/lib/format';
import { HostedAgentSettings } from './HostedAgentSettings';
import { agentDetailTabs, resolveAgentDetailTab, type AgentDetailTabId } from './agentNavigation';

function healthTone(health: string): 'success' | 'warning' | 'danger' | 'default' {
  if (health === 'healthy' || health === 'ready') return 'success';
  if (health === 'unhealthy' || health === 'offline') return 'danger';
  if (health === 'unknown') return 'warning';
  return 'default';
}
function readinessTone(state?: string): 'success' | 'warning' | 'danger' | 'default' {
  if (state === 'ready') return 'success';
  if (state === 'degraded' || state === 'unbound') return 'warning';
  if (state === 'unavailable' || state === 'inactive') return 'danger';
  return 'default';
}
function telemetryTone(state: TelemetryState): 'success' | 'warning' | 'default' | 'info' {
  if (state === 'available') return 'success';
  if (state === 'partial' || state === 'not_reporting') return 'warning';
  if (state === 'not_applicable') return 'default';
  return 'info';
}
function telemetryLabel(state?: TelemetryState) {
  if (!state) return 'Not reporting';
  return state.split('_').map(value => value.charAt(0).toUpperCase() + value.slice(1)).join(' ');
}
function capacityLabel(capacity: number | null | undefined, available: number | null | undefined) {
  return capacity == null ? 'Unlimited / N/A' : `${available ?? 0} of ${capacity} available`;
}
function bindingConfiguration(value: unknown) {
  return value && typeof value === 'object' ? value as Record<string, unknown> : {};
}
function bindingLabel(kind: string) {
  if (kind === 'managed') return 'Managed runtime';
  if (kind === 'external-application') return 'External application';
  if (kind === 'hosted-runtime') return 'Hosted runtime';
  return kind;
}
function bindingDescription(kind: string) {
  if (kind === 'managed') return 'AgentScope Service owns the definition, session lifecycle, and execution adapter.';
  if (kind === 'external-application') return 'An independently deployed application registers instances and receives ASDP commands.';
  if (kind === 'hosted-runtime') return 'Execution is launched on demand through an available detected agent runtime.';
  return 'Runtime binding configuration.';
}
function instanceSummary(kind: string, rows: CatalogAgentInstance[]) {
  if (kind === 'managed') return 'Platform managed';
  if (kind === 'hosted-runtime') return 'On-demand';
  return `${rows.filter(item => item.health === 'healthy').length}/${rows.length} healthy`;
}
function taskTriggerLabel(triggerType: string) {
  const labels: Record<string, string> = {
    assignment: 'Issue assigned directly',
    comment: 'Issue comment routed',
    mention: 'Mentioned in an Issue',
    delegation: 'Delegated by another Agent',
    orchestration_node: 'Workflow step',
    endpoint: 'Endpoint job',
    automation: 'Automation-created task',
    manual_replay: 'Manually replayed task',
    retry: 'Retried task',
  };
  return labels[triggerType] ?? triggerType.split('_').map(word => word.charAt(0).toUpperCase() + word.slice(1)).join(' ');
}

function InstanceCard({ instance }: { instance: CatalogAgentInstance }) {
  return <div className="rounded-xl border border-border bg-white p-4">
    <div className="flex items-start justify-between gap-3">
      <div className="min-w-0"><div className="truncate font-medium">{instance.instanceKey}</div><div className="mt-1 text-sm text-muted-foreground">{instance.framework || instance.backendKind || 'runtime'}{instance.frameworkVersion ? ` ${instance.frameworkVersion}` : ''} · generation {instance.generation}</div></div>
      <Badge tone={healthTone(instance.health)}>{instance.health}</Badge>
    </div>
    <div className="mt-3 grid grid-cols-2 gap-3 text-sm"><div><span className="text-muted-foreground">Load</span><div className="mt-0.5 font-medium">{instance.activeSessions}/{instance.capacity <= 0 ? '∞' : instance.capacity}</div></div><div><span className="text-muted-foreground">Last seen</span><div className="mt-0.5 font-medium">{formatRelative(instance.lastSeenAt)}</div></div></div>
  </div>;
}

export default function AgentCatalogDetailPage() {
  const { agentId = '' } = useParams();
  const scope = useControlPlaneScope();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const [params, setParams] = useSearchParams();
  const roles = getRoles().map(role => role.toLowerCase());
  const [registrationCredential, setRegistrationCredential] = useState('');
  const canEdit = roles.includes('admin') || roles.includes('agent_developer');
  const tabParam = params.get('tab');
  const agent = useQuery({ queryKey: ['catalog-agent', agentId], queryFn: () => getAgent(agentId), enabled: !!agentId });
  const runtimeKind = agent.data?.runtimeKind;
  const tabs = useMemo(() => agentDetailTabs(runtimeKind), [runtimeKind]);
  const tab = resolveAgentDetailTab(tabParam, runtimeKind);
  const setTab = (next: AgentDetailTabId) => {
    const nextParams = new URLSearchParams(params);
    if (next === 'overview') nextParams.delete('tab'); else nextParams.set('tab', next);
    setParams(nextParams, { replace: true });
  };

  useEffect(() => {
    if (!agent.data || !['settings', 'runtime'].includes(tabParam ?? '') || (tabParam === 'settings' && runtimeKind === 'managed')) return;
    const nextParams = new URLSearchParams(params);
    nextParams.delete('tab');
    setParams(nextParams, { replace: true });
  }, [agent.data, params, runtimeKind, setParams, tabParam]);

  const overview = useQuery({ queryKey: ['catalog-agent-overview', agentId], queryFn: () => getAgentDetailOverview(agentId), enabled: !!agentId, refetchInterval: tab === 'overview' ? 10_000 : false, refetchIntervalInBackground: false });
  const bindings = useQuery({ queryKey: ['catalog-agent-bindings', agentId], queryFn: () => listCatalogBindings(agentId), enabled: !!agentId });
  const instances = useQuery({ queryKey: ['catalog-agent-instances', agentId], queryFn: () => listCatalogAgentInstances(agentId), enabled: !!agentId, refetchInterval: tab === 'overview' ? 10_000 : false, refetchIntervalInBackground: false });
  const sessions = useQuery({ queryKey: ['catalog-agent-sessions', agentId], queryFn: () => fetchRuntimeSessions({ agentId, limit: 100 }), enabled: !!agentId && tab === 'sessions', refetchInterval: tab === 'sessions' ? 10_000 : false, refetchIntervalInBackground: false });
  const tasks = useQuery({ queryKey: ['catalog-agent-related-work', agentId, scope.tenant, scope.namespace], queryFn: () => listTasks(scope.tenant, scope.namespace, '', agentId), enabled: !!agentId && tab === 'related-work', refetchInterval: tab === 'related-work' ? 10_000 : false });
  const toggle = useMutation({ mutationFn: ({ binding, enabled }: { binding: NonNullable<typeof bindings.data>[number]; enabled: boolean }) => setCatalogBindingEnabled(agentId, binding, enabled), onSuccess: async () => { await Promise.all([queryClient.invalidateQueries({ queryKey: ['catalog-agent-bindings', agentId] }), queryClient.invalidateQueries({ queryKey: ['catalog-agent-overview', agentId] })]); } });
  const rotateCredential = useMutation({ mutationFn: () => rotateAgentRegistrationCredential(agentId), onSuccess: setRegistrationCredential });
  const runtimeKinds = useMemo(() => Array.from(new Set((bindings.data ?? []).map(binding => binding.kind))), [bindings.data]);
  const identities = useEntityIdentities((tasks.data?.items ?? []).slice(0, 10).map(task => ({ type: 'issue', ref: task.issueId })));

  if (agent.isLoading) return <Page><p className="text-sm text-muted-foreground">Loading…</p></Page>;
  if (!agent.data) return <Page><EmptyState title="Agent not found" description={agent.error instanceof Error ? agent.error.message : 'The Agent identity is unavailable.'} /></Page>;
  const value = agent.data;
  const currentOverview = overview.data;
  const sessionItems = sessions.data?.sessions ?? [];
  const liveSessions = sessionItems.filter(item => !['archived', 'terminated'].includes((item.phase || '').toLowerCase()));
  const historySessions = sessionItems.filter(item => ['archived', 'terminated'].includes((item.phase || '').toLowerCase()));
  const relatedTasks = (tasks.data?.items ?? []).slice(0, 10);
  const taskCounts = (tasks.data?.items ?? []).reduce<Record<string, number>>((counts, task) => { counts[task.status] = (counts[task.status] ?? 0) + 1; return counts; }, {});

  return <Page>
    <div>
      <Link to={scope.scopedPath('/agent-center/agents')} className="text-sm text-muted-foreground hover:text-foreground">← Agents</Link>
      <PageHeader className="mt-2" title={value.name} description={value.description || 'Enterprise Agent service runtime'} actions={<div className="flex flex-wrap gap-2"><Button onClick={() => navigate(scope.scopedPath(`/work/chat?agent=${encodeURIComponent(value.id)}`))}><MessageSquare className="mr-2 h-4 w-4" />Chat</Button><Button variant="outline" onClick={() => void queryClient.invalidateQueries({ queryKey: ['catalog-agent-overview', agentId] })}><RefreshCw className="mr-2 h-4 w-4" />Refresh</Button>{runtimeKinds.includes('managed') && canEdit && <Button variant="outline" onClick={() => navigate(scope.scopedPath(`/agent-center/agents/${encodeURIComponent(value.id)}/manage/settings`))}>Manage</Button>}{runtimeKinds.includes('external-application') && canEdit && <Button variant="outline" disabled={rotateCredential.isPending} onClick={() => rotateCredential.mutate()}>Rotate credential</Button>}</div>} />
      <div className="flex flex-wrap items-center gap-2"><Badge tone={value.status === 'active' ? 'success' : 'warning'}>{value.status || 'unknown'}</Badge><Badge tone={readinessTone(currentOverview?.readiness.state)}>{currentOverview?.readiness.state || 'observing'}</Badge><Badge tone="info">{currentOverview?.readiness.mode || 'online'}</Badge>{runtimeKinds.map(kind => <Badge key={kind}>{bindingLabel(kind)}</Badge>)}<span className="ml-1 font-mono text-xs text-muted-foreground">{value.agentKey}</span><button type="button" title="Copy Agent ID" className="inline-flex items-center gap-1 font-mono text-xs text-muted-foreground hover:text-foreground" onClick={() => void navigator.clipboard.writeText(value.id)}>{value.id}<Copy className="h-3.5 w-3.5" /></button></div>
    </div>
    {registrationCredential && <Card className="border-amber-300 bg-amber-50"><CardHeader><CardTitle>Save the new registration credential</CardTitle><CardDescription>The plaintext value is shown only once.</CardDescription></CardHeader><CardContent className="grid gap-3"><code className="break-all rounded border bg-white p-3 text-xs">{registrationCredential}</code><Button variant="outline" onClick={() => void navigator.clipboard.writeText(registrationCredential)}>Copy</Button></CardContent></Card>}
    <div className="flex flex-wrap gap-1 border-b border-border pb-px">{tabs.map(item => <button key={item.id} type="button" onClick={() => setTab(item.id)} className={`rounded-t-lg px-4 py-2.5 text-sm ${tab === item.id ? 'border border-b-0 border-border bg-white font-medium text-foreground' : 'text-muted-foreground hover:text-foreground'}`}>{item.label}</button>)}</div>

    {tab === 'overview' && <div className="grid gap-5">
      {overview.isError && <p className="text-sm text-red-600">{String(overview.error)}</p>}
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <Card><CardHeader><CardDescription>Service readiness</CardDescription><CardTitle className="capitalize">{currentOverview?.readiness.state || 'Observing'}</CardTitle></CardHeader><CardContent className="text-sm text-muted-foreground">{currentOverview?.readiness.reason || 'Waiting for runtime data.'}</CardContent></Card>
        <Card><CardHeader><CardDescription>Runtime availability</CardDescription><CardTitle>{currentOverview ? currentOverview.readiness.mode === 'on-demand' ? 'On-demand' : currentOverview.instances.total > 0 ? `${currentOverview.instances.healthy}/${currentOverview.instances.total}` : runtimeKinds.includes('managed') ? 'Managed' : 'Not reporting' : '—'}</CardTitle></CardHeader><CardContent className="text-sm text-muted-foreground">{currentOverview?.readiness.mode === 'on-demand' ? 'Scheduled automatically when work starts' : currentOverview?.instances.total ? `${currentOverview.instances.unhealthy} unavailable instances` : runtimeKinds.includes('managed') ? 'Session lifecycle is owned by AgentScope Service' : 'No runtime instances observed'}</CardContent></Card>
        <Card><CardHeader><CardDescription>Live sessions</CardDescription><CardTitle>{currentOverview ? currentOverview.sessions.active + currentOverview.sessions.idle + currentOverview.sessions.compressing : '—'}</CardTitle></CardHeader><CardContent className="text-sm text-muted-foreground"><Badge tone={telemetryTone(currentOverview?.sessions.status || 'not_reporting')}>{telemetryLabel(currentOverview?.sessions.status)}</Badge></CardContent></Card>
        <Card><CardHeader><CardDescription>Capacity</CardDescription><CardTitle>{currentOverview?.readiness.mode === 'on-demand' ? 'On-demand' : currentOverview?.instances.total ? capacityLabel(currentOverview.instances.capacity, currentOverview.instances.availableCapacity) : runtimeKinds.includes('managed') ? 'Platform managed' : 'Not reporting'}</CardTitle></CardHeader><CardContent className="text-sm text-muted-foreground">{currentOverview?.instances.total ? `${currentOverview.instances.activeSessions} active on runtime instances` : currentOverview?.readiness.mode === 'on-demand' ? 'Checked across detected runtimes at dispatch' : 'No instance capacity signal'}</CardContent></Card>
      </div>
      <div className="grid gap-5 lg:grid-cols-[1.35fr_1fr]">
        <Card><CardHeader><CardTitle>Execution runtime</CardTitle><CardDescription>How this Agent is started and where its capacity comes from.</CardDescription></CardHeader><CardContent className="space-y-3">{(bindings.data ?? []).map(binding => { const rows = (instances.data ?? []).filter(instance => instance.bindingId === binding.id); return <div key={binding.id} className="flex items-center justify-between gap-4 rounded-lg border border-border p-4"><div><div className="font-medium">{bindingLabel(binding.kind)}</div></div><div className="text-right"><Badge tone={binding.enabled ? 'success' : 'warning'}>{binding.enabled ? 'enabled' : 'disabled'}</Badge><div className="mt-1 text-xs text-muted-foreground">{instanceSummary(binding.kind, rows)}</div></div></div>; })}{!bindings.isLoading && !(bindings.data ?? []).length && <EmptyState title="Runtime unavailable" description="Choose an available runtime before starting work with this Agent." className="py-8" />}</CardContent></Card>
        <Card><CardHeader><CardTitle>Service signals · 24h</CardTitle><CardDescription>Only data actually reported by the runtime is shown.</CardDescription></CardHeader><CardContent className="space-y-4 text-sm"><div className="flex items-center justify-between"><span className="text-muted-foreground">Sessions created</span><span className="font-medium">{currentOverview?.sessions.createdInWindow ?? '—'}</span></div><div className="flex items-center justify-between"><span className="text-muted-foreground">Tokens observed</span><span className="font-medium">{currentOverview?.usage.totalTokens == null ? 'Not reporting' : currentOverview.usage.totalTokens.toLocaleString()}</span></div><div className="flex items-center justify-between"><span className="text-muted-foreground">Errors observed</span><span className="font-medium">{currentOverview?.usage.errorCount == null ? 'Not reporting' : currentOverview.usage.errorCount.toLocaleString()}</span></div><div className="flex items-center justify-between"><span className="text-muted-foreground">Enabled endpoints</span><span className="font-medium">{currentOverview?.entrypoints.enabled ?? '—'}/{currentOverview?.entrypoints.total ?? '—'}</span></div><div className="border-t pt-3 text-xs text-muted-foreground">Last runtime activity: {formatRelative(currentOverview?.sessions.lastActiveAt)}</div></CardContent></Card>
      </div>
    </div>}

    {tab === 'sessions' && <div className="grid gap-5 lg:grid-cols-2">{([['Live sessions', liveSessions], ['History', historySessions]] as const).map(([title, items]) => <Card key={title}><CardHeader><CardTitle>{title}</CardTitle><CardDescription>{title === 'Live sessions' ? 'Open a runtime session to inspect messages, events, context, tasks, and commands in read-only mode.' : 'Open an archived or terminated runtime context for its recorded session details.'}</CardDescription></CardHeader><CardContent className="space-y-3">{items.map(session => <Link key={session.id} to={scope.scopedPath(agentSessionDetailPath(agentId, session))} className="block rounded-lg border border-border p-4 hover:bg-muted/40"><div className="flex items-start justify-between gap-3"><div className="min-w-0"><div className="truncate font-medium">{session.sessionId}</div><div className="mt-1 flex flex-wrap gap-1.5"><Badge tone={phaseTone(session.phase)}>{session.phase}</Badge><Badge>{session.originType || 'runtime'}</Badge>{session.framework && <Badge tone="info">{session.framework}</Badge>}</div></div><ExternalLink className="h-4 w-4 text-muted-foreground" /></div><div className="mt-3 flex items-center justify-between gap-3 text-xs text-muted-foreground"><span>{session.instanceRef || 'No instance affinity'} · {formatRelative(session.lastActiveAt || session.startedAt)}</span><PressureGauge value={session.snapshot?.contextPressure} /></div></Link>)}{!sessions.isLoading && !items.length && <EmptyState title={`No ${title.toLowerCase()}`} description="No matching runtime contexts have been reported." className="py-8" />}</CardContent></Card>)}</div>}

    {tab === 'overview' && <section className="grid gap-5 border-t pt-5"><div><h2 className="text-lg font-semibold">Runtime & integration</h2><p className="mt-1 text-sm text-muted-foreground">Configuration and live runtime state are kept with the service overview.</p></div>{(bindings.data ?? []).map(binding => { const rows = (instances.data ?? []).filter(instance => instance.bindingId === binding.id); const configuration = bindingConfiguration(binding.configuration); return <Card key={binding.id}><CardHeader><div className="flex items-start justify-between gap-3"><div><CardTitle>{bindingLabel(binding.kind)}</CardTitle><CardDescription className="mt-1">{bindingDescription(binding.kind)}</CardDescription></div><div className="flex items-center gap-2"><Badge tone={binding.enabled ? 'success' : 'warning'}>{binding.enabled ? 'enabled' : 'disabled'}</Badge>{canEdit && <Button size="sm" variant="outline" disabled={toggle.isPending} onClick={() => toggle.mutate({ binding, enabled: !binding.enabled })}>{binding.enabled ? 'Disable' : 'Enable'}</Button>}</div></div></CardHeader><CardContent className="grid gap-5 lg:grid-cols-[0.8fr_1.2fr]"><div><div className="mb-2 text-sm font-medium">{binding.kind === 'managed' ? 'Definition' : binding.kind === 'external-application' ? 'Registration & routing' : 'Runtime selection'}</div>{binding.kind === 'managed' ? <div className="space-y-3 rounded-lg border p-4 text-sm"><div><span className="text-muted-foreground">Definition reference</span><div className="mt-1 font-mono text-xs">{String(configuration.managedDefinitionRef ?? 'Not configured')}</div></div><div><span className="text-muted-foreground">Owner</span><div className="mt-1">{String(configuration.ownerRef ?? 'Not configured')}</div></div>{canEdit && <Button size="sm" variant="outline" onClick={() => navigate(scope.scopedPath(`/agent-center/agents/${agentId}/manage/settings`))}>Manage definition</Button>}</div> : binding.kind === 'external-application' ? <div className="space-y-3 rounded-lg border p-4 text-sm"><div><span className="text-muted-foreground">Registered instances</span><div className="mt-1 font-medium">{rows.length}</div></div><div><span className="text-muted-foreground">Instance selector</span><JsonViewer value={configuration.instanceSelector ?? {}} className="mt-1 max-h-32" /></div><div className="text-xs text-muted-foreground">Credentials authenticate registration; instance health and availability are reported by the runtime.</div></div> : <div className="space-y-3 rounded-lg border p-4 text-sm"><div className="font-medium">Automatically selected</div><div className="text-muted-foreground">AgentScope uses the detected agent runtime selected when this Agent was created.</div><div className="text-xs text-muted-foreground">Processes are launched per execution; availability and capacity are checked automatically.</div></div>}</div><div><div className="mb-2 text-sm font-medium">{binding.kind === 'external-application' ? 'Registered instances' : binding.kind === 'hosted-runtime' ? 'Execution capacity' : 'Service lifecycle'}</div><div className="grid gap-3">{rows.map(instance => <InstanceCard key={instance.id} instance={instance} />)}{!rows.length && <div className="rounded-lg border border-dashed p-5 text-sm text-muted-foreground">{binding.kind === 'hosted-runtime' ? 'On-demand: an available agent runtime is selected when work starts.' : binding.kind === 'managed' ? 'Sessions are created and managed automatically by AgentScope Service.' : 'Not reporting: no external application process is currently connected.'}</div>}</div></div></CardContent></Card>; })}{runtimeKinds.includes('hosted-runtime') && <HostedAgentSettings agent={value} canEdit={canEdit} />}</section>}

    {tab === 'entrypoints' && <PublishEndpointCard
      targetType="agent"
      targetRef={value.id}
      targetName={value.name}
      ownerPath={`/agent-center/agents/${value.id}?tab=entrypoints`}
    />}

    {tab === 'related-work' && <div className="grid gap-5"><Card className="border-indigo-200 bg-indigo-50/50"><CardContent className="pt-6 text-sm text-muted-foreground">These are control-plane AgentTasks linked to Issues, including direct assignment, comment routing, workflow or Endpoint execution, replay, and retry. Runtime-native traffic is shown under Sessions. Counts below cover the currently loaded set of up to 100 tasks.</CardContent></Card><div className="grid gap-4 sm:grid-cols-3"><Card><CardHeader><CardDescription>Active Issue tasks</CardDescription><CardTitle>{(taskCounts.queued ?? 0) + (taskCounts.dispatched ?? 0) + (taskCounts.running ?? 0)}</CardTitle></CardHeader></Card><Card><CardHeader><CardDescription>Completed Issue tasks</CardDescription><CardTitle>{taskCounts.completed ?? 0}</CardTitle></CardHeader></Card><Card><CardHeader><CardDescription>Failed / cancelled Issue tasks</CardDescription><CardTitle>{(taskCounts.failed ?? 0) + (taskCounts.cancelled ?? 0)}</CardTitle></CardHeader></Card></div><Card><CardHeader><CardTitle>Issue-based AgentTasks</CardTitle><CardDescription>Showing up to 10 task records from the currently loaded set. Open a task or its source Issue for details.</CardDescription></CardHeader><CardContent className="space-y-3">{relatedTasks.map(task => <div key={task.id} className="grid gap-3 rounded-lg border p-4 sm:grid-cols-[1fr_auto_auto] sm:items-center"><div><Link className="font-mono text-sm text-primary hover:underline" to={scope.scopedPath(`/work/executions/tasks/${task.id}`)}>{task.id.slice(0, 8)}</Link><div className="mt-1 text-xs text-muted-foreground">{taskTriggerLabel(task.triggerType)} · {formatRelative(task.createdAt)}</div></div><Link className="text-sm hover:underline" to={scope.scopedPath(`/work/issues/${task.issueId}`)}><EntityIdentityText identities={identities} type="issue" entityRef={task.issueId} secondary /></Link><Badge tone={task.status === 'completed' ? 'success' : task.status === 'failed' ? 'danger' : 'warning'}>{task.status}</Badge></div>)}{!tasks.isLoading && !relatedTasks.length && <EmptyState title="No Issue-based AgentTasks" description="This Agent has no loaded control-plane task records. Runtime-native traffic may still appear under Sessions." className="py-10" />}</CardContent></Card></div>}

    {tab === 'settings' && runtimeKind === 'managed' && <Card><CardHeader><CardTitle>Agent settings</CardTitle><CardDescription>Managed Agent behavior is configured in its definition editor.</CardDescription></CardHeader><CardContent>{canEdit && <Button variant="outline" onClick={() => navigate(scope.scopedPath(`/agent-center/agents/${agentId}/manage/settings`))}>Open definition settings</Button>}</CardContent></Card>}
  </Page>;
}
