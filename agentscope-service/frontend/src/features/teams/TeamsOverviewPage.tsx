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

import { useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { Plus } from 'lucide-react';
import {
  createTeam,
  listTeams,
  type Team,
} from '@/api/collaboration';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { EmptyState } from '@/components/EmptyState';
import { Page, PageHeader } from '@/components/Page';
import { AgentIdentity, AgentMultiPicker, AgentPicker, useCatalogAgents } from '@/components/AgentPicker';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input, Textarea } from '@/components/ui/input';
import { buildInitialTeamMembers } from './teamCreation';

function TeamCard({ team }: { team: Team }) {
  const scope = useControlPlaneScope();
  return (
    <Link to={scope.scopedPath(`/agent-center/teams/${team.id}`)} className="block rounded-2xl border border-slate-200/90 bg-white p-5 transition hover:-translate-y-0.5 hover:border-slate-300 hover:shadow-md">
      <div className="flex items-start justify-between gap-3"><div><h2 className="font-semibold">{team.name}</h2><p className="mt-1 line-clamp-2 text-sm text-muted-foreground">{team.description || 'No description'}</p></div><Badge>{team.status}</Badge></div>
      <div className="mt-5 text-sm"><span className="text-muted-foreground">Leader</span><div className="mt-1 font-medium"><AgentIdentity agentId={team.leaderAgentId} /></div></div>
      <div className="mt-4 flex items-center justify-between text-xs text-muted-foreground"><span>{team.members?.length ?? 0} worker roles</span><span>Version {team.version}</span></div>
    </Link>
  );
}

export default function TeamsOverviewPage() {
  const scope = useControlPlaneScope();
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [name, setName] = useState('');
  const [leader, setLeader] = useState('');
  const [memberIds, setMemberIds] = useState<string[]>([]);
  const [description, setDescription] = useState('');
  const [instructions, setInstructions] = useState('');
  const teams = useQuery({
    queryKey: ['teams', scope.tenant, scope.namespace],
    queryFn: () => listTeams(scope.tenant, scope.namespace),
  });
  const agents = useCatalogAgents();
  const refresh = () => {
    void queryClient.invalidateQueries({ queryKey: ['teams', scope.tenant, scope.namespace] });
  };
  const create = useMutation({
    mutationFn: () => createTeam({
      tenant: scope.tenant,
      namespace: scope.namespace,
      name,
      description,
      instructions,
      leaderAgentId: leader,
      members: buildInitialTeamMembers(memberIds, agents.data ?? []),
      policy: { maxActiveTasks: 32, maxFanout: 8, maxHops: 8, maxChildDepth: 8, maxChildIssues: 64 },
    }),
    onSuccess: () => {
      setOpen(false);
      setName('');
      setLeader('');
      setMemberIds([]);
      setDescription('');
      setInstructions('');
      refresh();
    },
  });
  const submit = (event: FormEvent) => {
    event.preventDefault();
    create.mutate();
  };
  const items = teams.data?.items || [];

  return (
    <Page className="max-w-[1440px]">
      <PageHeader
        title="Teams"
        description="Persistent leader-first multi-Agent services. Assemble a roster and coordination policy, then publish an API Endpoint."
        actions={<Button onClick={() => setOpen(!open)}><Plus className="h-4 w-4" />New team</Button>}
      />
      {open && (
        <form onSubmit={submit} className="grid gap-5 rounded-2xl border border-slate-200/90 bg-white p-6 shadow-[0_1px_2px_rgba(15,23,42,0.035)]">
          <div><h2 className="text-lg font-semibold">Create Team</h2><p className="mt-1 text-sm text-muted-foreground">Choose a leader and, optionally, the Agents they can delegate work to.</p></div>
          <label className="grid gap-1.5 text-sm font-medium"><span>Name</span><Input value={name} onChange={(event) => setName(event.target.value)} placeholder="Team name" required /></label>
          <label className="grid gap-1.5 text-sm font-medium"><span>Description <span className="font-normal text-muted-foreground">(optional)</span></span><Textarea value={description} onChange={(event) => setDescription(event.target.value)} placeholder="What is this Team responsible for?" /></label>
          <div className="grid gap-4 lg:grid-cols-2">
            <div className="grid content-start gap-1.5 text-sm"><div className="font-medium">Leader Agent</div><p className="text-muted-foreground">Receives Team work and coordinates delegation.</p><AgentPicker value={leader} onChange={(value) => { setLeader(value); setMemberIds(current => current.filter(id => id !== value)); }} required aria-label="Team leader Agent" /></div>
            <div className="grid content-start gap-1.5 text-sm"><div className="font-medium">Additional members <span className="font-normal text-muted-foreground">(optional)</span></div><p className="text-muted-foreground">Select one or more Agents in the same field.</p><AgentMultiPicker value={memberIds} onChange={setMemberIds} excludeIds={leader ? [leader] : []} aria-label="Additional Team members" /></div>
          </div>
          <details className="rounded-lg border bg-muted/20 px-4 py-3"><summary className="cursor-pointer text-sm font-medium">Advanced coordination instructions</summary><Textarea className="mt-3" value={instructions} onChange={(event) => setInstructions(event.target.value)} placeholder="Leader operating instructions and Team-wide collaboration rules" /></details>
          <div className="flex gap-2"><Button type="submit" disabled={create.isPending}>{create.isPending ? 'Creating…' : 'Create team'}</Button><Button type="button" variant="outline" onClick={() => setOpen(false)}>Cancel</Button></div>
          {create.error && <p className="text-sm text-destructive">{String(create.error)}</p>}
        </form>
      )}
      {!teams.isLoading && !items.length ? (
        <EmptyState title="No Teams" description="Create a persistent Team with a leader Agent." />
      ) : (
        <div className="grid gap-4 xl:grid-cols-2">
          {items.map((team) => <TeamCard key={team.id} team={team} />)}
        </div>
      )}
    </Page>
  );
}
