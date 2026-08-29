import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  addTeamMember,
  createTeam,
  listTeams,
  removeTeamMember,
  type RuntimeBinding,
  type RuntimeBindingPolicy,
  type Team,
} from '@/api/collaboration';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { Button } from '@/components/ui/button';
import { Input, Textarea } from '@/components/ui/input';

function TeamCard({ team, refresh }: { team: Team; refresh: () => void }) {
  const [agentId, setAgentId] = useState('');
  const [role, setRole] = useState('worker');
  const [instructions, setInstructions] = useState('');
  const [backend, setBackend] = useState<'auto' | 'managed' | 'external-application' | 'hosted-runtime'>('auto');
  const [bindingRefA, setBindingRefA] = useState('');
  const [requiredCapabilities, setRequiredCapabilities] = useState('{}');
  const [securityConstraints, setSecurityConstraints] = useState('{}');
  const runtimeBindingPolicy = (): RuntimeBindingPolicy | undefined => {
    let binding: RuntimeBinding | undefined;
    if (backend !== 'auto') binding = { agentId, bindingId: bindingRefA, kind: backend };
    return binding ? { selectionMode: 'ordered', fallbackMode: 'disabled', candidates: [{ binding, requiredCapabilities: JSON.parse(requiredCapabilities), securityConstraints: JSON.parse(securityConstraints) }] } : undefined;
  };
  const add = useMutation({
    mutationFn: () => addTeamMember(team.id, { agentId, role, instructions, runtimeBindingPolicy: runtimeBindingPolicy() }),
    onSuccess: () => {
      setAgentId('');
      setRole('worker');
      setInstructions('');
      setBackend('auto');
      setBindingRefA('');
      refresh();
    },
  });
  const remove = useMutation({
    mutationFn: (memberId: string) => removeTeamMember(team.id, memberId),
    onSuccess: refresh,
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    add.mutate();
  };

  return (
    <article className="rounded-xl border bg-white p-5">
      <h2 className="font-semibold">{team.name}</h2>
      <p className="mt-1 text-sm text-muted-foreground">{team.description || 'No description'}</p>
      <div className="mt-4 text-sm">Leader: <strong>{team.leaderAgentId}</strong></div>
      <div className="mt-4 space-y-2">
        {(team.members || []).map((member) => (
          <div key={member.id} className="flex items-start justify-between gap-3 rounded-lg border px-3 py-2 text-sm">
            <div>
              <div><strong>{member.role}</strong> · {member.agentId}</div>
              {member.instructions && <div className="mt-1 text-muted-foreground">{member.instructions}</div>}
              {member.runtimeBindingPolicy && (
                <div className="mt-1 text-xs text-muted-foreground">Runtime policy: {member.runtimeBindingPolicy.candidates.map((candidate) => candidate.binding.kind).join(' → ')}</div>
              )}
            </div>
            <Button variant="outline" size="sm" disabled={remove.isPending} onClick={() => remove.mutate(member.id)}>
              Remove
            </Button>
          </div>
        ))}
        {!(team.members || []).length && <p className="text-sm text-muted-foreground">No worker roles yet.</p>}
      </div>
      <form onSubmit={submit} className="mt-4 grid gap-2 border-t pt-4">
        <div className="grid gap-2 sm:grid-cols-2">
          <Input value={role} onChange={(event) => setRole(event.target.value)} placeholder="Unique role" required />
          <Input value={agentId} onChange={(event) => setAgentId(event.target.value)} placeholder="Agent ID" required />
        </div>
        <Textarea value={instructions} onChange={(event) => setInstructions(event.target.value)} placeholder="Role instructions" />
        <label className="grid gap-1 text-sm">
          Runtime binding policy
          <select
            className="h-10 rounded-md border bg-background px-3"
            value={backend}
            onChange={(event) => {
              setBackend(event.target.value as typeof backend);
              setBindingRefA('');
            }}
          >
            <option value="auto">Inherit Agent Runtime Policy</option>
            <option value="external-application">External application</option>
            <option value="managed">Managed Agent</option>
            <option value="hosted-runtime">Hosted Runtime</option>
          </select>
        </label>
        {backend !== 'auto' && <Input value={bindingRefA} onChange={(event) => setBindingRefA(event.target.value)} placeholder="Binding ID" required />}
        {backend !== 'auto' && <div className="grid gap-2 sm:grid-cols-2"><Textarea className="font-mono text-xs" value={requiredCapabilities} onChange={event=>setRequiredCapabilities(event.target.value)} placeholder="Required capabilities JSON"/><Textarea className="font-mono text-xs" value={securityConstraints} onChange={event=>setSecurityConstraints(event.target.value)} placeholder="Security constraints JSON"/></div>}
        <Button type="submit" variant="outline" disabled={add.isPending}>Add member</Button>
        {(add.error || remove.error) && <p className="text-sm text-destructive">{String(add.error || remove.error)}</p>}
      </form>
    </article>
  );
}

export default function TeamsOverviewPage() {
  const scope = useControlPlaneScope();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [leader, setLeader] = useState('');
  const [description, setDescription] = useState('');
  const teams = useQuery({
    queryKey: ['teams', scope.tenant, scope.namespace],
    queryFn: () => listTeams(scope.tenant, scope.namespace),
  });
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['teams', scope.tenant, scope.namespace] });
  };
  const create = useMutation({
    mutationFn: () => createTeam({
      tenant: scope.tenant,
      namespace: scope.namespace,
      name,
      description,
      leaderAgentId: leader,
      policy: { maxActiveTasks: 32, maxFanout: 8, maxHops: 8, maxChildDepth: 8, maxChildIssues: 64 },
    }),
    onSuccess: () => {
      setOpen(false);
      setName('');
      setLeader('');
      setDescription('');
      refresh();
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };
  const items = teams.data?.items || [];

  return (
    <Page>
      <PageHeader
        title="Teams"
        description="Persistent leader-first Agent squads. All work and communication lives in Issues and Comments."
        actions={<Button onClick={() => setOpen(!open)}>New Team</Button>}
      />
      {open && (
        <form onSubmit={submit} className="grid gap-3 rounded-xl border bg-white p-5">
          <Input value={name} onChange={(event) => setName(event.target.value)} placeholder="Team name" required />
          <Input value={leader} onChange={(event) => setLeader(event.target.value)} placeholder="Leader Agent ID" required />
          <Textarea value={description} onChange={(event) => setDescription(event.target.value)} placeholder="Description" />
          <Button type="submit" disabled={create.isPending}>Create Team</Button>
          {create.error && <p className="text-sm text-destructive">{String(create.error)}</p>}
        </form>
      )}
      {!teams.isLoading && !items.length ? (
        <EmptyState title="No Teams" description="Create a persistent Team with a leader Agent." />
      ) : (
        <div className="grid gap-4 xl:grid-cols-2">
          {items.map((team) => <TeamCard key={team.id} team={team} refresh={refresh} />)}
        </div>
      )}
    </Page>
  );
}
