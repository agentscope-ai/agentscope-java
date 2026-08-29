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

import React, { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { AgentDefinition, listAgents } from '../api/agents';
import NewManagedSessionForm from '../components/NewManagedSessionForm';

const S: Record<string, React.CSSProperties> = {
  root: { padding: '28px 32px', minWidth: 0, maxWidth: 640 },
  back: {
    background: 'none', border: 'none', color: '#6366f1', cursor: 'pointer',
    fontSize: '0.88rem', fontWeight: 500, padding: 0, marginBottom: 16,
    textDecoration: 'none', display: 'inline-block',
  },
  title: { margin: '0 0 8px', fontSize: '1.4rem', fontWeight: 700, color: '#0f172a' },
  hint: { fontSize: '0.88rem', color: '#64748b', marginBottom: 20, lineHeight: 1.5 },
  field: { display: 'block', fontSize: '0.82rem', color: '#64748b', marginBottom: 6, fontWeight: 500 },
  select: {
    width: '100%', boxSizing: 'border-box', padding: '10px 12px', marginBottom: 18,
    border: '1px solid #cbd5e1', borderRadius: 8, fontSize: '0.92rem', background: '#ffffff',
  },
  err: { color: '#dc2626', fontSize: '0.9rem', marginBottom: 12 },
  panel: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 14,
    padding: '8px 4px', boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
};

/**
 * Create a Managed session definition (static bind). Does not post user.message.
 */
export default function SessionCreatePage() {
  const [searchParams] = useSearchParams();
  const prefAgentId = searchParams.get('agentId') ?? '';
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [agentId, setAgentId] = useState(prefAgentId);
  const [loadErr, setLoadErr] = useState<string | null>(null);
  const navigate = useNavigate();

  useEffect(() => {
    let cancelled = false;
    listAgents()
      .then(list => {
        if (cancelled) return;
        setAgents(list);
        if (!prefAgentId && list.length === 1) {
          setAgentId(list[0].id);
        }
      })
      .catch(e => {
        if (!cancelled) setLoadErr(e instanceof Error ? e.message : 'Failed to load agents');
      });
    return () => { cancelled = true; };
  }, [prefAgentId]);

  useEffect(() => {
    if (prefAgentId) setAgentId(prefAgentId);
  }, [prefAgentId]);

  return (
    <div style={S.root}>
      <Link to={agentId ? `/sessions?agentId=${encodeURIComponent(agentId)}` : '/sessions'} style={S.back}>
        ← Sessions
      </Link>
      <h1 style={S.title}>New session</h1>
      <p style={S.hint}>
        Creates a session resource bound to an agent and mounts. No turn starts until you send a
        message in Chat.
      </p>
      {loadErr && <div style={S.err}>{loadErr}</div>}

      <label style={S.field}>Agent</label>
      <select
        style={S.select}
        value={agentId}
        onChange={e => setAgentId(e.target.value)}
        required
      >
        <option value="">Select agent…</option>
        {agents.map(a => (
          <option key={a.id} value={a.id}>{a.name}</option>
        ))}
      </select>

      {agentId ? (
        <div style={S.panel}>
          <NewManagedSessionForm
            agentId={agentId}
            modal={false}
            onCancel={() => navigate(agentId
              ? `/sessions?agentId=${encodeURIComponent(agentId)}`
              : '/sessions')}
            onCreated={session => {
              navigate(`/sessions/${encodeURIComponent(session.id)}`, { replace: true });
            }}
          />
        </div>
      ) : (
        <div style={{ color: '#94a3b8', fontSize: '0.9rem' }}>
          Choose an agent to configure environment, vaults, and memory mounts.
        </div>
      )}
    </div>
  );
}
