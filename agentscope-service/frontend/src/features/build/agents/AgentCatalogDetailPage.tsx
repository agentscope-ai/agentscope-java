import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import {
  getAgent,
  listCatalogAgentInstances,
  listCatalogBindings,
  rotateAgentRegistrationCredential,
  setCatalogBindingEnabled,
} from '@/api/agents';
import { getRoles } from '@/api/auth';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';

function healthTone(health: string): 'success' | 'warning' | 'default' {
  if (health === 'healthy' || health === 'ready') return 'success';
  if (health === 'unhealthy' || health === 'offline') return 'warning';
  return 'default';
}

export default function AgentCatalogDetailPage() {
  const { agentId = '' } = useParams();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const roles = getRoles().map(role => role.toLowerCase());
  const [registrationCredential, setRegistrationCredential] = useState('');
  const canEdit = roles.includes('admin') || roles.includes('agent_developer');
  const agent = useQuery({ queryKey: ['catalog-agent', agentId], queryFn: () => getAgent(agentId), enabled: !!agentId });
  const bindings = useQuery({ queryKey: ['catalog-agent-bindings', agentId], queryFn: () => listCatalogBindings(agentId), enabled: !!agentId });
  const instances = useQuery({
    queryKey: ['catalog-agent-instances', agentId],
    queryFn: () => listCatalogAgentInstances(agentId),
    enabled: !!agentId,
    refetchInterval: 10_000,
  });
  const toggle = useMutation({
    mutationFn: ({ binding, enabled }: { binding: NonNullable<typeof bindings.data>[number]; enabled: boolean }) =>
      setCatalogBindingEnabled(agentId, binding, enabled),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['catalog-agent-bindings', agentId] }),
  });
  const rotateCredential = useMutation({
    mutationFn: () => rotateAgentRegistrationCredential(agentId),
    onSuccess: setRegistrationCredential,
  });

  if (agent.isLoading) return <Page><p className="text-sm text-muted-foreground">Loading…</p></Page>;
  if (!agent.data) {
    return <Page><EmptyState title="Agent not found" description={agent.error instanceof Error ? agent.error.message : 'The Agent identity is unavailable.'} /></Page>;
  }
  const value = agent.data;
  return (
    <Page>
      <PageHeader
        title={value.name}
        description={value.description || value.agentKey || value.id}
        actions={value.runtimeKind === 'managed'
          ? <Button onClick={() => navigate(`/managed/agents/${encodeURIComponent(value.id)}/settings`)}>Edit definition</Button>
          : value.runtimeKind === 'external-application' && canEdit
            ? <Button variant="outline" disabled={rotateCredential.isPending} onClick={() => rotateCredential.mutate()}>Rotate registration credential</Button>
            : undefined}
      />

      {registrationCredential && (
        <Card className="border-amber-300 bg-amber-50">
          <CardHeader><CardTitle>Save the new registration credential</CardTitle><CardDescription>Existing credentials were revoked. This plaintext value will not be shown again.</CardDescription></CardHeader>
          <CardContent className="grid gap-3"><code className="break-all rounded border bg-white p-3 text-xs">{registrationCredential}</code><Button variant="outline" onClick={() => void navigator.clipboard.writeText(registrationCredential)}>Copy</Button></CardContent>
        </Card>
      )}

      <div className="grid gap-4 sm:grid-cols-3">
        <Card><CardHeader><CardDescription>Status</CardDescription><CardTitle>{value.status}</CardTitle></CardHeader></Card>
        <Card><CardHeader><CardDescription>Agent key</CardDescription><CardTitle className="truncate font-mono text-base">{value.agentKey}</CardTitle></CardHeader></Card>
        <Card><CardHeader><CardDescription>Logical Agent ID</CardDescription><CardTitle className="truncate font-mono text-base">{value.id}</CardTitle></CardHeader></Card>
      </div>

      <section className="grid gap-3">
        <h2 className="text-lg font-semibold">Runtime bindings</h2>
        {bindings.isError && <p className="text-sm text-red-600">{String(bindings.error)}</p>}
        {(bindings.data ?? []).map(binding => (
          <Card key={binding.id}>
            <CardHeader>
              <div className="flex items-center justify-between gap-3">
                <div>
                  <CardTitle>{binding.kind}</CardTitle>
                  <CardDescription className="font-mono">{binding.id}</CardDescription>
                </div>
                <div className="flex items-center gap-2">
                  <Badge tone={binding.enabled ? 'success' : 'warning'}>{binding.enabled ? 'enabled' : 'disabled'}</Badge>
                  {canEdit && (
                    <Button
                      size="sm"
                      variant="outline"
                      disabled={toggle.isPending}
                      onClick={() => toggle.mutate({ binding, enabled: !binding.enabled })}
                    >
                      {binding.enabled ? 'Disable' : 'Enable'}
                    </Button>
                  )}
                </div>
              </div>
            </CardHeader>
            <CardContent className="text-sm text-muted-foreground">Priority {binding.priority}</CardContent>
          </Card>
        ))}
        {!bindings.isLoading && !(bindings.data ?? []).length && (
          <EmptyState title="No runtime binding" description="This Agent fails closed until an enabled Runtime Binding is configured." />
        )}
      </section>

      <section className="grid gap-3">
        <h2 className="text-lg font-semibold">Instances</h2>
        {(instances.data ?? []).map(instance => (
          <Card key={instance.id}>
            <CardHeader>
              <div className="flex items-start justify-between gap-3">
                <div><CardTitle>{instance.instanceKey}</CardTitle><CardDescription>{instance.framework || 'runtime'} · generation {instance.generation}</CardDescription></div>
                <Badge tone={healthTone(instance.health)}>{instance.health}</Badge>
              </div>
            </CardHeader>
            <CardContent className="text-sm text-muted-foreground">Capacity {instance.activeSessions}/{instance.capacity} · binding {instance.bindingId}</CardContent>
          </Card>
        ))}
        {!instances.isLoading && !(instances.data ?? []).length && (
          <EmptyState title="No active instances" description="Scaling changes instances only; it never creates another logical Agent." />
        )}
      </section>
    </Page>
  );
}
