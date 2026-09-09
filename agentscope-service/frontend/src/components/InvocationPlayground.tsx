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

import { useQuery } from '@tanstack/react-query';
import { ExternalLink, Play, RotateCcw } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import {
  continueEndpointConversation,
  invokeEndpointJob,
  listEndpointCredentials,
  revealEndpointCredential,
  startEndpointConversation,
  type Endpoint,
} from '@/api/agentEndpoints';
import { getRoles } from '@/api/auth';
import { getRunGraph, listRunEvents, type RunEvent, type RunGraph } from '@/api/orchestration';
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
import { selectEndpointTestCredential } from '@/components/endpointTestCredential';

export interface InvocationPlaygroundProps {
  endpoint: Endpoint;
  onInvoked?: () => void;
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
  endpoint,
  onInvoked,
}: InvocationPlaygroundProps) {
  const scope = useControlPlaneScope();
  const roles = getRoles().map(role => role.toLowerCase());
  const canInvoke = roles.includes('admin') || roles.includes('agent_developer');
  const [message, setMessage] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [credentialLoading, setCredentialLoading] = useState(false);
  const [credentialName, setCredentialName] = useState('');
  const [credentialError, setCredentialError] = useState('');
  const [sessionId, setSessionId] = useState('');
  const [sessionRef, setSessionRef] = useState('');
  const [endpointConversationId, setEndpointConversationId] = useState('');
  const [result, setResult] = useState<Record<string, unknown> | null>(null);
  const [localMessages, setLocalMessages] = useState<ConversationMessage[]>([]);
  const [invocationEvents, setInvocationEvents] = useState<ConversationEvent[]>([]);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
	const [resolvedJobRunId, setResolvedJobRunId] = useState('');
  const mode = endpoint.invocationMode;
  const endpointId = endpoint.id;
  const endpointAuthType = endpoint.authPolicy?.type ?? 'api_key';
  const sessionAgentId = endpoint.targetType === 'agent' ? endpoint.targetRef : '';
  const timeline = useSessionEvents(sessionRef, {
    agentId: sessionAgentId,
    enabled: mode === 'conversation' && !!sessionRef && !!sessionAgentId,
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
  useEffect(() => {
    setApiKey('');
    setCredentialName('');
    setCredentialError('');
    setCredentialLoading(false);
    if (!canInvoke || endpoint.status !== 'published' || endpointAuthType === 'platform') return;

    let cancelled = false;
    setCredentialLoading(true);
    listEndpointCredentials(endpointId)
      .then(({ items }) => {
        const credential = selectEndpointTestCredential(items);
        if (!credential) {
          throw new Error('No active recoverable API credential is available. Create or rotate one in Security, or enter a key below.');
        }
        return revealEndpointCredential(endpointId, credential.id)
          .then(({ secret }) => ({ secret, name: credential.name }));
      })
      .then(({ secret, name }) => {
        if (cancelled) return;
        setApiKey(secret);
        setCredentialName(name);
      })
      .catch((cause) => {
        if (!cancelled) setCredentialError(cause instanceof Error ? cause.message : 'Unable to load an Endpoint credential automatically.');
      })
      .finally(() => {
        if (!cancelled) setCredentialLoading(false);
      });
    return () => { cancelled = true; };
  }, [canInvoke, endpoint.status, endpointAuthType, endpointId]);

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

  async function submit(content: string) {
    if (!content.trim()) return;
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
      const next = endpoint.invocationMode === 'job'
          ? await invokeEndpointJob(endpoint, apiKey, { title: `${endpoint.name} invocation`, input: { prompt: content.trim() } })
          : endpointConversationId
            ? await continueEndpointConversation(endpoint, endpointConversationId, apiKey, content.trim())
            : await startEndpointConversation(endpoint, apiKey, content.trim());
      if (typeof next.conversationId === 'string') setEndpointConversationId(next.conversationId);
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
        <div><CardTitle className="flex items-center gap-2"><Play className="h-4 w-4" />Test published API</CardTitle><CardDescription>Exercise the published API through its real Gateway authentication, schema, rate limit, and release.</CardDescription></div>
        <Badge>public route</Badge>
      </div>
    </CardHeader>
    <CardContent className="grid gap-4">
        <div className="flex flex-wrap items-center gap-2 rounded-lg border bg-muted/30 p-3 text-sm"><span className="text-muted-foreground">Target</span><strong>{endpoint.name}</strong><Badge>endpoint</Badge></div>
        {endpointAuthType === 'api_key' && <label className="grid gap-1 text-sm">
          API credential
          <Input
            type="password"
            value={apiKey}
            onChange={event => { setApiKey(event.target.value); setCredentialName(''); }}
            placeholder={credentialLoading ? 'Loading credential…' : 'Endpoint API key'}
          />
          <span className={`text-xs ${credentialError ? 'text-amber-700' : 'text-muted-foreground'}`}>
            {credentialLoading
              ? 'Loading the latest active credential from Endpoint Security…'
              : credentialName
                ? `Using “${credentialName}” from Endpoint Security automatically.`
                : credentialError || 'Enter an Endpoint API key.'}
          </span>
        </label>}
        {endpointAuthType === 'platform' && <div className="rounded-lg border bg-muted/30 p-3 text-sm">
          <div className="font-medium">Console credential</div>
          <p className="mt-1 text-xs text-muted-foreground">The current signed-in credential is used automatically for this Platform-authenticated Endpoint.</p>
        </div>}
        {(sessionId || endpointConversationId) && mode === 'conversation' && <div className="flex flex-wrap items-center gap-2 rounded-lg bg-sky-50 p-3 text-xs text-sky-900">Continuing session <code>{sessionId || endpointConversationId}</code><Button type="button" size="sm" variant="ghost" onClick={resetConversation}><RotateCcw className="h-3 w-3" />Start new</Button></div>}
        {error && <p className="text-sm text-red-600">{error}</p>}
        {!canInvoke && <p className="text-sm text-amber-700">Your Agent Center access is read-only. Agent developer or administrator permission is required to invoke tests.</p>}
        {endpoint.status !== 'published' && <span className="text-xs text-amber-700">Publish the Endpoint before invoking it.</span>}
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
          emptyMessage="Send a message to start a test conversation."
          headerActions={result && <div className="flex flex-wrap gap-2">{typeof result.issueId === 'string' && <Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(`/work/issues/${result.issueId}`)}>Issue<ExternalLink className="h-3 w-3" /></Link></Button>}{typeof result.runId === 'string' && <Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(`/work/executions/${result.runId}`)}>Execution<ExternalLink className="h-3 w-3" /></Link></Button>}{(typeof result.sessionRef === 'string' || typeof result.sessionId === 'string') && <Button asChild size="sm" variant="outline"><Link to={scope.scopedPath(`/work/sessions/${String(result.sessionRef || result.sessionId)}`)}>Session<ExternalLink className="h-3 w-3" /></Link></Button>}</div>}
          composer={{
            value: message,
            onChange: setMessage,
            onSubmit: submit,
			busy: submitting || jobActive,
			disabled: jobActive || credentialLoading || !canInvoke || endpoint.status !== 'published' || (endpointAuthType === 'api_key' && !apiKey),
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
