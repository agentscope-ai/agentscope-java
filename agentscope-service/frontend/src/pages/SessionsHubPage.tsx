/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *     http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router-dom';
import { listAgents, type AgentDefinition } from '../api/agents';

/** The first protocol release supports create/open, while Session listing is unavailable. */
export default function SessionsHubPage() {
  const [searchParams, setSearchParams] = useSearchParams();
  const [agents, setAgents] = useState<AgentDefinition[]>([]);
  const [sessionId, setSessionId] = useState('');
  const [error, setError] = useState('');
  const navigate = useNavigate();
  const agent = searchParams.get('agentId') ?? '';
  useEffect(() => {
    let active = true;
    listAgents().then(values => { if (active) setAgents(values); }).catch(reason => { if (active) setError(String(reason)); });
    return () => { active = false; };
  }, []);
  const create = '/managed/sessions/new' + (agent ? '?agentId=' + encodeURIComponent(agent) : '');
  return <div className="console-page-legacy" style={{ padding: 28, maxWidth: 1000 }}>
    <h2>Sessions</h2>
    <p>Create a Session or open an existing Session by ID. Session listing and lifecycle controls are unavailable in this release.</p>
    {error && <p role="alert">{error}</p>}
    <label>Agent <select aria-label="Agent" value={agent} onChange={event => {
      const next = new URLSearchParams(searchParams);
      if (event.target.value) next.set('agentId', event.target.value); else next.delete('agentId');
      setSearchParams(next, { replace: true });
    }}>
      <option value="">Choose when creating</option>
      {agents.map(value => <option key={value.id} value={value.id}>{value.name}</option>)}
    </select></label>{' '}
    <Link to={create}>New session</Link>
    <form onSubmit={event => { event.preventDefault(); navigate('/managed/sessions/' + encodeURIComponent(sessionId.trim())); }} style={{ marginTop: 24 }}>
      <label>Session ID <input aria-label="Session ID" value={sessionId} onChange={event => setSessionId(event.target.value)} /></label>{' '}
      <button type="submit" disabled={!sessionId.trim()}>Open session</button>
    </form>
  </div>;
}
