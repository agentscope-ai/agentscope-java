import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ArrowLeft, ExternalLink } from 'lucide-react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { getRoles } from '@/api/auth';
import {
  addTeamMember,
  getTeamOverview,
  listTeamTasks,
  removeTeamMember,
  updateTeam,
  updateTeamMember,
  type RuntimeBinding,
  type RuntimeBindingPolicy,
  type Team,
  type TeamMember,
} from '@/api/collaboration';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { AgentBindingPicker, AgentIdentity, AgentPicker } from '@/components/AgentPicker';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { PublishEndpointCard } from '@/components/PublishEndpointCard';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input, Textarea } from '@/components/ui/input';
import { formatRelative } from '@/lib/format';

const tabs = ['overview', 'members', 'coordination', 'endpoints', 'activity'] as const;
type Tab = typeof tabs[number];

function tone(state: string): 'success' | 'warning' | 'danger' | 'default' {
  if (state === 'ready' || state === 'active' || state === 'completed') return 'success';
  if (state === 'degraded' || state === 'running' || state === 'queued') return 'warning';
  if (state === 'unavailable' || state === 'failed' || state === 'inactive') return 'danger';
  return 'default';
}

function Metric({ label, value, help }: { label: string; value: string | number; help: string }) {
  return <Card><CardContent className="pt-6"><div className="text-2xl font-semibold">{value}</div><div className="mt-1 text-sm font-medium">{label}</div><p className="mt-1 text-xs text-muted-foreground">{help}</p></CardContent></Card>;
}

