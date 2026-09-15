import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listResources, resourceURL } from '@/api/resourceAccess';
import { useControlPlaneScope } from '@/app/ScopeContext';

/** References are filtered by the resource inventory's discover permissions. */
export function ResourceConsumers({ kind, id }: { kind: 'vault' | 'memory'; id: string }) {
  const scope = useControlPlaneScope();
  const resources = useQuery({ queryKey: ['resource-inventory', scope.tenant, scope.namespace], queryFn: () => listResources(scope.namespace) });
  const consumers = resources.data?.items.filter(item => item.resource.dependencies?.includes(`${kind}:${id}`)).map(item => item.resource) ?? [];
  return <section className="mb-4 rounded-lg border border-border bg-slate-50 p-3" aria-label="Resource associations">
    <h3 className="text-sm font-semibold">Used by</h3>
    <p className="mt-1 text-xs text-muted-foreground">Configured references you can access. Individual execution mounts may differ.</p>
    {resources.isError ? <p role="alert" className="mt-2 text-xs text-red-700">Unable to load resource associations.</p> : resources.isLoading ? <p className="mt-2 text-xs">Loading references…</p> : !consumers.length ? <p className="mt-2 text-xs text-muted-foreground">No visible resources currently reference this {kind === 'vault' ? 'Vault' : 'Memory Store'}.</p> : <ul className="mt-2 space-y-2">{consumers.map(resource => <li key={`${resource.kind}:${resource.id}`} className="text-sm"><Link className="text-primary underline" to={scope.scopedPath(resource.kind === 'agent' ? `/agent-center/agents/${encodeURIComponent(resource.id)}/definition` : resourceURL(scope.namespace, resource.kind, resource.id))}>{resource.name}</Link><span className="ml-2 text-xs text-muted-foreground">{resource.kind} · {resource.id.slice(0, 8)}</span>{kind === 'memory' && <span className="ml-2 text-xs">Shared knowledge · read-only in Managed sessions</span>}</li>)}</ul>}
  </section>;
}
