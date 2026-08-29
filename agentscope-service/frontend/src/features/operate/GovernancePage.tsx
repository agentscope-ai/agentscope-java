/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { FileClock, Gauge, KeyRound, Network, ShieldCheck, Users } from 'lucide-react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { listDeadLetters, replayDeadLetter } from '@/api/operations';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { Page, PageHeader } from '@/components/Page';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';

const capabilities = [
  { icon: Users, title: 'Identity & access', description: 'Tenant and namespace-scoped console and machine identities.', state: 'available' },
  { icon: ShieldCheck, title: 'Runtime policy', description: 'Ordered Runtime Binding candidates with capability and security constraints.', state: 'available' },
  { icon: KeyRound, title: 'Secret references', description: 'Hashed registration and Endpoint credentials plus task-scoped attempt tokens.', state: 'available' },
  { icon: Gauge, title: 'Quotas & budgets', description: 'Host capacity, Endpoint rate limits, usage and budget foundations.', state: 'foundation' },
  { icon: FileClock, title: 'Audit trail', description: 'Task, approval, runtime command, and credential-use audit events.', state: 'foundation' },
  { icon: Network, title: 'Infrastructure integrations', description: 'Optional Kubernetes CRDs and future Launcher integrations.', state: 'optional' },
];

export default function GovernancePage() {
  const scope = useControlPlaneScope();
  const queryClient = useQueryClient();
  const deadLetters = useQuery({
    queryKey: ['dead-letters', scope.tenant, scope.namespace],
    queryFn: () => listDeadLetters(scope.tenant, scope.namespace),
  });
  const replay = useMutation({
    mutationFn: replayDeadLetter,
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['dead-letters'] }),
  });
  return (
    <Page className="max-w-[1200px]">
      <PageHeader title="Policies & audit" description="Governance belongs to the control plane and remains available without Kubernetes. Infrastructure integrations are optional projections." />
      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {capabilities.map(({ icon: Icon, title, description, state }) => (
          <Card key={title}>
            <CardHeader><div className="flex items-start justify-between gap-3"><div className="flex h-10 w-10 items-center justify-center rounded-lg bg-muted"><Icon className="h-5 w-5" /></div><Badge tone={state === 'available' ? 'success' : state === 'foundation' ? 'info' : 'default'}>{state}</Badge></div><CardTitle className="pt-2">{title}</CardTitle><CardDescription>{description}</CardDescription></CardHeader>
            <CardContent className="text-sm text-muted-foreground">{state === 'available' ? 'Enforced by the current authentication and authorization boundary.' : 'The resource model is reserved; controls will appear when the corresponding API is enabled.'}</CardContent>
          </Card>
        ))}
      </div>
      <section className="grid gap-3">
        <div><h2 className="text-lg font-semibold">Dead letters</h2><p className="text-sm text-muted-foreground">Durable control-plane events that exhausted delivery retries.</p></div>
        <div className="divide-y overflow-hidden rounded-xl border bg-white">
          {(deadLetters.data?.items ?? []).map(event => (
            <div key={event.id} className="flex flex-wrap items-center gap-3 p-4 text-sm">
              <div className="min-w-0 flex-1"><div className="font-medium">{event.eventType}</div><div className="truncate text-xs text-muted-foreground">{event.aggregateType}/{event.aggregateId} · attempts {event.attempts} · {event.lastError}</div></div>
              <Button size="sm" variant="outline" disabled={replay.isPending} onClick={() => replay.mutate(event.id)}>Replay</Button>
            </div>
          ))}
          {!deadLetters.isLoading && !(deadLetters.data?.items.length) && <p className="p-6 text-center text-sm text-muted-foreground">No dead letters in this scope.</p>}
        </div>
      </section>
      <div className="rounded-xl border border-indigo-200 bg-indigo-50 p-5 text-sm text-indigo-900"><strong>Boundary:</strong> Runtime Host security governs task-scoped coding processes. Future user Agent containers are governed through WorkloadTemplate, AgentDeployment, and a separate Launcher—not through Runtime Host.</div>
    </Page>
  );
}
