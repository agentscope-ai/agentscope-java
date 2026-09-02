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

import { FormEvent, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { listAgents, type AgentDefinition } from '@/api/agents';
import { resolveApiErrorMessage } from '@/api/errors';
import {
  createTeam,
  type TeamCreateMember,
  type TeamCreateRequest,
} from '@/api/teams';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';
import { Page, PageHeader } from '@/components/Page';
import { me } from '@/lib/auth';
import { fetchDataPlanes } from '@/features/operate/api';
import { useT } from '@/i18n';

type DeployPick = 'managed' | 'byo';

interface MemberDraft {
  name: string;
  deployMode: DeployPick;
  managedAgentId: string;
  byoAgentRef: string;
  prompt: string;
}

const emptyMember = (): MemberDraft => ({
  name: '',
  deployMode: 'managed',
  managedAgentId: '',
  byoAgentRef: '',
  prompt: '',
});

function agentRefFor(pick: DeployPick, managedId: string, byoRef: string, agents: AgentDefinition[]) {
  if (pick === 'byo') return byoRef.trim();
  const a = agents.find((x) => x.id === managedId);
  return (a?.name || managedId).trim();
}

export default function TeamCreatePage() {
  const t = useT();
  const navigate = useNavigate();
  const [name, setName] = useState('');
  const [namespace, setNamespace] = useState('default');
  const [objective, setObjective] = useState('');
  const [leadMode, setLeadMode] = useState<DeployPick>('managed');
  const [leadManagedId, setLeadManagedId] = useState('');
  const [leadByoRef, setLeadByoRef] = useState('');
  const [leadPrompt, setLeadPrompt] = useState('');
  const [workers, setWorkers] = useState<MemberDraft[]>([emptyMember()]);
  const [error, setError] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [ownerId, setOwnerId] = useState('');

  const agents = useQuery({ queryKey: ['product-agents'], queryFn: listAgents });
  const dataplanes = useQuery({
    queryKey: ['dataplanes', 'default'],
    queryFn: () => fetchDataPlanes(undefined, 'default'),
  });

  useEffect(() => {
    me()
      .then((m) => setOwnerId(m.userId))
      .catch(() => setOwnerId(''));
  }, []);

  const managedAgents = agents.data || [];
  const byoOptions = useMemo(() => {
    const names = new Set<string>();
    for (const d of dataplanes.data?.dataplanes || []) {
      if (d.agentName) names.add(d.agentName);
    }
    return Array.from(names).sort();
  }, [dataplanes.data]);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    setError('');
    const leadRef = agentRefFor(leadMode, leadManagedId, leadByoRef, managedAgents);
    if (!name.trim() || !objective.trim() || !leadRef) {
      setError(t('teams.create.validation.required'));
      return;
    }
    if (leadMode === 'managed' && !ownerId) {
      setError(t('teams.create.validation.managedLeadOwner'));
      return;
    }

    const members: TeamCreateMember[] = [];
    for (const [i, w] of workers.entries()) {
      const ref = agentRefFor(w.deployMode, w.managedAgentId, w.byoAgentRef, managedAgents);
      if (!ref) continue;
      const memberName = w.name.trim() || `worker-${i + 1}`;
      if (w.deployMode === 'managed' && !ownerId) {
        setError(t('teams.create.validation.managedWorkerOwner', { name: memberName }));
        return;
      }
      members.push({
        name: memberName,
        agentRef: ref,
        prompt: w.prompt.trim() || undefined,
        deployMode: w.deployMode,
        managedAgentId: w.deployMode === 'managed' ? w.managedAgentId : undefined,
        ownerId: w.deployMode === 'managed' ? ownerId : undefined,
      });
    }

    const body: TeamCreateRequest = {
      name: name.trim(),
      namespace: namespace.trim() || 'default',
      objective: objective.trim(),
      lead: {
        agentRef: leadRef,
        prompt: leadPrompt.trim() || undefined,
        deployMode: leadMode,
        managedAgentId: leadMode === 'managed' ? leadManagedId : undefined,
        ownerId: leadMode === 'managed' ? ownerId : undefined,
      },
      members,
    };

    setSubmitting(true);
    try {
      const res = await createTeam(body);
      const ns = res.team.namespace || body.namespace || 'default';
      navigate(`/teams/${encodeURIComponent(res.team.name)}?namespace=${encodeURIComponent(ns)}`);
    } catch (err) {
      setError(resolveApiErrorMessage(err, t('teams.create.failed')));
    } finally {
      setSubmitting(false);
    }
  }

  return (
    <Page className="max-w-3xl">
      <PageHeader
        title={t('teams.new')}
        description={t('teams.create.description')}
        actions={
          <Button variant="outline" asChild>
            <Link to="/teams/list">{t('common.cancel')}</Link>
          </Button>
        }
      />

      <form onSubmit={onSubmit} className="space-y-8 rounded-xl border border-border bg-white p-6 shadow-sm">
        <section className="grid gap-4 sm:grid-cols-2">
          <label className="grid gap-1.5 text-sm sm:col-span-1">
            <span className="font-medium">{t('teams.name')}</span>
            <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="research" required />
          </label>
          <label className="grid gap-1.5 text-sm">
            <span className="font-medium">{t('teams.namespace')}</span>
            <Input value={namespace} onChange={(e) => setNamespace(e.target.value)} placeholder="default" />
          </label>
          <label className="grid gap-1.5 text-sm sm:col-span-2">
            <span className="font-medium">{t('teams.objective')}</span>
            <textarea
              className="min-h-[88px] w-full rounded-lg border border-border bg-white px-3 py-2 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
              value={objective}
              onChange={(e) => setObjective(e.target.value)}
              placeholder={t('teams.create.objectivePlaceholder')}
              required
            />
          </label>
        </section>

        <section className="space-y-3">
          <h2 className="text-base font-semibold">{t('teams.lead')}</h2>
          <DeployModeFields
            mode={leadMode}
            onMode={setLeadMode}
            managedId={leadManagedId}
            onManagedId={setLeadManagedId}
            byoRef={leadByoRef}
            onByoRef={setLeadByoRef}
            managedAgents={managedAgents}
            byoOptions={byoOptions}
            prompt={leadPrompt}
            onPrompt={setLeadPrompt}
          />
        </section>

        <section className="space-y-3">
          <div className="flex items-center justify-between">
            <h2 className="text-base font-semibold">{t('teams.workers')}</h2>
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => setWorkers((w) => [...w, emptyMember()])}
            >
              {t('teams.create.addWorker')}
            </Button>
          </div>
          {workers.map((w, i) => (
            <div key={i} className="space-y-3 rounded-lg border border-border p-4">
              <div className="flex items-center justify-between gap-2">
                <label className="grid flex-1 gap-1.5 text-sm">
                  <span className="font-medium">{t('teams.create.memberName')}</span>
                  <Input
                    value={w.name}
                    onChange={(e) =>
                      setWorkers((all) =>
                        all.map((x, j) => (j === i ? { ...x, name: e.target.value } : x)),
                      )
                    }
                    placeholder={`worker-${i + 1}`}
                  />
                </label>
                {workers.length > 1 && (
                  <Button
                    type="button"
                    variant="ghost"
                    size="sm"
                    className="mt-6"
                    onClick={() => setWorkers((all) => all.filter((_, j) => j !== i))}
                  >
                    {t('teams.detail.remove')}
                  </Button>
                )}
              </div>
              <DeployModeFields
                mode={w.deployMode}
                onMode={(m) =>
                  setWorkers((all) => all.map((x, j) => (j === i ? { ...x, deployMode: m } : x)))
                }
                managedId={w.managedAgentId}
                onManagedId={(id) =>
                  setWorkers((all) =>
                    all.map((x, j) => (j === i ? { ...x, managedAgentId: id } : x)),
                  )
                }
                byoRef={w.byoAgentRef}
                onByoRef={(ref) =>
                  setWorkers((all) =>
                    all.map((x, j) => (j === i ? { ...x, byoAgentRef: ref } : x)),
                  )
                }
                managedAgents={managedAgents}
                byoOptions={byoOptions}
                prompt={w.prompt}
                onPrompt={(p) =>
                  setWorkers((all) => all.map((x, j) => (j === i ? { ...x, prompt: p } : x)))
                }
              />
            </div>
          ))}
        </section>

        {error && <p className="text-sm text-red-600">{error}</p>}

        <div className="flex justify-end gap-2">
          <Button type="button" variant="outline" asChild>
            <Link to="/teams/list">{t('common.cancel')}</Link>
          </Button>
          <Button type="submit" disabled={submitting}>
            {submitting ? t('teams.creating') : t('teams.create')}
          </Button>
        </div>
      </form>
    </Page>
  );
}

