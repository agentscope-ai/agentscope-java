import { type FormEvent, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ExternalLink } from 'lucide-react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import {
  createDefinition,
  getDefinition,
  listDefinitions,
  listRuns,
  listRevisions,
  publishDefinition,
  startRun,
  updateDefinition,
  validateDefinition,
  type DefinitionSpec,
} from '@/api/orchestration';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { AgentIdentity, AgentPicker } from '@/components/AgentPicker';
import { PublishEndpointCard } from '@/components/PublishEndpointCard';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { formatRelative } from '@/lib/format';

const starter = (agentId: string): DefinitionSpec => ({
  nodes: [{ key: 'work', type: 'agent', agentId, issueMode: 'inherit', failurePolicy: 'fail_fast' }],
  edges: [],
});

export default function DefinitionsPage() {
  const { definitionId } = useParams();
  return definitionId ? <DefinitionDetail id={definitionId} /> : <DefinitionList />;
}

function DefinitionList() {
  const scope = useControlPlaneScope();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [agentId, setAgentId] = useState('');
  const definitions = useQuery({
    queryKey: ['orchestration-definitions', scope.tenant, scope.namespace],
    queryFn: () => listDefinitions(scope.tenant, scope.namespace),
  });
  const create = useMutation({
    mutationFn: () => createDefinition({
      tenant: scope.tenant,
      namespace: scope.namespace,
      name,
      draftSpec: starter(agentId),
    }),
    onSuccess: (result) => {
      void queryClient.invalidateQueries({ queryKey: ['orchestration-definitions'] });
      navigate(scope.scopedPath(`/orchestration/definitions/${result.definition.id}`));
    },
  });
  function submit(event: FormEvent) {
    event.preventDefault();
    create.mutate();
  }
  const items = definitions.data?.definitions ?? [];
  return (
    <Page>
      <PageHeader title="Orchestration Definitions" description="Versioned, validated DAGs for repeatable multi-runtime work." />
      <form onSubmit={submit} className="mb-5 grid gap-3 rounded-xl border bg-white p-4 md:grid-cols-[1fr_1.4fr_auto]">
        <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="Workflow name" required />
        <AgentPicker value={agentId} onChange={setAgentId} required aria-label="Initial workflow Agent" />
        <Button disabled={!name.trim() || !agentId || create.isPending}>Create</Button>
        {create.error && <p className="text-sm text-destructive md:col-span-3">{String(create.error)}</p>}
      </form>
      {!definitions.isLoading && items.length === 0 ? (
        <EmptyState title="No definitions" description="Choose a registered Agent to create the first valid DAG, then publish an immutable revision." />
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {items.map((definition) => (
            <Link key={definition.id} to={scope.scopedPath(`/orchestration/definitions/${definition.id}`)} className="rounded-xl border bg-white p-5 hover:border-primary">
              <div className="flex justify-between"><strong>{definition.name}</strong><Badge>draft v{definition.draftVersion}</Badge></div>
              <p className="mt-2 text-sm text-muted-foreground">{definition.description || `${definition.draftSpec.nodes.length} nodes`}</p>
            </Link>
          ))}
        </div>
      )}
    </Page>
  );
}

