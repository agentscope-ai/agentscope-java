import { FormEvent, useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  AgentEndpoint,
  createAgentEndpoint,
  invokeAgentEndpoint,
  listAgentEndpoints,
  patchAgentEndpoint,
} from '@/api/agentEndpoints';
import { listAgents } from '@/api/agents';
import { getRoles } from '@/api/auth';
import { listTeams } from '@/api/collaboration';
import { listDefinitions, listRevisions } from '@/api/orchestration';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/EmptyState';
import { Input } from '@/components/ui/input';
import { Page, PageHeader } from '@/components/Page';
import { useControlPlaneScope } from '@/app/ScopeContext';

type TargetOption = { type: AgentEndpoint['targetType']; id: string; label: string };

export default function DeploymentsPage() {
  const queryClient = useQueryClient();
  const scope = useControlPlaneScope();
  const roles = getRoles().map(role => role.toLowerCase());
  const canEdit = roles.includes('admin') || roles.includes('agent_developer');
  const endpoints = useQuery({
    queryKey: ['agent-endpoints', scope.tenant, scope.namespace],
    queryFn: () => listAgentEndpoints(scope.tenant, scope.namespace),
  });
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState('');
  const [slug, setSlug] = useState('');
  const [mode, setMode] = useState<AgentEndpoint['invocationMode']>('job');
  const [targetType, setTargetType] = useState<AgentEndpoint['targetType']>('agent');
  const [targetRef, setTargetRef] = useState('');
  const [targetOptions, setTargetOptions] = useState<TargetOption[]>([]);
  const [credential, setCredential] = useState('');
  const [credentialEndpoint, setCredentialEndpoint] = useState<AgentEndpoint | null>(null);
  const [playgroundText, setPlaygroundText] = useState('');
  const [playgroundResult, setPlaygroundResult] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    let cancelled = false;
    void Promise.all([
      listAgents(scope.tenant, scope.namespace),
      listTeams(scope.tenant, scope.namespace),
      listDefinitions(scope.tenant, scope.namespace),
    ])
      .then(async ([agents, teams, definitions]) => {
        const revisions = await Promise.all(definitions.definitions.map(async definition => ({
          definition,
          revisions: (await listRevisions(definition.id)).revisions,
        })));
        if (cancelled) return;
        setTargetOptions([
          ...agents.filter(agent => agent.status === 'active').map(agent => ({
            type: 'agent' as const, id: agent.id, label: `Agent · ${agent.name}`,
          })),
          ...teams.items.map(team => ({ type: 'team' as const, id: team.id, label: `Team · ${team.name}` })),
          ...revisions.flatMap(({ definition, revisions: values }) => values.map(revision => ({
            type: 'orchestration_revision' as const,
            id: revision.id,
            label: `Workflow · ${definition.name} r${revision.revision}`,
          }))),
        ]);
      })
      .catch(cause => {
        if (!cancelled) setError(cause instanceof Error ? cause.message : 'Failed to load Endpoint targets');
      });
    return () => { cancelled = true; };
  }, [scope.tenant, scope.namespace]);

  const matchingTargets = useMemo(
    () => targetOptions.filter(option => option.type === targetType),
    [targetOptions, targetType],
  );

  const create = useMutation({
    mutationFn: createAgentEndpoint,
    onSuccess: result => {
      setCredential(result.credential);
      setCredentialEndpoint(result.endpoint);
      setCreating(false);
      setName('');
      setSlug('');
      setTargetRef('');
      void queryClient.invalidateQueries({ queryKey: ['agent-endpoints'] });
    },
    onError: cause => setError(cause instanceof Error ? cause.message : 'Endpoint creation failed'),
  });
  const patch = useMutation({
    mutationFn: ({ endpoint, body }: {
      endpoint: AgentEndpoint;
      body: { enabled?: boolean; rotateCredential?: boolean };
    }) => patchAgentEndpoint(endpoint, body),
    onSuccess: (result, variables) => {
      if (result.credential) {
        setCredential(result.credential);
        setCredentialEndpoint(variables.endpoint);
      }
      void queryClient.invalidateQueries({ queryKey: ['agent-endpoints'] });
    },
    onError: cause => setError(cause instanceof Error ? cause.message : 'Endpoint update failed'),
  });

  function submit(event: FormEvent) {
    event.preventDefault();
    setError('');
    create.mutate({
      tenant: scope.tenant,
      namespace: scope.namespace,
      name: name.trim(),
      slug: slug.trim(),
      targetType,
      targetRef,
      invocationMode: mode,
      rateLimit: { requests: 60, windowSeconds: 60 },
    });
  }

  async function runPlayground(endpoint: AgentEndpoint) {
    setError('');
    setPlaygroundResult('');
    try {
      const result = await invokeAgentEndpoint(
        endpoint,
        credential,
        endpoint.invocationMode === 'conversation'
          ? { message: playgroundText }
          : { title: playgroundText || 'Playground job', input: { prompt: playgroundText } },
      );
      setPlaygroundResult(JSON.stringify(result, null, 2));
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Invocation failed');
    }
  }

  return (
    <Page>
      <PageHeader
        title="Applications & Endpoints"
        description="Publish an Agent, Team, or Workflow through the same authenticated Gateway path used by the Console Playground."
        actions={canEdit ? <Button onClick={() => setCreating(value => !value)}>New endpoint</Button> : undefined}
      />
      {error && <p className="text-sm text-red-600">{error}</p>}

      {credentialEndpoint && credential && (
        <Card className="border-amber-300 bg-amber-50">
          <CardHeader>
            <CardTitle>Save the credential now</CardTitle>
            <CardDescription>It is returned once for {credentialEndpoint.name}; only its hash is stored.</CardDescription>
          </CardHeader>
          <CardContent className="grid gap-3">
            <code className="break-all rounded border bg-white p-3 text-xs">{credential}</code>
            <Button variant="outline" onClick={() => void navigator.clipboard.writeText(credential)}>Copy credential</Button>
          </CardContent>
        </Card>
      )}

      {creating && (
        <Card>
          <CardHeader><CardTitle>Create AgentEndpoint</CardTitle></CardHeader>
          <CardContent>
            <form className="grid gap-4 md:grid-cols-2" onSubmit={submit}>
              <label className="grid gap-1 text-sm">Name<Input required value={name} onChange={event => setName(event.target.value)} /></label>
              <label className="grid gap-1 text-sm">Slug<Input required value={slug} onChange={event => setSlug(event.target.value.toLowerCase().replace(/[^a-z0-9-]/g, '-'))} placeholder="research-agent" /></label>
              <label className="grid gap-1 text-sm">Mode<select className="h-10 rounded-lg border px-3" value={mode} onChange={event => { const next = event.target.value as AgentEndpoint['invocationMode']; setMode(next); if (next === 'conversation') setTargetType('agent'); }}><option value="job">Job</option><option value="conversation">Conversation</option></select></label>
              <label className="grid gap-1 text-sm">Target type<select className="h-10 rounded-lg border px-3" value={targetType} disabled={mode === 'conversation'} onChange={event => { setTargetType(event.target.value as AgentEndpoint['targetType']); setTargetRef(''); }}><option value="agent">Agent</option><option value="team">Team</option><option value="orchestration_revision">Workflow revision</option></select></label>
              <label className="grid gap-1 text-sm md:col-span-2">Target<select required className="h-10 rounded-lg border px-3" value={targetRef} onChange={event => setTargetRef(event.target.value)}><option value="">Select target…</option>{matchingTargets.map(option => <option key={option.id} value={option.id}>{option.label}</option>)}</select></label>
              <div className="flex gap-2 md:col-span-2"><Button type="submit" disabled={create.isPending || !name.trim() || !slug.trim() || !targetRef}>Create</Button><Button type="button" variant="outline" onClick={() => setCreating(false)}>Cancel</Button></div>
            </form>
          </CardContent>
        </Card>
      )}

      {endpoints.isLoading ? <p className="text-sm text-muted-foreground">Loading…</p> : !(endpoints.data?.items.length) ? (
        <EmptyState title="No endpoints" description="Create a conversation or job Endpoint to publish an AgentScope capability." />
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {endpoints.data.items.map(endpoint => (
            <Card key={endpoint.id}>
              <CardHeader>
                <div className="flex items-start justify-between gap-3">
                  <div><CardTitle>{endpoint.name}</CardTitle><CardDescription>/invoke/v1/endpoints/{endpoint.slug}/{endpoint.invocationMode === 'job' ? 'jobs' : 'conversations'}</CardDescription></div>
                  <Badge tone={endpoint.enabled ? 'success' : 'warning'}>{endpoint.enabled ? 'enabled' : 'disabled'}</Badge>
                </div>
              </CardHeader>
              <CardContent className="grid gap-3 text-sm">
                <div>{endpoint.invocationMode} · {endpoint.targetType} · <code>{endpoint.targetRef}</code></div>
                {canEdit && (
                  <div className="flex flex-wrap gap-2">
                    <Button size="sm" variant="outline" onClick={() => patch.mutate({ endpoint, body: { enabled: !endpoint.enabled } })}>{endpoint.enabled ? 'Disable' : 'Enable'}</Button>
                    <Button size="sm" variant="outline" onClick={() => patch.mutate({ endpoint, body: { rotateCredential: true } })}>Rotate key</Button>
                    <Button size="sm" variant="outline" onClick={() => { setCredentialEndpoint(endpoint); setCredential(''); setPlaygroundText(''); setPlaygroundResult(''); }}>Playground</Button>
                  </div>
                )}
                {credentialEndpoint?.id === endpoint.id && (
                  <div className="grid gap-2 rounded-lg border bg-slate-50 p-3">
                    <Input type="password" value={credential} onChange={event => setCredential(event.target.value)} placeholder="Endpoint API key" />
                    <Input value={playgroundText} onChange={event => setPlaygroundText(event.target.value)} placeholder={endpoint.invocationMode === 'job' ? 'Job title / prompt' : 'Message'} />
                    <Button size="sm" disabled={!credential || !playgroundText} onClick={() => void runPlayground(endpoint)}>Invoke public path</Button>
                    {playgroundResult && <pre className="overflow-auto whitespace-pre-wrap text-xs">{playgroundResult}</pre>}
                  </div>
                )}
              </CardContent>
            </Card>
          ))}
        </div>
      )}
    </Page>
  );
}
