import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Copy, ExternalLink, Play, RefreshCw } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import {
  getAgent,
  getAgentDetailOverview,
  getAgentRuntimeInventory,
  listCatalogAgentInstances,
  listCatalogBindings,
  rotateAgentRegistrationCredential,
  setCatalogBindingEnabled,
  type CatalogAgentInstance,
  type TelemetryState,
} from '@/api/agents';
import { listEndpoints } from '@/api/agentEndpoints';
import { getRoles } from '@/api/auth';
import { listTasks } from '@/api/collaboration';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { EmptyState } from '@/components/EmptyState';
import { EntityIdentityText, useEntityIdentities } from '@/components/EntityIdentity';
import { JsonViewer } from '@/components/JsonViewer';
import { InvocationPlayground } from '@/components/InvocationPlayground';
import { Page, PageHeader } from '@/components/Page';
import { PressureGauge } from '@/components/PressureGauge';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { agentSessionDetailPath, fetchRuntimeSessions, phaseTone } from '@/features/operate/api';
import {
  describeAgentCapability,
  type CapabilityDestination,
} from '@/lib/agentCapabilityPresentation';
import { formatRelative } from '@/lib/format';
import { HostedAgentSettings } from './HostedAgentSettings';

type TabId = 'overview' | 'playground' | 'sessions' | 'runtime' | 'capabilities' | 'entrypoints' | 'related-work' | 'settings';
const tabs: Array<{ id: TabId; label: string }> = [
  { id: 'overview', label: 'Overview' },
  { id: 'playground', label: 'Playground' },
  { id: 'sessions', label: 'Sessions' },
  { id: 'runtime', label: 'Runtime' },
  { id: 'capabilities', label: 'Capabilities' },
  { id: 'entrypoints', label: 'Entrypoints' },
  { id: 'related-work', label: 'Related Work' },
  { id: 'settings', label: 'Settings' },
];

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
function capabilityList(value: unknown): string[] {
  if (Array.isArray(value)) return value.filter((item): item is string => typeof item === 'string').sort();
  if (value && typeof value === 'object') {
    return Object.entries(value as Record<string, unknown>).filter(([, enabled]) => enabled !== false).map(([key]) => key).sort();
  }
  return [];
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
  const tab: TabId = tabs.some(item => item.id === tabParam) ? tabParam as TabId : 'overview';
  const setTab = (next: TabId) => {
    const nextParams = new URLSearchParams(params);
    if (next === 'overview') nextParams.delete('tab'); else nextParams.set('tab', next);
    setParams(nextParams, { replace: true });
  };

  const agent = useQuery({ queryKey: ['catalog-agent', agentId], queryFn: () => getAgent(agentId), enabled: !!agentId });
  const overview = useQuery({ queryKey: ['catalog-agent-overview', agentId], queryFn: () => getAgentDetailOverview(agentId), enabled: !!agentId, refetchInterval: tab === 'overview' ? 10_000 : false, refetchIntervalInBackground: false });
  const bindings = useQuery({ queryKey: ['catalog-agent-bindings', agentId], queryFn: () => listCatalogBindings(agentId), enabled: !!agentId });
  const instances = useQuery({ queryKey: ['catalog-agent-instances', agentId], queryFn: () => listCatalogAgentInstances(agentId), enabled: !!agentId, refetchInterval: ['overview', 'runtime', 'capabilities'].includes(tab) ? 10_000 : false, refetchIntervalInBackground: false });
  const sessions = useQuery({ queryKey: ['catalog-agent-sessions', agentId], queryFn: () => fetchRuntimeSessions({ agentId, limit: 100 }), enabled: !!agentId && tab === 'sessions', refetchInterval: tab === 'sessions' ? 10_000 : false, refetchIntervalInBackground: false });
  const inventory = useQuery({ queryKey: ['catalog-agent-runtime-inventory', agentId], queryFn: () => getAgentRuntimeInventory(agentId), enabled: !!agentId && tab === 'capabilities', retry: false });
  const endpoints = useQuery({ queryKey: ['catalog-endpoints', agentId, scope.tenant, scope.namespace], queryFn: () => listEndpoints(scope.tenant, scope.namespace, { targetType: 'agent', targetRef: agentId }), enabled: !!agentId && tab === 'entrypoints' });
  const tasks = useQuery({ queryKey: ['catalog-agent-related-work', agentId, scope.tenant, scope.namespace], queryFn: () => listTasks(scope.tenant, scope.namespace, '', agentId), enabled: !!agentId && tab === 'related-work', refetchInterval: tab === 'related-work' ? 10_000 : false });
  const toggle = useMutation({ mutationFn: ({ binding, enabled }: { binding: NonNullable<typeof bindings.data>[number]; enabled: boolean }) => setCatalogBindingEnabled(agentId, binding, enabled), onSuccess: async () => { await Promise.all([queryClient.invalidateQueries({ queryKey: ['catalog-agent-bindings', agentId] }), queryClient.invalidateQueries({ queryKey: ['catalog-agent-overview', agentId] })]); } });
  const rotateCredential = useMutation({ mutationFn: () => rotateAgentRegistrationCredential(agentId), onSuccess: setRegistrationCredential });
  const runtimeKinds = useMemo(() => Array.from(new Set((bindings.data ?? []).map(binding => binding.kind))), [bindings.data]);
  const observedCapabilities = useMemo(() => {
    const counts = new Map<string, number>();
    for (const instance of instances.data ?? []) for (const capability of capabilityList(instance.capabilities)) counts.set(capability, (counts.get(capability) ?? 0) + 1);
    return Array.from(counts.entries()).sort(([left], [right]) => left.localeCompare(right));
  }, [instances.data]);
  const openCapabilityDestination = (destination: CapabilityDestination) => {
    if (destination === 'overview' || destination === 'sessions' || destination === 'related-work') {
      setTab(destination);
      return;
    }
    document.getElementById(destination)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
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
      <PageHeader className="mt-2" title={value.name} description={value.description || 'Enterprise Agent service runtime'} actions={<div className="flex flex-wrap gap-2"><Button onClick={() => setTab('playground')}><Play className="mr-2 h-4 w-4" />Test</Button><Button variant="outline" onClick={() => void queryClient.invalidateQueries({ queryKey: ['catalog-agent-overview', agentId] })}><RefreshCw className="mr-2 h-4 w-4" />Refresh</Button>{runtimeKinds.includes('managed') && canEdit && <Button variant="outline" onClick={() => navigate(scope.scopedPath(`/agent-center/agents/${encodeURIComponent(value.id)}/manage/settings`))}>Manage</Button>}{runtimeKinds.includes('external-application') && canEdit && <Button variant="outline" disabled={rotateCredential.isPending} onClick={() => rotateCredential.mutate()}>Rotate credential</Button>}</div>} />
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

    {tab === 'playground' && <InvocationPlayground initialTargetType="agent" initialTargetRef={agentId} initialTargetLabel={value.name} lockTarget onInvoked={() => void queryClient.invalidateQueries({ queryKey: ['catalog-agent-overview', agentId] })} />}

    {tab === 'sessions' && <div className="grid gap-5 lg:grid-cols-2">{([['Live sessions', liveSessions], ['History', historySessions]] as const).map(([title, items]) => <Card key={title}><CardHeader><CardTitle>{title}</CardTitle><CardDescription>{title === 'Live sessions' ? 'Open a runtime session to inspect messages, events, context, tasks, and commands in read-only mode.' : 'Open an archived or terminated runtime context for its recorded session details.'}</CardDescription></CardHeader><CardContent className="space-y-3">{items.map(session => <Link key={session.id} to={scope.scopedPath(agentSessionDetailPath(agentId, session))} className="block rounded-lg border border-border p-4 hover:bg-muted/40"><div className="flex items-start justify-between gap-3"><div className="min-w-0"><div className="truncate font-medium">{session.sessionId}</div><div className="mt-1 flex flex-wrap gap-1.5"><Badge tone={phaseTone(session.phase)}>{session.phase}</Badge><Badge>{session.originType || 'runtime'}</Badge>{session.framework && <Badge tone="info">{session.framework}</Badge>}</div></div><ExternalLink className="h-4 w-4 text-muted-foreground" /></div><div className="mt-3 flex items-center justify-between gap-3 text-xs text-muted-foreground"><span>{session.instanceRef || 'No instance affinity'} · {formatRelative(session.lastActiveAt || session.startedAt)}</span><PressureGauge value={session.snapshot?.contextPressure} /></div></Link>)}{!sessions.isLoading && !items.length && <EmptyState title={`No ${title.toLowerCase()}`} description="No matching runtime contexts have been reported." className="py-8" />}</CardContent></Card>)}</div>}

    {tab === 'runtime' && <div className="grid gap-5">{(bindings.data ?? []).map(binding => { const rows = (instances.data ?? []).filter(instance => instance.bindingId === binding.id); const configuration = bindingConfiguration(binding.configuration); return <Card key={binding.id}><CardHeader><div className="flex items-start justify-between gap-3"><div><CardTitle>{bindingLabel(binding.kind)}</CardTitle><CardDescription className="mt-1">{bindingDescription(binding.kind)}</CardDescription></div><div className="flex items-center gap-2"><Badge tone={binding.enabled ? 'success' : 'warning'}>{binding.enabled ? 'enabled' : 'disabled'}</Badge>{canEdit && <Button size="sm" variant="outline" disabled={toggle.isPending} onClick={() => toggle.mutate({ binding, enabled: !binding.enabled })}>{binding.enabled ? 'Disable' : 'Enable'}</Button>}</div></div></CardHeader><CardContent className="grid gap-5 lg:grid-cols-[0.8fr_1.2fr]"><div><div className="mb-2 text-sm font-medium">{binding.kind === 'managed' ? 'Definition' : binding.kind === 'external-application' ? 'Registration & routing' : 'Runtime selection'}</div>{binding.kind === 'managed' ? <div className="space-y-3 rounded-lg border p-4 text-sm"><div><span className="text-muted-foreground">Definition reference</span><div className="mt-1 font-mono text-xs">{String(configuration.managedDefinitionRef ?? 'Not configured')}</div></div><div><span className="text-muted-foreground">Owner</span><div className="mt-1">{String(configuration.ownerRef ?? 'Not configured')}</div></div>{canEdit && <Button size="sm" variant="outline" onClick={() => navigate(scope.scopedPath(`/agent-center/agents/${agentId}/manage/settings`))}>Manage definition</Button>}</div> : binding.kind === 'external-application' ? <div className="space-y-3 rounded-lg border p-4 text-sm"><div><span className="text-muted-foreground">Registered instances</span><div className="mt-1 font-medium">{rows.length}</div></div><div><span className="text-muted-foreground">Instance selector</span><JsonViewer value={configuration.instanceSelector ?? {}} className="mt-1 max-h-32" /></div><div className="text-xs text-muted-foreground">Credentials authenticate registration; instance health and capabilities remain runtime-observed.</div></div> : <div className="space-y-3 rounded-lg border p-4 text-sm"><div className="font-medium">Automatically selected</div><div className="text-muted-foreground">AgentScope uses the detected agent runtime selected when this Agent was created.</div><div className="text-xs text-muted-foreground">Processes are launched per execution; availability and capacity are checked automatically.</div></div>}</div><div><div className="mb-2 text-sm font-medium">{binding.kind === 'external-application' ? 'Registered instances' : binding.kind === 'hosted-runtime' ? 'Execution capacity' : 'Service lifecycle'}</div><div className="grid gap-3">{rows.map(instance => <InstanceCard key={instance.id} instance={instance} />)}{!rows.length && <div className="rounded-lg border border-dashed p-5 text-sm text-muted-foreground">{binding.kind === 'hosted-runtime' ? 'On-demand: an available agent runtime is selected when work starts.' : binding.kind === 'managed' ? 'Sessions are created and managed automatically by AgentScope Service.' : 'Not reporting: no external application process is currently connected.'}</div>}</div></div></CardContent></Card>; })}</div>}

    {tab === 'capabilities' && <div className="grid gap-5">
      <Card><CardHeader><CardTitle>Operational capabilities</CardTitle><CardDescription>Capabilities reported by runtime instances, linked to the console workflows they enable.</CardDescription></CardHeader><CardContent>{observedCapabilities.length ? <div className="grid gap-3 xl:grid-cols-2">{observedCapabilities.map(([capability, count]) => { const presentation = describeAgentCapability(capability); return <div key={capability} className="flex min-h-44 flex-col rounded-xl border border-border p-4"><div className="flex flex-wrap items-start justify-between gap-3"><div><div className="font-medium">{presentation.title}</div><code className="mt-1 block text-xs text-muted-foreground">{capability}</code></div><Badge tone={count === (instances.data ?? []).length ? 'success' : 'warning'}>{count}/{(instances.data ?? []).length} instances</Badge></div><p className="mt-3 flex-1 text-sm text-muted-foreground">{presentation.description}</p><div className="mt-4 flex items-center justify-between gap-3"><Badge tone="info">{presentation.category}</Badge>{presentation.destination && presentation.actionLabel ? <Button size="sm" variant="outline" onClick={() => openCapabilityDestination(presentation.destination!)}>{presentation.actionLabel}</Button> : <span className="text-xs text-muted-foreground">No console action</span>}</div></div>; })}</div> : <p className="text-sm text-muted-foreground">No runtime capabilities reported.</p>}</CardContent></Card>
      <div className="grid gap-5 lg:grid-cols-2"><Card><CardHeader><CardTitle>Declared capabilities</CardTitle><CardDescription>Administrator-owned Catalog metadata used for discovery and composition. These labels do not grant runtime operations.</CardDescription></CardHeader><CardContent>{capabilityList(value.catalogCapabilities).length ? <div className="flex flex-wrap gap-2">{capabilityList(value.catalogCapabilities).map(capability => <Badge key={capability} tone="info">{capability}</Badge>)}</div> : <p className="text-sm text-muted-foreground">No declared capabilities.</p>}{value.catalogLabels && <div className="mt-4"><JsonViewer value={value.catalogLabels} className="max-h-48" /></div>}</CardContent></Card><Card><CardHeader><CardTitle>How actions work</CardTitle><CardDescription>Runtime actions are gated by per-session support and your role.</CardDescription></CardHeader><CardContent className="space-y-3 text-sm text-muted-foreground"><p>The instance coverage badge shows whether every observed instance reports the capability.</p><p>Choose a session to see only the controls that its runtime actually supports.</p><Button size="sm" variant="outline" onClick={() => setTab('sessions')}>Open sessions</Button></CardContent></Card></div>
      <Card><CardHeader><CardTitle>Telemetry coverage</CardTitle><CardDescription>Unsupported and unreported signals remain explicit.</CardDescription></CardHeader><CardContent className="grid gap-3 sm:grid-cols-2 lg:grid-cols-3">{Object.entries(currentOverview?.telemetry ?? {}).map(([capability, state]) => <div key={capability} className="flex items-center justify-between rounded-lg border p-3 text-sm"><span>{capability}</span><Badge tone={telemetryTone(state)}>{telemetryLabel(state)}</Badge></div>)}</CardContent></Card>
      <div className="grid gap-5 lg:grid-cols-2"><Card id="subagent-inventory" className="scroll-mt-6"><CardHeader><CardTitle>Subagent inventory</CardTitle><CardDescription>Latest per-instance inventory reported over ASDP.</CardDescription></CardHeader><CardContent className="space-y-3">{(inventory.data?.items ?? []).flatMap(item => (item.subagents ?? []).map(subagent => <div key={`${item.instanceKey}/${subagent.name}`} className="rounded-lg border p-3 text-sm"><div className="font-medium">{subagent.name}</div><div className="mt-1 text-muted-foreground">{subagent.description || 'No description'} · {item.instanceKey}</div></div>))}{!inventory.isLoading && !(inventory.data?.items ?? []).some(item => item.subagents?.length) && <p className="text-sm text-muted-foreground">{telemetryLabel(inventory.data?.status || 'not_reporting')}</p>}</CardContent></Card><Card id="workspace-inventory" className="scroll-mt-6"><CardHeader><CardTitle>Workspace inventory</CardTitle><CardDescription>Runtime-owned workspaces, not Managed Definition configuration.</CardDescription></CardHeader><CardContent className="space-y-3">{(inventory.data?.items ?? []).flatMap(item => (item.workspaces ?? []).map(workspace => <div key={`${item.instanceKey}/${workspace.path}`} className="rounded-lg border p-3 text-sm"><div className="font-mono">{workspace.path}</div><div className="mt-1 text-muted-foreground">{workspace.mode || 'mode n/a'}{workspace.sizeBytes != null ? ` · ${workspace.sizeBytes.toLocaleString()} bytes` : ''}</div></div>))}{!inventory.isLoading && !(inventory.data?.items ?? []).some(item => item.workspaces?.length) && <p className="text-sm text-muted-foreground">{telemetryLabel(inventory.data?.status || 'not_reporting')}</p>}</CardContent></Card></div>
    </div>}

    {tab === 'entrypoints' && <Card><CardHeader><div className="flex items-start justify-between gap-3"><div><CardTitle>Entrypoints</CardTitle><CardDescription>Optional public API contracts targeting this logical Agent.</CardDescription></div><Link to={scope.scopedPath(`/agent-center/endpoints?targetRef=${agentId}`)}><Button variant="outline">Manage endpoints <ExternalLink className="ml-2 h-4 w-4" /></Button></Link></div></CardHeader><CardContent className="space-y-3">{(endpoints.data?.items ?? []).map(endpoint => <Link key={endpoint.id} to={scope.scopedPath(`/agent-center/endpoints/${endpoint.id}`)} className="flex items-center justify-between gap-4 rounded-lg border p-4 transition-colors hover:bg-muted/40"><div><div className="font-medium">{endpoint.name}</div><div className="mt-1 font-mono text-xs text-muted-foreground">/invoke/v1/endpoints/{endpoint.slug}/{endpoint.invocationMode === 'conversation' ? 'conversations' : 'jobs'}</div></div><div className="flex gap-2"><Badge tone="info">{endpoint.invocationMode}</Badge><Badge tone={endpoint.status === 'published' ? 'success' : endpoint.status === 'disabled' ? 'warning' : 'default'}>{endpoint.status}</Badge></div></Link>)}{!endpoints.isLoading && !(endpoints.data?.items ?? []).length && <EmptyState title="No Agent endpoints" description="Create a governed conversation or job API only when business consumers need a stable external address." className="py-10" />}</CardContent></Card>}

    {tab === 'related-work' && <div className="grid gap-5"><Card className="border-indigo-200 bg-indigo-50/50"><CardContent className="pt-6 text-sm text-muted-foreground">These are control-plane AgentTasks linked to Issues, including direct assignment, comment routing, workflow or Endpoint execution, replay, and retry. Runtime-native traffic is shown under Sessions. Counts below cover the currently loaded set of up to 100 tasks.</CardContent></Card><div className="grid gap-4 sm:grid-cols-3"><Card><CardHeader><CardDescription>Active Issue tasks</CardDescription><CardTitle>{(taskCounts.queued ?? 0) + (taskCounts.dispatched ?? 0) + (taskCounts.running ?? 0)}</CardTitle></CardHeader></Card><Card><CardHeader><CardDescription>Completed Issue tasks</CardDescription><CardTitle>{taskCounts.completed ?? 0}</CardTitle></CardHeader></Card><Card><CardHeader><CardDescription>Failed / cancelled Issue tasks</CardDescription><CardTitle>{(taskCounts.failed ?? 0) + (taskCounts.cancelled ?? 0)}</CardTitle></CardHeader></Card></div><Card><CardHeader><CardTitle>Issue-based AgentTasks</CardTitle><CardDescription>Showing up to 10 task records from the currently loaded set. Open a task or its source Issue for details.</CardDescription></CardHeader><CardContent className="space-y-3">{relatedTasks.map(task => <div key={task.id} className="grid gap-3 rounded-lg border p-4 sm:grid-cols-[1fr_auto_auto] sm:items-center"><div><Link className="font-mono text-sm text-primary hover:underline" to={scope.scopedPath(`/agent-center/activity/tasks/${task.id}`)}>{task.id.slice(0, 8)}</Link><div className="mt-1 text-xs text-muted-foreground">{taskTriggerLabel(task.triggerType)} · {formatRelative(task.createdAt)}</div></div><Link className="text-sm hover:underline" to={scope.scopedPath(`/work/issues/${task.issueId}`)}><EntityIdentityText identities={identities} type="issue" entityRef={task.issueId} secondary /></Link><Badge tone={task.status === 'completed' ? 'success' : task.status === 'failed' ? 'danger' : 'warning'}>{task.status}</Badge></div>)}{!tasks.isLoading && !relatedTasks.length && <EmptyState title="No Issue-based AgentTasks" description="This Agent has no loaded control-plane task records. Runtime-native traffic may still appear under Sessions." className="py-10" />}</CardContent></Card></div>}

    {tab === 'settings' && (runtimeKinds.includes('hosted-runtime')
      ? <HostedAgentSettings agent={value} canEdit={canEdit} />
      : <Card><CardHeader><CardTitle>Agent settings</CardTitle><CardDescription>This runtime keeps its settings in the managed definition editor.</CardDescription></CardHeader><CardContent>{canEdit && <Button variant="outline" onClick={() => navigate(scope.scopedPath(`/agent-center/agents/${agentId}/manage/settings`))}>Open definition settings</Button>}</CardContent></Card>)}
  </Page>;
}
