// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { listRuntimeHosts, updateRuntimeHostCapacity, type RuntimeHost } from '@/api/runtimeControl';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';
import { namespaceCan } from '@/lib/namespaceScope';

function HostCapacityForm({ host, onSaved }: { host: RuntimeHost; onSaved: () => Promise<void> }) {
  const [capacity, setCapacity] = useState(String(host.capacity));
  const [expectedCapacity, setExpectedCapacity] = useState(host.capacity);
  const [dirty, setDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  // Polls update occupancy without overwriting an unsaved capacity or its concurrency check.
  useEffect(() => {
    if (!dirty) { setCapacity(String(host.capacity)); setExpectedCapacity(host.capacity); }
  }, [host.capacity, dirty]);
  const parsed = Number(capacity);
  const valid = capacity.trim() !== '' && Number.isInteger(parsed) && parsed >= 1 && parsed <= 50;
  async function save(event: React.FormEvent) {
    event.preventDefault();
    if (!valid) return;
    setSaving(true); setError(''); setMessage('');
    try {
      const { host: updated } = await updateRuntimeHostCapacity(host.id, parsed, expectedCapacity);
      setCapacity(String(updated.capacity)); setExpectedCapacity(updated.capacity);
      await onSaved();
      setDirty(false);
      setMessage('Capacity saved. The Host applies it on its next heartbeat (normally within 15 seconds), or when it reconnects.');
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Could not save capacity.');
    } finally { setSaving(false); }
  }
  return <form onSubmit={event => void save(event)} className="grid gap-3 rounded-lg border p-4" aria-label={`Capacity for ${host.hostKey}`}>
    <div className="flex flex-wrap justify-between gap-2 text-sm"><span className="font-medium">Host {host.hostKey}</span><span>{host.state} · {host.active} / {host.capacity > 0 ? host.capacity : '∞'} occupied</span></div>
    <div className="flex items-end gap-3">
      <label className="grid flex-1 gap-1.5 text-sm"><span className="font-medium">Host capacity</span><Input type="number" min={1} max={50} step={1} required value={capacity} disabled={saving} onChange={event => { setCapacity(event.target.value); setDirty(true); setMessage(''); setError(''); }} /></label>
      <Button type="submit" disabled={saving || !dirty || !valid}>{saving ? 'Saving…' : 'Save capacity'}</Button>
    </div>
    {dirty && !valid && <p role="alert" className="text-sm text-red-600">Enter a whole number between 1 and 50.</p>}
    {valid && parsed < host.active && <p className="text-sm text-muted-foreground">Current executions will finish. New work waits until occupancy is below the new capacity.</p>}
    {error && <p role="alert" className="text-sm text-red-600">{error} Refresh this page before retrying if another operator changed the capacity.</p>}
    {message && <p role="status" className="text-sm text-emerald-600">{message}</p>}
  </form>;
}

export function RuntimeHostCapacity({ poolName }: { poolName: string }) {
  const scope = useControlPlaneScope();
  const client = useQueryClient();
  const canOperate = namespaceCan(scope.roles, 'operate');
  const queryKey = ['runtime-host-capacity', scope.tenant, scope.namespace, poolName];
  const hosts = useQuery({ queryKey, queryFn: () => listRuntimeHosts(scope.tenant, scope.namespace, poolName), enabled: canOperate, refetchInterval: 15000 });
  return <Card>
    <CardHeader><CardTitle>Host capacity</CardTitle><CardDescription>Hosts in the saved runtime pool: {poolName}. Each capacity is shared by all Agents and providers on that Host, including Qoder and Codex. Waiting for approval also occupies a slot.</CardDescription></CardHeader>
    <CardContent className="grid gap-3">
      <p className="text-sm text-muted-foreground">Capacity is a whole number from 1 to 50. Saving changes the shared Host limit; it persists across Host restarts. Agent concurrency remains a separate limit.</p>
      {!canOperate ? <p className="text-sm text-muted-foreground">A namespace administrator or operator can view and change Host capacity.</p> : hosts.isLoading ? <p>Loading Hosts…</p> : hosts.isError ? <p role="alert">{hosts.error.message}</p> : <>
        {(hosts.data?.items ?? []).map(host => <HostCapacityForm key={host.id} host={host} onSaved={() => client.invalidateQueries({ queryKey: ['runtime-host-capacity'] })} />)}
        {!hosts.data?.items.length && <p className="text-sm text-muted-foreground">No Hosts are registered in this pool. Connect a Host to configure its capacity.</p>}
      </>}
    </CardContent>
  </Card>;
}
