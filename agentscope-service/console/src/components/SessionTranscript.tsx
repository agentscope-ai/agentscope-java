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

import { useEffect, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { archiveManagedSession, deleteManagedSession, getManagedLifecycleReceipt, getManagedSession, managedLifecyclePending, restoreManagedSession, updateManagedSession, type ManagedDeletionReceipt, type ManagedSession } from '../api/managedSessions';

/** Session definitions stay pinned; title and user metadata remain editable. */
export default function SessionTranscript({ agentId, sessionId, onDeleted, embedded = false }: {
  agentId: string; sessionId: string; onDeleted?: () => void; embedded?: boolean;
}) {
  const navigate = useNavigate();
  const sessionEditingEnabled = false;
  const [session, setSession] = useState<ManagedSession>();
  const [title, setTitle] = useState('');
  const [metadata, setMetadata] = useState('{}');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [deletion, setDeletion] = useState<ManagedDeletionReceipt>();
  const lifecycleCommandId = session?.lifecycleCommandId;
  const lifecyclePending = session ? managedLifecyclePending(session) : false;
  useEffect(() => {
    let active = true;
    setSession(undefined); setDeletion(undefined);
    setError('');
    getManagedSession(sessionId).then(value => {
      if (!active) return;
      setSession(value); setTitle(value.title ?? ''); setMetadata(JSON.stringify(value.metadata ?? {}, null, 2));
    }).catch(e => { if (active) setError(e.message); });
    return () => { active = false; };
  }, [sessionId]);
  useEffect(() => {
    if (!lifecycleCommandId || !lifecyclePending || deletion) return;
    let active = true;
    const timer = setInterval(() => {
      getManagedSession(sessionId).then(value => { if (active) setSession(value); })
        .catch(e => { if (active) setError(e.message); });
    }, 2000);
    return () => { active = false; clearInterval(timer); };
  }, [sessionId, lifecycleCommandId, lifecyclePending, deletion]);
  useEffect(() => {
    if (!deletion || deletion.cleanup_status === 'completed') return;
    let active = true;
    const timer = setInterval(() => {
      getManagedLifecycleReceipt(sessionId, deletion.lifecycle_command_id).then(receipt => {
        if (!active) return;
        setDeletion(previous => previous ? { ...previous, cleanup_status: receipt.status } : previous);
        if (receipt.failure_code) setError('Cleanup could not be confirmed yet. The service will retry.');
        else setError('');
      }).catch(e => { if (active) setError(e.message); });
    }, 2000);
    return () => { active = false; clearInterval(timer); };
  }, [sessionId, deletion]);
  async function change(operation: () => Promise<ManagedSession>) {
    setBusy(true); setError('');
    try { setSession(await operation()); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  async function save() {
    let values: Record<string, string>;
    try {
      values = JSON.parse(metadata);
      if (!values || Array.isArray(values) || typeof values !== 'object' || Object.values(values).some(value => typeof value !== 'string')) throw new Error('Metadata must contain string values.');
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); return; }
    await change(() => updateManagedSession(sessionId, { title: title || null, metadata: values }));
  }
  async function remove() {
    if (!confirm('Delete this session entirely?')) return;
    setBusy(true); setError('');
    try {
      setDeletion(await deleteManagedSession(sessionId));
    } catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  return <section style={{ padding: embedded ? 16 : 28, maxWidth: 1000 }}>
    {!embedded && <><button onClick={() => navigate(-1)}>← Back</button><h2>Session details</h2></>}
    <p>{sessionId} · {session?.status} · {session?.runtimeStatus}</p>
    {error && <p role="alert">{error}</p>}
    {deletion && <p role="status">{deletion.cleanup_status === 'completed'
      ? 'Deletion and workspace cleanup completed.' : 'Deletion accepted. Execution is stopping and workspace cleanup is pending.'}{' '}
      <button onClick={() => { if (onDeleted) onDeleted(); else navigate(`/managed/sessions?agentId=${encodeURIComponent(agentId)}`, { replace: true }); }}>Back to sessions</button></p>}
    {session && !deletion && <>
      {managedLifecyclePending(session) && <p role="status">Execution is stopping; cleanup status: {session.cleanupStatus}. Restore is available after cleanup completes.</p>}
      <p><Link to={`/managed/sessions/${encodeURIComponent(sessionId)}`}>Open execution</Link>{' '}
        {sessionEditingEnabled && (session.status === 'archived'
          ? <button disabled={busy || managedLifecyclePending(session)} onClick={() => void change(() => restoreManagedSession(sessionId))}>Restore</button>
          : <button disabled={busy} onClick={() => void change(() => archiveManagedSession(sessionId))}>Archive</button>)}{' '}
        {sessionEditingEnabled && <button disabled={busy || managedLifecyclePending(session)} onClick={() => void remove()}>Delete</button>}</p>
      <h3>Configuration</h3>
      <p>Agent and resource bindings are fixed when the Session is created. Create another Session to use a different configuration.</p>
      <dl><dt>Agent</dt><dd>{session.agentId} · version {session.agentVersion}</dd>
        <dt>Environment</dt><dd>{session.environmentId}</dd>
        <dt>Vaults</dt><dd>{session.vaultIds?.join(', ') || '—'}</dd>
        <dt>Memory stores</dt><dd>{session.memoryStoreIds?.join(', ') || '—'}</dd></dl>
      <h3>Title and metadata</h3>
      <form onSubmit={event => { event.preventDefault(); void save(); }}>
        <label>Title <input value={title} maxLength={500} disabled={!sessionEditingEnabled || busy || session.status !== 'active'} onChange={event => setTitle(event.target.value)} /></label>
        <label style={{ display: 'block', marginTop: 12 }}>Metadata
          <textarea value={metadata} rows={6} style={{ display: 'block', width: '100%' }} disabled={!sessionEditingEnabled || busy || session.status !== 'active'} onChange={event => setMetadata(event.target.value)} /></label>
        <button disabled={!sessionEditingEnabled || busy || session.status !== 'active'} type="submit">Save</button>
      </form>
    </>}
  </section>;
}
