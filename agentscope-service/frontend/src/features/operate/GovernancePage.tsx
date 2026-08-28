/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0.
 */

import { FileClock, Gauge, KeyRound, Network, ShieldCheck, Users } from 'lucide-react';
import { Page, PageHeader } from '@/components/Page';
import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';

const capabilities = [
  { icon: Users, title: 'Identity & access', description: 'Tenant and namespace-scoped console and machine identities.', state: 'available' },
  { icon: ShieldCheck, title: 'Runtime policy', description: 'Provider, tool, filesystem, sandbox, and executable allowlists.', state: 'planned' },
  { icon: KeyRound, title: 'Secret references', description: 'Task-scoped credentials without exposing plaintext in templates.', state: 'planned' },
  { icon: Gauge, title: 'Quotas & budgets', description: 'Concurrent executions, Host capacity, token and cost limits.', state: 'planned' },
  { icon: FileClock, title: 'Audit trail', description: 'Task, approval, runtime command, and credential-use audit events.', state: 'foundation' },
  { icon: Network, title: 'Infrastructure integrations', description: 'Optional Kubernetes CRDs and future Launcher integrations.', state: 'optional' },
];

export default function GovernancePage() {
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
      <div className="rounded-xl border border-indigo-200 bg-indigo-50 p-5 text-sm text-indigo-900"><strong>Boundary:</strong> Runtime Host security governs task-scoped coding processes. Future user Agent containers are governed through WorkloadTemplate, AgentDeployment, and a separate Launcher—not through Runtime Host.</div>
    </Page>
  );
}
