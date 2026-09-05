import { useQuery } from '@tanstack/react-query';
import { ExternalLink, Play, RotateCcw } from 'lucide-react';
import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { listEndpoints, type Endpoint, continueEndpointConversation, invokeEndpointJob, startEndpointConversation } from '@/api/agentEndpoints';
import { listAgents } from '@/api/agents';
import { getRoles } from '@/api/auth';
import { listTeams } from '@/api/collaboration';
import { getRunGraph, listDefinitions, listRunEvents, type RunEvent, type RunGraph } from '@/api/orchestration';
import {
  continuePlaygroundConversation,
  getAgentInvocationCapabilities,
  invokePlayground,
  type PlaygroundMode,
  type PlaygroundTargetType,
} from '@/api/playground';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { ConversationSurface } from '@/features/conversation/ConversationSurface';
import {
  invocationResultEvent,
  resultText,
  runtimeEventsToConversation,
  runtimeEventsToMessages,
} from '@/features/conversation/adapters';
import type { ConversationEvent, ConversationMessage } from '@/features/conversation/model';
import { useSessionEvents } from '@/features/operate/lib/useSessionEvents';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';

type TargetKind = PlaygroundTargetType | 'endpoint';

export interface InvocationPlaygroundProps {
  initialTargetType?: PlaygroundTargetType;
  initialTargetRef?: string;
  initialTargetLabel?: string;
  lockTarget?: boolean;
  endpoint?: Endpoint;
  onInvoked?: () => void;
}

function stateTone(state?: string): 'success' | 'warning' | 'danger' | 'default' {
  if (state === 'available' || state === 'ready') return 'success';
  if (state === 'partial') return 'warning';
  if (state === 'unavailable' || state === 'not_supported') return 'danger';
  return 'default';
}

function hasSessionTranscript(mode: PlaygroundMode, sessionRef: string, agentId: string): boolean {
  return mode === 'conversation' && !!sessionRef && !!agentId;
}

const terminalRunStates = new Set(['cancelled', 'succeeded', 'partial_succeeded', 'failed']);

function isTerminalRun(graph?: RunGraph): boolean {
  return !!graph && terminalRunStates.has(graph.run.state);
}

function outputOf(value: unknown): string {
  if (typeof value === 'string') return value;
  if (!value || typeof value !== 'object') return '';
  const record = value as Record<string, unknown>;
  if (typeof record.output === 'string') return record.output;
  if (typeof record.result === 'string') return record.result;
  return '';
}

function runEventsToConversation(events: RunEvent[]): ConversationEvent[] {
  return events.map((event) => {
    const payload = event.payload && typeof event.payload === 'object'
      ? event.payload as Record<string, unknown>
      : undefined;
    const providerType = typeof payload?.eventType === 'string' ? payload.eventType : '';
    const failed = event.type.includes('failed') || event.type.includes('error');
    return {
      id: `run-event-${event.id}`,
      seq: event.sequence,
      type: providerType ? `${event.type}:${providerType}` : event.type,
      category: failed ? 'error' : event.type.startsWith('attempt.') ? 'lifecycle' : 'other',
      occurredAt: event.occurredAt,
      summary: providerType || event.type,
      payload: event.payload,
    };
  });
}

