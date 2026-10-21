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

import { useEffect, useRef, useState, type FormEvent } from 'react';
import { getAgent } from '../api/agents';
import { listEnvironments, ensureDefaultEnvironment, type Environment } from '../api/environments';
import { createManagedSession, type ManagedSession } from '../api/managedSessions';

export interface NewManagedSessionFormProps {
  agentId: string;
  /** When true, render as a modal overlay; otherwise as an inline panel. */
  modal?: boolean;
  initialFileIds?: string[];
  onCreated: (session: ManagedSession) => void;
  onCancel?: () => void;
}

export default function NewManagedSessionForm({ agentId, modal = true, initialFileIds = [], onCreated, onCancel }: NewManagedSessionFormProps) {
  const [environments, setEnvironments] = useState<Environment[]>([]);
  const [environmentId, setEnvironmentId] = useState('');
  const [title, setTitle] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const requestKey = useRef(crypto.randomUUID());
  const pendingBody = useRef<string>();
  useEffect(() => {
    let active = true;
    Promise.all([listEnvironments(), getAgent(agentId)]).then(([values, agent]) => {
      if (active) { setEnvironments(values); setEnvironmentId(agent.defaultEnvironmentId ?? ''); }
    }).catch(reason => { if (active) setError(String(reason)); });
    return () => { active = false; };
  }, [agentId]);
  async function submit(event: FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      if (initialFileIds.length) throw new Error('File input is unavailable in this release. Create a text Session without attachments.');
      const environment = environmentId || (await ensureDefaultEnvironment()).id;
      const definition = { agent: agentId, environmentId: environment, title: title || undefined };
      const signature = JSON.stringify(definition);
      if (pendingBody.current !== signature) { pendingBody.current = signature; requestKey.current = crypto.randomUUID(); }
      onCreated(await createManagedSession(definition, requestKey.current));
    } catch (reason) { setError(reason instanceof Error ? reason.message : String(reason)); }
    finally { setBusy(false); }
  }
  const body = <form onSubmit={submit} style={{ padding: 24, background: 'white', borderRadius: 12, minWidth: 320 }}>
    <h3>New session</h3>
    <p>Use the selected Agent configuration and send a message to begin.</p>
    <label>Title <input aria-label="Session title" value={title} onChange={event => setTitle(event.target.value)} /></label>
    <p><label>Environment <select aria-label="Session environment" value={environmentId} onChange={event => setEnvironmentId(event.target.value)}>
      <option value="">Agent default environment</option>
      {environments.map(value => <option key={value.id} value={value.id}>{value.name}</option>)}
    </select></label></p>
    {error && <p role="alert">{error}</p>}
    <button type="submit" disabled={busy}>{busy ? 'Creating…' : 'Create session'}</button>{' '}
    <button type="button" onClick={onCancel} disabled={busy}>Cancel</button>
  </form>;
  return modal ? <div role="dialog" aria-modal="true" style={{ position: 'fixed', inset: 0, display: 'grid', placeItems: 'center', background: '#0006', zIndex: 100 }}>{body}</div> : body;
}