function DefinitionDetail({ id }: { id: string }) {
  const scope = useControlPlaneScope();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const detail = useQuery({ queryKey: ['orchestration-definition', id], queryFn: () => getDefinition(id) });
  const revisions = useQuery({ queryKey: ['orchestration-revisions', id], queryFn: () => listRevisions(id) });
  const runs = useQuery({
    queryKey: ['orchestration-definition-runs', scope.tenant, scope.namespace, id],
    queryFn: () => listRuns(scope.tenant, scope.namespace),
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
  });
  const initial = detail.data?.definition.draftSpec;
  const [editor, setEditor] = useState('');
  const [issueId, setIssueId] = useState('');
  const [validation, setValidation] = useState('');
  const [newNodeKey, setNewNodeKey] = useState('');
  const [newAgentId, setNewAgentId] = useState('');
  const [publishRevisionId, setPublishRevisionId] = useState('');
  const text = editor || JSON.stringify(initial || { nodes: [], edges: [] }, null, 2);
  const parsed = useMemo(() => {
    try {
      return JSON.parse(text) as DefinitionSpec;
    } catch {
      return null;
    }
  }, [text]);
  const agentNodes = (parsed?.nodes ?? [])
    .map((node, index) => ({ node, index }))
    .filter(({ node }) => node.type === 'agent');
  const setSpec = (spec: DefinitionSpec) => {
    setEditor(JSON.stringify(spec, null, 2));
    setValidation('Draft changed; validate before publish.');
  };
  const updateAgentNode = (index: number, agentId: string) => {
    if (!parsed) return;
    const nodes = parsed.nodes.map((node, nodeIndex) => nodeIndex === index ? { ...node, agentId } : node);
    setSpec({ ...parsed, nodes });
  };
  const removeAgentNode = (index: number) => {
    if (!parsed) return;
    const removedKey = parsed.nodes[index]?.key;
    setSpec({
      ...parsed,
      nodes: parsed.nodes.filter((_node, nodeIndex) => nodeIndex !== index),
      edges: (parsed.edges ?? []).filter((edge) => edge.from !== removedKey && edge.to !== removedKey),
    });
  };
  const addAgentNode = () => {
    if (!parsed || !newNodeKey.trim() || !newAgentId) return;
    setSpec({
      ...parsed,
      nodes: [...parsed.nodes, {
        key: newNodeKey.trim(),
        type: 'agent',
        agentId: newAgentId,
        issueMode: 'inherit',
        failurePolicy: 'fail_fast',
      }],
    });
    setNewNodeKey('');
    setNewAgentId('');
  };
  const duplicateNodeKey = !!newNodeKey.trim() && !!parsed?.nodes.some((node) => node.key === newNodeKey.trim());
  const save = useMutation({
    mutationFn: () => updateDefinition(id, { draftSpec: parsed, expectedVersion: detail.data?.definition.version }),
    onSuccess: () => {
      setEditor('');
      void queryClient.invalidateQueries({ queryKey: ['orchestration-definition', id] });
    },
  });
  const validate = useMutation({
    mutationFn: () => validateDefinition(id, parsed!),
    onSuccess: (result) => setValidation(result.valid ? 'Valid DAG' : 'Invalid'),
    onError: (error) => setValidation(String(error)),
  });
  const publish = useMutation({
    mutationFn: () => publishDefinition(id),
    onSuccess: (result) => {
      setPublishRevisionId(result.revision.id);
      void queryClient.invalidateQueries({ queryKey: ['orchestration-revisions', id] });
    },
  });
  const start = useMutation({
    mutationFn: () => startRun(id, { idempotencyKey: crypto.randomUUID(), input: {}, issueId }),
    onSuccess: (result) => navigate(scope.scopedPath(`/work/executions/${result.run.id}`)),
  });
  if (!detail.data) return <Page>Loading definition…</Page>;
  const revisionItems = revisions.data?.revisions ?? [];
  const revisionIds = new Set(revisionItems.map(revision => revision.id));
  const workflowRuns = (runs.data?.runs ?? [])
    .filter(run => !!run.definitionRevisionId && revisionIds.has(run.definitionRevisionId))
    .slice(0, 10);
  const selectedRevision = revisionItems.find(revision => revision.id === publishRevisionId) ?? revisionItems[0];
  return (
    <Page>
      <Link to={scope.scopedPath('/orchestration/definitions')} className="text-sm text-muted-foreground">← Definitions</Link>
      <PageHeader
        title={detail.data.definition.name}
        description="Choose registered Agents for execution nodes. Advanced graph fields remain editable as JSON."
        actions={<div className="flex gap-2">
          <Button variant="outline" disabled={!parsed || validate.isPending} onClick={() => validate.mutate()}>Validate</Button>
          <Button disabled={!parsed || save.isPending} onClick={() => save.mutate()}>Save draft</Button>
          <Button variant="outline" onClick={() => publish.mutate()}>Publish</Button>
        </div>}
      />
      <div className="grid gap-5 xl:grid-cols-[2fr_1fr]">
        <section className="space-y-4">
          <div className="rounded-xl border bg-white p-4">
            <div className="flex items-center justify-between"><h2 className="font-semibold">Agent nodes</h2><Badge>{agentNodes.length}</Badge></div>
            <div className="mt-3 space-y-3">
              {agentNodes.map(({ node, index }) => (
                <div key={`${node.key}-${index}`} className="grid gap-2 rounded-lg border p-3 md:grid-cols-[10rem_1fr_auto]">
                  <div className="min-w-0"><div className="font-medium">{node.key}</div><div className="truncate text-xs text-muted-foreground"><AgentIdentity agentId={String(node.agentId || '')} /></div></div>
                  <AgentPicker value={String(node.agentId || '')} onChange={(agentId) => updateAgentNode(index, agentId)} required aria-label={`Agent for node ${node.key}`} />
                  <Button variant="outline" onClick={() => removeAgentNode(index)}>Remove</Button>
                </div>
              ))}
              {agentNodes.length === 0 && <p className="text-sm text-muted-foreground">No Agent nodes. Add one before validation.</p>}
            </div>
            <div className="mt-4 grid gap-2 border-t pt-4 md:grid-cols-[10rem_1fr_auto]">
              <Input value={newNodeKey} onChange={(event) => setNewNodeKey(event.target.value)} placeholder="Unique node key" />
              <AgentPicker value={newAgentId} onChange={setNewAgentId} aria-label="Agent for new node" />
              <Button variant="outline" disabled={!newNodeKey.trim() || !newAgentId || duplicateNodeKey} onClick={addAgentNode}>Add node</Button>
              {duplicateNodeKey && <p className="text-xs text-destructive md:col-span-3">Node keys must be unique.</p>}
            </div>
          </div>
          <div>
            <div className="mb-2 flex items-center justify-between"><h2 className="font-semibold">Advanced graph JSON</h2><span className="text-xs text-muted-foreground">Agent IDs above stay synchronized</span></div>
            <textarea aria-label="Definition JSON" className="min-h-[34rem] w-full rounded-xl border bg-slate-950 p-4 font-mono text-xs text-slate-100" value={text} onChange={(event) => setEditor(event.target.value)} />
            <p className="mt-2 text-sm">{parsed ? validation || 'JSON parsed' : 'Invalid JSON'}</p>
          </div>
        </section>
        <aside className="space-y-4">
          <div className="rounded-xl border bg-white p-4">
            <h2 className="font-semibold">Start run</h2>
            <Input className="mt-3" value={issueId} onChange={(event) => setIssueId(event.target.value)} placeholder="Existing Issue ID" />
            <Button className="mt-2" disabled={!issueId} onClick={() => start.mutate()}>Start latest revision</Button>
          </div>
          <div className="rounded-xl border bg-white p-4">
            <div className="flex items-center justify-between gap-3">
              <h2 className="font-semibold">Recent executions</h2>
              <Badge>{workflowRuns.length}</Badge>
            </div>
            <p className="mt-1 text-xs text-muted-foreground">Runs linked to a published revision of this Workflow.</p>
            <div className="mt-3 space-y-2">
              {workflowRuns.map(run => (
                <Link key={run.id} to={scope.scopedPath(`/work/executions/${run.id}`)} className="flex items-center justify-between gap-3 rounded-lg border p-3 text-sm hover:bg-muted/40">
                  <span className="min-w-0">
                    <span className="block truncate font-medium">Execution {run.id.slice(0, 8)}</span>
                    <span className="text-xs text-muted-foreground">{formatRelative(run.createdAt)} · {run.mode}</span>
                  </span>
                  <span className="flex items-center gap-2"><Badge>{run.state.replace(/_/g, ' ')}</Badge><ExternalLink className="h-3.5 w-3.5 text-muted-foreground" /></span>
                </Link>
              ))}
              {!runs.isLoading && !workflowRuns.length && <p className="py-3 text-xs text-muted-foreground">No execution has used this Workflow yet.</p>}
            </div>
          </div>
          <div className="rounded-xl border bg-white p-4">
            <h2 className="font-semibold">Published revisions</h2>
            <div className="mt-3 space-y-2">{revisionItems.map((revision) => (
              <div key={revision.id} className="rounded-lg border p-3">
                <div className="flex items-center justify-between gap-2"><span className="text-sm">Revision {revision.revision} · {revision.checksum.slice(0, 10)}</span><Button size="sm" variant={selectedRevision?.id === revision.id ? 'default' : 'outline'} onClick={() => setPublishRevisionId(revision.id)}>API target</Button></div>
                <details className="mt-2"><summary className="cursor-pointer text-xs text-muted-foreground">View immutable spec</summary><pre className="mt-2 overflow-auto rounded bg-muted p-2 text-[10px]">{JSON.stringify(revision.spec, null, 2)}</pre></details>
              </div>
            ))}</div>
          </div>
          {selectedRevision && <PublishEndpointCard
            targetType="orchestration_revision"
            targetRef={selectedRevision.id}
            targetName={detail.data.definition.name}
            ownerPath={`/agent-center/workflows/${detail.data.definition.id}`}
            allowDeployToExisting
            relatedTargetRefs={revisionItems.map(revision => revision.id)}
          />}
        </aside>
      </div>
    </Page>
  );
}