function DeployModeFields({
  mode,
  onMode,
  managedId,
  onManagedId,
  byoRef,
  onByoRef,
  managedAgents,
  byoOptions,
  prompt,
  onPrompt,
}: {
  mode: DeployPick;
  onMode: (m: DeployPick) => void;
  managedId: string;
  onManagedId: (id: string) => void;
  byoRef: string;
  onByoRef: (ref: string) => void;
  managedAgents: AgentDefinition[];
  byoOptions: string[];
  prompt: string;
  onPrompt: (p: string) => void;
}) {
  const t = useT();
  return (
    <div className="grid gap-3 sm:grid-cols-2">
      <label className="grid gap-1.5 text-sm">
        <span className="font-medium">{t('teams.create.deployMode')}</span>
        <select
          className="h-10 rounded-lg border border-border bg-white px-3 text-sm shadow-sm"
          value={mode}
          onChange={(e) => onMode(e.target.value as DeployPick)}
        >
          <option value="managed">{t('teams.create.managedMode')}</option>
          <option value="byo">{t('teams.create.byoMode')}</option>
        </select>
      </label>
      {mode === 'managed' ? (
        <label className="grid gap-1.5 text-sm">
          <span className="font-medium">{t('teams.create.managedAgent')}</span>
          <select
            className="h-10 rounded-lg border border-border bg-white px-3 text-sm shadow-sm"
            value={managedId}
            onChange={(e) => onManagedId(e.target.value)}
            required
          >
            <option value="">{t('teams.create.selectAgent')}</option>
            {managedAgents.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name} ({a.id})
              </option>
            ))}
          </select>
        </label>
      ) : (
        <label className="grid gap-1.5 text-sm">
          <span className="font-medium">{t('teams.create.registryAgentRef')}</span>
          <Input
            list="byo-agent-refs"
            value={byoRef}
            onChange={(e) => onByoRef(e.target.value)}
            placeholder="agent-name"
            required
          />
          <datalist id="byo-agent-refs">
            {byoOptions.map((n) => (
              <option key={n} value={n} />
            ))}
          </datalist>
        </label>
      )}
      <label className="grid gap-1.5 text-sm sm:col-span-2">
        <span className="font-medium">{t('teams.create.rolePrompt')}</span>
        <Input value={prompt} onChange={(e) => onPrompt(e.target.value)} placeholder={t('teams.create.rolePromptPlaceholder')} />
      </label>
    </div>
  );
}