export function InvocationPlayground({
  initialTargetType = 'agent',
  initialTargetRef = '',
  initialTargetLabel,
  lockTarget = false,
  endpoint: fixedEndpoint,
  onInvoked,
}: InvocationPlaygroundProps) {
  const scope = useControlPlaneScope();
  const roles = getRoles().map(role => role.toLowerCase());
  const canInvoke = roles.includes('admin') || roles.includes('agent_developer');
  const [targetType, setTargetType] = useState<TargetKind>(fixedEndpoint ? 'endpoint' : initialTargetType);
  const [targetRef, setTargetRef] = useState(fixedEndpoint?.id ?? initialTargetRef);
  const [mode, setMode] = useState<PlaygroundMode>(fixedEndpoint?.invocationMode ?? 'conversation');
  const [message, setMessage] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [sessionId, setSessionId] = useState('');
  const [sessionRef, setSessionRef] = useState('');
  const [endpointConversationId, setEndpointConversationId] = useState('');
  const [result, setResult] = useState<Record<string, unknown> | null>(null);
  const [localMessages, setLocalMessages] = useState<ConversationMessage[]>([]);
  const [invocationEvents, setInvocationEvents] = useState<ConversationEvent[]>([]);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
	const [resolvedJobRunId, setResolvedJobRunId] = useState('');

  const agents = useQuery({ queryKey: ['playground-agents', scope.tenant, scope.namespace], queryFn: () => listAgents(scope.tenant, scope.namespace), enabled: !lockTarget && !fixedEndpoint });
  const teams = useQuery({ queryKey: ['playground-teams', scope.tenant, scope.namespace], queryFn: () => listTeams(scope.tenant, scope.namespace), enabled: !lockTarget && !fixedEndpoint });
  const workflows = useQuery({ queryKey: ['playground-workflows', scope.tenant, scope.namespace], queryFn: () => listDefinitions(scope.tenant, scope.namespace), enabled: !lockTarget && !fixedEndpoint });
  const endpoints = useQuery({ queryKey: ['playground-endpoints', scope.tenant, scope.namespace], queryFn: () => listEndpoints(scope.tenant, scope.namespace), enabled: !lockTarget && !fixedEndpoint });
  const selectedEndpoint = fixedEndpoint ?? endpoints.data?.items.find(item => item.id === targetRef);
  const sessionAgentId = targetType === 'agent'
    ? targetRef
    : targetType === 'endpoint' && selectedEndpoint?.targetType === 'agent'
      ? selectedEndpoint.targetRef
      : '';
  const capabilities = useQuery({
    queryKey: ['agent-invocation-capabilities', targetRef, scope.tenant, scope.namespace],
    queryFn: () => getAgentInvocationCapabilities(targetRef),
    enabled: targetType === 'agent' && !!targetRef,
  });
  const timeline = useSessionEvents(sessionRef, {
    agentId: sessionAgentId,
    enabled: hasSessionTranscript(mode, sessionRef, sessionAgentId),
  });
	const jobRunId = mode === 'job' && typeof result?.runId === 'string' ? result.runId : '';
	const jobGraph = useQuery({
		queryKey: ['playground-run-graph', jobRunId],
		queryFn: () => getRunGraph(jobRunId),
		enabled: !!jobRunId,
		refetchInterval: query => isTerminalRun(query.state.data) ? false : 1500,
		refetchIntervalInBackground: false,
	});
	const jobEvents = useQuery({
		queryKey: ['playground-run-events', jobRunId],
		queryFn: () => listRunEvents(jobRunId),
		enabled: !!jobRunId,
		refetchInterval: () => isTerminalRun(jobGraph.data) ? false : 1500,
		refetchIntervalInBackground: false,
	});

  const options = useMemo(() => {
    if (targetType === 'agent') return (agents.data ?? []).map(item => ({ id: item.id, label: item.name }));
    if (targetType === 'team') return (teams.data?.items ?? []).map(item => ({ id: item.id, label: item.name }));
    if (targetType === 'workflow') return (workflows.data?.definitions ?? []).map(item => ({ id: item.id, label: item.name }));
    return (endpoints.data?.items ?? []).map(item => ({ id: item.id, label: item.name }));
  }, [agents.data, endpoints.data, targetType, teams.data, workflows.data]);

  useEffect(() => {
    if (fixedEndpoint) {
      setMode(fixedEndpoint.invocationMode);
      return;
    }
    if (targetType !== 'agent') setMode('job');
    setSessionId('');
    setSessionRef('');
    setEndpointConversationId('');
    setResult(null);
    setLocalMessages([]);
    setInvocationEvents([]);
  }, [fixedEndpoint, targetType, targetRef]);

  useEffect(() => {
    if (targetType === 'endpoint' && selectedEndpoint) {
      setMode(selectedEndpoint.invocationMode);
    }
  }, [selectedEndpoint, targetType]);

	useEffect(() => {
		const graph = jobGraph.data;
		if (!jobRunId || !graph || !isTerminalRun(graph) || resolvedJobRunId === jobRunId) return;
		const taskResult = [...graph.tasks].reverse().map(task => outputOf(task.result)).find(Boolean);
		const attemptResult = [...graph.attempts].reverse().map(attempt => outputOf(attempt.result)).find(Boolean);
		const runResult = outputOf(graph.run.output);
		const attemptFailure = [...graph.attempts].reverse().find(attempt => attempt.failureMessage);
		const failure = attemptFailure?.failureMessage || graph.run.failureMessage;
		const content = taskResult || attemptResult || runResult || failure ||
			(graph.run.state === 'succeeded' ? 'Job completed without text output.' : `Job finished with state ${graph.run.state}.`);
		const failed = graph.run.state === 'failed' || graph.run.state === 'cancelled';
		setLocalMessages(current => [...current, {
			id: `playground-job-${jobRunId}`,
			role: failed ? 'error' : 'assistant',
			blocks: [{ kind: 'text', id: `playground-job-text-${jobRunId}`, text: content }],
			occurredAt: graph.run.completedAt || new Date().toISOString(),
			state: failed ? 'error' : 'complete',
			raw: graph,
		}]);
		setResolvedJobRunId(jobRunId);
	}, [jobGraph.data, jobRunId, resolvedJobRunId]);

  const direct = targetType !== 'endpoint';
  const modeCapability = targetType === 'agent' ? capabilities.data?.capabilities[mode] : undefined;
  const unavailable = !!modeCapability && modeCapability.state !== 'available';

  async function submit(content: string) {
    if (!targetRef || !content.trim()) return;
    setSubmitting(true);
    setError('');
    const submittedAt = new Date().toISOString();
    setLocalMessages((current) => [...current, {
      id: `playground-user-${Date.now()}`,
      role: 'user',
      blocks: [{ kind: 'text', id: `playground-user-text-${Date.now()}`, text: content.trim() }],
      occurredAt: submittedAt,
      state: 'complete',
    }]);
    try {
      let next: Record<string, unknown>;
      if (!direct) {
        if (!selectedEndpoint) throw new Error('Choose an Endpoint');
        next = selectedEndpoint.invocationMode === 'job'
          ? await invokeEndpointJob(selectedEndpoint, apiKey, { title: `${selectedEndpoint.name} invocation`, input: { prompt: content.trim() } })
          : endpointConversationId
            ? await continueEndpointConversation(endpointConversationId, apiKey, content.trim())
            : await startEndpointConversation(selectedEndpoint, apiKey, content.trim());
        if (typeof next.conversationId === 'string') setEndpointConversationId(next.conversationId);
      } else if (mode === 'conversation' && sessionId) {
        next = await continuePlaygroundConversation(sessionId, {
          tenant: scope.tenant, namespace: scope.namespace, agentId: targetRef, message: content.trim(),
        });
      } else {
        next = await invokePlayground({
          tenant: scope.tenant, namespace: scope.namespace,
          targetType: targetType as PlaygroundTargetType, targetRef, mode,
          message: content.trim(), title: 'Playground job', input: { prompt: content.trim() },
        });
      }
      if (typeof next.sessionId === 'string') setSessionId(next.sessionId);
      if (typeof next.sessionRef === 'string') setSessionRef(next.sessionRef);
      const output = resultText(next);
      if (output) {
        setLocalMessages((current) => [...current, {
          id: `playground-assistant-${Date.now()}`,
          role: 'assistant',
          blocks: [{ kind: 'text', id: `playground-assistant-text-${Date.now()}`, text: output }],
          occurredAt: new Date().toISOString(),
          state: 'complete',
          raw: next,
        }]);
      }
      setInvocationEvents((current) => [...current, invocationResultEvent(next, current.length)]);
      setResult(next);
		if (typeof next.runId === 'string') setResolvedJobRunId('');
      setMessage('');
      onInvoked?.();
    } catch (cause) {
      const failure = cause instanceof Error ? cause.message : 'Invocation failed';
      setError(failure);
      setLocalMessages((current) => [...current, {
        id: `playground-error-${Date.now()}`,
        role: 'error',
        blocks: [{ kind: 'text', id: `playground-error-text-${Date.now()}`, text: failure }],
        occurredAt: new Date().toISOString(),
        state: 'error',
      }]);
    } finally {
      setSubmitting(false);
    }
  }

  const liveMessages = runtimeEventsToMessages(timeline.events);
  const displayedMessages = liveMessages.length > 0 ? liveMessages : localMessages;
  const displayedEvents = [
    ...runtimeEventsToConversation(timeline.events),
	...runEventsToConversation(jobEvents.data?.events ?? []),
    ...invocationEvents,
  ];
	const jobActive = !!jobRunId && !isTerminalRun(jobGraph.data);
	const latestAttempt = jobGraph.data?.attempts[jobGraph.data.attempts.length - 1];

  function resetConversation() {
    setSessionId('');
    setSessionRef('');
    setEndpointConversationId('');
    setResult(null);
    setLocalMessages([]);
    setInvocationEvents([]);
	setResolvedJobRunId('');
    setError('');
  }

  return <Card>
    <CardHeader>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div><CardTitle className="flex items-center gap-2"><Play className="h-4 w-4" />{direct ? 'Direct test' : 'Test published API'}</CardTitle><CardDescription>{direct ? 'Test the target with Console authorization. Runtime policy and Binding selection are still enforced.' : 'Exercise the published API through its real Gateway authentication, schema, rate limit, and release.'}</CardDescription></div>
        <Badge tone={direct ? 'info' : 'default'}>{direct ? 'control-plane route' : 'public route'}</Badge>
      </div>
    </CardHeader>
    <CardContent className="grid gap-4">
        {!lockTarget && !fixedEndpoint && <div className="grid gap-3 md:grid-cols-2">
          <label className="grid gap-1 text-sm">Target type<select className="h-10 rounded-md border bg-background px-3" value={targetType} onChange={event => { setTargetType(event.target.value as TargetKind); setTargetRef(''); resetConversation(); }}><option value="agent">Agent</option><option value="team">Team</option><option value="workflow">Workflow</option><option value="endpoint">Endpoint</option></select></label>
          <label className="grid gap-1 text-sm">Target<select className="h-10 rounded-md border bg-background px-3" value={targetRef} onChange={event => setTargetRef(event.target.value)}><option value="">Choose…</option>{options.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}</select></label>
        </div>}
        {(lockTarget || fixedEndpoint) && <div className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/30 p-3 text-sm"><span className="text-muted-foreground">Target</span><strong>{fixedEndpoint?.name ?? initialTargetLabel ?? targetRef}</strong><Badge>{fixedEndpoint ? 'endpoint' : initialTargetType}</Badge></div>}
        {direct && <div className="grid gap-3 md:grid-cols-2">
          <label className="grid gap-1 text-sm">Mode<select className="h-10 rounded-md border bg-background px-3" value={mode} disabled={targetType !== 'agent'} onChange={event => { setMode(event.target.value as PlaygroundMode); resetConversation(); }}><option value="conversation">Conversation</option><option value="job">Job</option></select></label>
          <div className="rounded-lg border p-3 text-sm"><div className="flex items-center gap-2"><span className="text-muted-foreground">Capability</span><Badge tone={stateTone(modeCapability?.state)}>{modeCapability?.state ?? (targetType === 'agent' ? 'checking' : 'available')}</Badge></div><p className="mt-1 text-xs text-muted-foreground">{modeCapability?.reason ?? (targetType === 'agent' ? 'Inspecting runtime candidates…' : 'Team and Workflow tests use job execution.')}</p></div>
        </div>}
        {!direct && <Input type="password" value={apiKey} onChange={event => setApiKey(event.target.value)} placeholder="Endpoint API key" />}
        {(sessionId || endpointConversationId) && mode === 'conversation' && <div className="flex flex-wrap items-center gap-2 rounded-lg bg-sky-50 p-3 text-xs text-sky-900">Continuing session <code>{sessionId || endpointConversationId}</code><Button type="button" size="sm" variant="ghost" onClick={resetConversation}><RotateCcw className="h-3 w-3" />Start new</Button></div>}
        {error && <p className="text-sm text-red-600">{error}</p>}
        {!canInvoke && <p className="text-sm text-amber-700">Your Agent Center access is read-only. Agent developer or administrator permission is required to invoke tests.</p>}
        {!direct && selectedEndpoint?.status !== 'published' && <span className="text-xs text-amber-700">Publish the Endpoint before invoking it.</span>}
		{jobRunId && <div className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/30 p-3 text-sm">
			<span className="text-muted-foreground">Hosted job</span>
			<Badge tone={jobGraph.data?.run.state === 'failed' ? 'danger' : isTerminalRun(jobGraph.data) ? 'success' : 'info'}>{jobGraph.data?.run.state ?? 'submitted'}</Badge>
			{latestAttempt && <><span className="text-muted-foreground">Attempt</span><code>{latestAttempt.id.slice(0, 8)}</code><Badge>{latestAttempt.state}</Badge></>}
			{latestAttempt?.providerSessionId && <><span className="text-muted-foreground">Provider session</span><code>{latestAttempt.providerSessionId.slice(0, 12)}</code></>}
			<span className="ml-auto text-xs text-muted-foreground">{jobActive ? 'Live updates every 1.5s' : 'Polling stopped'}</span>
		</div>}
        <ConversationSurface
          className="min-h-[36rem] max-h-[72vh]"
          messages={displayedMessages}
          events={displayedEvents}
          source={sessionRef ? 'event stream' : 'API test'}
		  loading={timeline.loading || (!!jobRunId && jobGraph.isLoading)}
		  error={timeline.error || (jobGraph.error instanceof Error ? jobGraph.error.message : '')}
          emptyMessage={targetRef ? 'Send a message to start a test conversation.' : 'Choose a target to begin.'}
          headerActions={result && <div className="flex flex-wrap gap-2">{typeof result.issueId === 'string' && <Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(`/work/issues/${result.issueId}`)}>Issue<ExternalLink className="h-3 w-3" /></Link></Button>}{typeof result.runId === 'string' && <Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(`/work/executions/${result.runId}`)}>Execution<ExternalLink className="h-3 w-3" /></Link></Button>}{(typeof result.sessionRef === 'string' || typeof result.sessionId === 'string') && <Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(`/work/sessions/${String(result.sessionRef || result.sessionId)}`)}>Session<ExternalLink className="h-3 w-3" /></Link></Button>}</div>}
          composer={{
            value: message,
            onChange: setMessage,
            onSubmit: submit,
			busy: submitting || jobActive,
			disabled: jobActive || !canInvoke || !targetRef || unavailable || (!direct && (!apiKey || selectedEndpoint?.status !== 'published')),
            placeholder: mode === 'job' ? 'Describe the job to run…' : 'Send a message…',
          }}
          hasEarlierMessages={timeline.hasEarlier}
          loadingEarlierMessages={timeline.loadingEarlier}
          onLoadEarlierMessages={() => void timeline.loadEarlier()}
          hasEarlierEvents={timeline.hasEarlier}
          loadingEarlierEvents={timeline.loadingEarlier}
          onLoadEarlierEvents={() => void timeline.loadEarlier()}
        />
    </CardContent>
  </Card>;
}