function MembersEditor({ teamId, teamVersion, leaderAgentId, members, canEdit }: { teamId: string; teamVersion: number; leaderAgentId: string; members: TeamMember[]; canEdit: boolean }) {
  const qc = useQueryClient();
  const [agentId, setAgentId] = useState('');
  const [role, setRole] = useState('');
  const [instructions, setInstructions] = useState('');
  const [backend, setBackend] = useState<'auto' | 'managed' | 'external-application' | 'hosted-runtime'>('auto');
  const [bindingId, setBindingId] = useState('');
  const [requiredCapabilities, setRequiredCapabilities] = useState('{}');
  const [securityConstraints, setSecurityConstraints] = useState('{}');
  const [editing, setEditing] = useState<string>();
  const [editRole, setEditRole] = useState('');
  const [editInstructions, setEditInstructions] = useState('');
  const refresh = () => void qc.invalidateQueries({ queryKey: ['team-overview', teamId] });
  const add = useMutation({ mutationFn: () => {
    let runtimeBindingPolicy: RuntimeBindingPolicy | undefined;
    if (backend !== 'auto') {
      const binding: RuntimeBinding = { agentId, bindingId, kind: backend };
      runtimeBindingPolicy = { selectionMode: 'ordered', fallbackMode: 'disabled', candidates: [{ binding, requiredCapabilities: JSON.parse(requiredCapabilities), securityConstraints: JSON.parse(securityConstraints) }] };
    }
    return addTeamMember(teamId, { agentId, role, instructions, runtimeBindingPolicy });
  }, onSuccess: () => { setAgentId(''); setRole(''); setInstructions(''); setBackend('auto'); setBindingId(''); setRequiredCapabilities('{}'); setSecurityConstraints('{}'); refresh(); } });
  const remove = useMutation({ mutationFn: (memberId: string) => removeTeamMember(teamId, memberId), onSuccess: refresh });
  const update = useMutation({
    mutationFn: (member: TeamMember) => updateTeamMember(teamId, member.id, {
      role: editRole,
      instructions: editInstructions,
      capabilityRequirements: member.capabilityRequirements,
      runtimeBindingPolicy: member.runtimeBindingPolicy,
      expectedTeamVersion: teamVersion,
    }),
    onSuccess: () => { setEditing(undefined); refresh(); },
  });
  const submit = (event: FormEvent) => { event.preventDefault(); add.mutate(); };
  return <div className="space-y-4">
    {members.map(member => <Card key={member.id}><CardContent className="pt-6">{editing === member.id ? <form className="grid gap-3" onSubmit={event => { event.preventDefault(); update.mutate(member); }}><div className="grid gap-3 md:grid-cols-2"><Input value={editRole} onChange={event => setEditRole(event.target.value)} required /><div className="rounded-md border px-3 py-2 text-sm"><AgentIdentity agentId={member.agentId} /></div></div><Textarea value={editInstructions} onChange={event => setEditInstructions(event.target.value)} placeholder="Responsibilities and hand-off expectations" /><div className="flex gap-2"><Button type="submit" size="sm" disabled={update.isPending}>Save</Button><Button type="button" variant="outline" size="sm" onClick={() => setEditing(undefined)}>Cancel</Button></div></form> : <div className="flex items-start justify-between gap-4"><div><div className="font-medium">{member.role}</div><div className="mt-1 text-sm"><AgentIdentity agentId={member.agentId} /></div>{member.instructions && <p className="mt-2 text-sm text-muted-foreground">{member.instructions}</p>}{member.runtimeBindingPolicy && <p className="mt-2 text-xs text-muted-foreground">Pinned runtime selection · {member.runtimeBindingPolicy.candidates.map(candidate => candidate.binding.kind).join(' → ')}</p>}</div>{canEdit && <div className="flex gap-2"><Button variant="outline" size="sm" onClick={() => { setEditing(member.id); setEditRole(member.role); setEditInstructions(member.instructions ?? ''); }}>Edit</Button><Button variant="outline" size="sm" disabled={remove.isPending} onClick={() => remove.mutate(member.id)}>Remove</Button></div>}</div>}</CardContent></Card>)}
    {!members.length && <EmptyState title="No worker members" description="The leader can run alone, or you can add role-specific Agent members." />}
    {canEdit && <form onSubmit={submit} className="grid gap-3 rounded-xl border bg-white p-5"><h3 className="font-semibold">Add member</h3><div className="grid gap-3 md:grid-cols-2"><Input value={role} onChange={event => setRole(event.target.value)} placeholder="Unique role, for example researcher" required /><AgentPicker value={agentId} onChange={value => { setAgentId(value); setBindingId(''); }} excludeIds={[leaderAgentId, ...members.map(member => member.agentId)]} required aria-label="Team member Agent" /></div><Textarea value={instructions} onChange={event => setInstructions(event.target.value)} placeholder="Responsibilities and hand-off expectations" /><label className="grid gap-1 text-sm">Runtime selection<select className="h-10 rounded-md border bg-background px-3" value={backend} onChange={event => { setBackend(event.target.value as typeof backend); setBindingId(''); }}><option value="auto">Use Agent's automatic runtime selection</option><option value="external-application">External application</option><option value="managed">Managed Agent</option><option value="hosted-runtime">Hosted Runtime</option></select></label>{backend !== 'auto' && <><AgentBindingPicker agentId={agentId} kind={backend} value={bindingId} onChange={setBindingId} required /><div className="grid gap-3 md:grid-cols-2"><Textarea className="font-mono text-xs" value={requiredCapabilities} onChange={event => setRequiredCapabilities(event.target.value)} placeholder="Required capabilities JSON" /><Textarea className="font-mono text-xs" value={securityConstraints} onChange={event => setSecurityConstraints(event.target.value)} placeholder="Security constraints JSON" /></div></>}<div><Button type="submit" disabled={add.isPending}>Add member</Button></div>{add.error && <p className="text-sm text-destructive">{String(add.error)}</p>}</form>}
  </div>;
}

const numericPolicyFields = ['maxActiveTasks', 'maxHops', 'maxFanout', 'maxChildDepth', 'maxChildIssues', 'maxTaskRetries'] as const;

function CoordinationEditor({ team, canEdit, onSaved }: { team: Team; canEdit: boolean; onSaved: () => void }) {
  const [name, setName] = useState(team.name);
  const [description, setDescription] = useState(team.description ?? '');
  const [instructions, setInstructions] = useState(team.instructions ?? '');
  const [leader, setLeader] = useState(team.leaderAgentId);
  const [policy, setPolicy] = useState<Record<string, unknown>>((team.policy ?? {}) as Record<string, unknown>);
  const save = useMutation({ mutationFn: () => updateTeam(team.id, { name, description, instructions, leaderAgentId: leader, status: team.status, policy, expectedVersion: team.version }), onSuccess: onSaved });
  if (!canEdit) return <Card><CardHeader><CardTitle>Team instructions</CardTitle><CardDescription>Frozen into each Run for the leader to coordinate consistently.</CardDescription></CardHeader><CardContent><p className="whitespace-pre-wrap text-sm">{team.instructions || 'No Team-specific instructions.'}</p></CardContent></Card>;
  return <Card><CardHeader><CardTitle>Coordination settings</CardTitle><CardDescription>Changes apply to new Runs. Existing Runs continue with their frozen Team snapshot.</CardDescription></CardHeader><CardContent><form className="grid gap-4" onSubmit={event => { event.preventDefault(); save.mutate(); }}><div className="grid gap-3 md:grid-cols-2"><Input value={name} onChange={event => setName(event.target.value)} placeholder="Team name" required /><AgentPicker value={leader} onChange={setLeader} excludeIds={(team.members ?? []).map(member => member.agentId)} required aria-label="Team leader Agent" /></div><Textarea value={description} onChange={event => setDescription(event.target.value)} placeholder="Description" /><Textarea value={instructions} onChange={event => setInstructions(event.target.value)} placeholder="Leader operating instructions and Team-wide collaboration rules" /><div className="grid gap-3 sm:grid-cols-3">{numericPolicyFields.map(field => <label key={field} className="grid gap-1 text-xs text-muted-foreground">{field}<Input type="number" min="0" value={String(policy[field] ?? 0)} onChange={event => setPolicy(current => ({ ...current, [field]: Number(event.target.value) }))} /></label>)}</div><div className="flex flex-wrap gap-5 text-sm">{[['allowExternalDelegation','Allow external delegation'],['allowMentionAll','Allow mention all'],['requireReview','Require human review']].map(([field,label]) => <label key={field} className="flex items-center gap-2"><input type="checkbox" checked={Boolean(policy[field])} onChange={event => setPolicy(current => ({ ...current, [field]: event.target.checked }))} />{label}</label>)}</div><div><Button type="submit" disabled={save.isPending}>Save coordination settings</Button></div>{save.error && <p className="text-sm text-destructive">{String(save.error)}</p>}</form></CardContent></Card>;
}

export default function TeamDetailPage() {
  const { teamId = '' } = useParams();
  const navigate = useNavigate();
  const scope = useControlPlaneScope();
  const [params, setParams] = useSearchParams();
  const requested = params.get('tab');
  const tab: Tab = tabs.includes(requested as Tab) ? requested as Tab : 'overview';
  const roles = getRoles().map(role => role.toLowerCase());
  const canEdit = roles.includes('admin') || roles.includes('agent_developer');
  const queryClient = useQueryClient();
  const detail = useQuery({ queryKey: ['team-overview', teamId], queryFn: () => getTeamOverview(teamId), enabled: !!teamId, refetchInterval: tab === 'overview' ? 10_000 : false });
  const tasks = useQuery({ queryKey: ['team-tasks', scope.tenant, scope.namespace, teamId], queryFn: () => listTeamTasks(scope.tenant, scope.namespace, teamId), enabled: !!teamId && tab === 'activity' });
  const toggleStatus = useMutation({ mutationFn: () => { const team = detail.data?.team; if (!team) throw new Error('Team is unavailable'); return updateTeam(team.id, { name: team.name, description: team.description ?? '', instructions: team.instructions ?? '', leaderAgentId: team.leaderAgentId, policy: team.policy ?? {}, status: team.status === 'active' ? 'disabled' : 'active', expectedVersion: team.version }); }, onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['team-overview', teamId] }) });
  if (detail.isLoading) return <Page><p className="text-sm text-muted-foreground">Loading Team…</p></Page>;
  if (!detail.data) return <Page><EmptyState title="Team unavailable" description={String(detail.error || 'The Team could not be loaded.')} /></Page>;
  const { team, overview } = detail.data;
  const policy = (team.policy ?? {}) as Record<string, unknown>;
  const selectTab = (next: Tab) => { const updated = new URLSearchParams(params); updated.set('tab', next); setParams(updated, { replace: true }); };
  return <Page className="max-w-[1320px]">
    <button type="button" className="inline-flex cursor-pointer items-center self-start border-0 bg-transparent p-0 text-sm text-muted-foreground hover:text-foreground" aria-label="Back to previous page" title="Back to previous page" onClick={() => navigate(-1)}><ArrowLeft className="mr-2 h-4 w-4" />Back</button>
    <PageHeader title={<span className="flex flex-wrap items-center gap-3">{team.name}<Badge>{team.status}</Badge><Badge tone={tone(overview.readiness)}>{overview.readiness}</Badge></span>} description={team.description || 'Persistent leader-first multi-Agent service.'} actions={<>{canEdit && <Button variant="outline" disabled={toggleStatus.isPending} onClick={() => toggleStatus.mutate()}>{team.status === 'active' ? 'Disable' : 'Enable'}</Button>}<span className="font-mono text-xs text-muted-foreground">{team.id}</span></>} />
    <nav className="flex gap-1 overflow-x-auto border-b">{tabs.map(item => <button key={item} className={`border-b-2 px-4 py-3 text-sm capitalize ${tab === item ? 'border-primary font-medium text-foreground' : 'border-transparent text-muted-foreground'}`} onClick={() => selectTab(item)}>{item === 'endpoints' ? 'Published APIs' : item}</button>)}</nav>

    {tab === 'overview' && <div className="space-y-6"><div className="grid gap-4 md:grid-cols-4"><Metric label="Readiness" value={overview.readiness} help={overview.reason} /><Metric label="Roster" value={overview.members.length} help="Leader and worker Agents" /><Metric label="Active tasks" value={overview.runs.activeTasks} help={`${overview.runs.total} Team runs recorded`} /><Metric label="Published APIs" value={overview.endpoints.published} help={`${overview.endpoints.total} configured`} /></div><div className="grid gap-4 xl:grid-cols-[0.7fr_1.3fr]"><Card><CardHeader><CardTitle>Leader</CardTitle><CardDescription>All new Team work starts here. The leader decides whether and how to delegate.</CardDescription></CardHeader><CardContent><div className="flex items-center justify-between gap-4"><AgentIdentity agentId={team.leaderAgentId} /><Badge tone={tone(overview.members[0]?.readiness.state || 'unavailable')}>{overview.members[0]?.readiness.state || 'unavailable'}</Badge></div><p className="mt-3 text-sm text-muted-foreground">{overview.members[0]?.readiness.reason}</p></CardContent></Card><Card><CardHeader><CardTitle>Roster & runtime readiness</CardTitle><CardDescription>Each member resolves work through its own Managed, External, or Hosted runtime binding.</CardDescription></CardHeader><CardContent className="divide-y">{overview.members.map(member => <div key={`${member.agentId}:${member.role}`} className="flex items-start justify-between gap-4 py-3 first:pt-0 last:pb-0"><div><div className="font-medium">{member.role}{member.leader ? ' · leader' : ''}</div><div className="mt-1 text-sm"><AgentIdentity agentId={member.agentId} /></div><p className="mt-1 text-xs text-muted-foreground">{member.readiness.reason}</p></div><div className="flex gap-2"><Badge>{member.lifecycle || 'unknown'}</Badge><Badge tone={tone(member.readiness.state)}>{member.readiness.state}</Badge></div></div>)}</CardContent></Card></div></div>}

    {tab === 'members' && <MembersEditor teamId={team.id} teamVersion={team.version} leaderAgentId={team.leaderAgentId} members={team.members ?? []} canEdit={canEdit} />}

    {tab === 'coordination' && <div className="grid gap-4 xl:grid-cols-[1.3fr_0.7fr]"><CoordinationEditor key={team.version} team={team} canEdit={canEdit} onSaved={() => void queryClient.invalidateQueries({ queryKey: ['team-overview', teamId] })} /><Card><CardHeader><CardTitle>Leader-first protocol</CardTitle><CardDescription>Workers are not automatically fanned out.</CardDescription></CardHeader><CardContent className="space-y-3 text-sm"><p>The leader delegates through structured mentions or child work, receives worker results, and explicitly concludes the coordinator.</p><div>External delegation: <strong>{policy.allowExternalDelegation ? 'Allowed' : 'Blocked'}</strong></div><div>Mention all: <strong>{policy.allowMentionAll ? 'Allowed' : 'Blocked'}</strong></div><div>Human review: <strong>{policy.requireReview ? 'Required' : 'Policy dependent'}</strong></div><p className="text-xs text-muted-foreground">All policy and roster values are frozen when a Run begins.</p></CardContent></Card></div>}

    {tab === 'endpoints' && <PublishEndpointCard
      targetType="team"
      targetRef={team.id}
      targetName={team.name}
      ownerPath={`/agent-center/teams/${team.id}?tab=endpoints`}
    />}

    {tab === 'activity' && <Card><CardHeader><CardTitle>Team work</CardTitle><CardDescription>Only AgentTasks created under this Team's frozen snapshots are shown; unrelated member traffic is excluded.</CardDescription></CardHeader><CardContent className="divide-y">{(tasks.data?.items ?? []).slice(0, 20).map(task => <Link key={task.id} to={scope.scopedPath(`/work/executions/tasks/${task.id}`)} className="flex items-center justify-between gap-4 py-4 hover:bg-muted/30"><div><div className="font-medium">{task.teamRole || 'member'} · <AgentIdentity agentId={task.agentId} /></div><div className="mt-1 text-xs text-muted-foreground">{formatRelative(task.createdAt)} · {task.id.slice(0, 8)}</div></div><div className="flex items-center gap-2"><Badge tone={tone(task.status)}>{task.status}</Badge><ExternalLink className="h-4 w-4 text-muted-foreground" /></div></Link>)}{!tasks.isLoading && !(tasks.data?.items ?? []).length && <EmptyState title="No Team activity" description="Assign an Issue or invoke a published Team Endpoint to start an adaptive Run." />}</CardContent></Card>}
  </Page>;
}
