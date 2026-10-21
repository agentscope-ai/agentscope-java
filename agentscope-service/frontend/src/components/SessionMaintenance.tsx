/*
 * Copyright 2024-2026 the original author or authors.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { useEffect, useRef, useState } from 'react';
import {
  AgentBudget, AgentBudgetLimits, AgentCheckpoint, AgentCheckpointCommand, AgentCheckpointPage,
  AgentRequestError, AgentSessionCapabilities, forkAgentSession, getAgentBudget, getAgentCheckpointOperation, cancelAgentCheckpointOperation,
  listAgentCheckpoints, restoreAgentCheckpoint, setAgentBudget,
} from '../api/agentSessions';
import { CheckpointRetry, clearCheckpointRetry, readCheckpointRetry, saveCheckpointRetry } from '../features/conversation/checkpointRetry';

export default function SessionMaintenance({ sessionId, capabilities, readOnly }: {
  sessionId: string; capabilities: AgentSessionCapabilities; readOnly: boolean;
}) {
  const [open, setOpen] = useState(false);
  const [loaded, setLoaded] = useState(false);
  return <details open={open} onToggle={event => {
    setOpen(event.currentTarget.open);
    if (event.currentTarget.open) setLoaded(true);
  }}>
    <summary>Checkpoints and budget</summary>
    {loaded && <>
      {capabilities.capabilities.checkpoints && <Checkpoints key={sessionId} sessionId={sessionId}
        capabilities={capabilities} readOnly={readOnly} />}
      {capabilities.capabilities.budget && <Budget key={sessionId} sessionId={sessionId} readOnly={readOnly} />}
    </>}
  </details>;
}

function Checkpoints({ sessionId, capabilities, readOnly }: {
  sessionId: string; capabilities: AgentSessionCapabilities; readOnly: boolean;
}) {
  const [records, setRecords] = useState<AgentCheckpoint[]>([]);
  const [page, setPage] = useState<AgentCheckpointPage>();
  const [retained] = useState(() => readCheckpointRetry(sessionId));
  const [pendingRequest, setPendingRequest] = useState<CheckpointRetry | undefined>(retained.pending);
  const [selected, setSelected] = useState(retained.pending?.checkpointId ?? '');
  const [target, setTarget] = useState(retained.pending?.target ?? '');
  const [command, setCommand] = useState<AgentCheckpointCommand | undefined>(retained.pending?.receipt);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const controller = useRef(new AbortController());
  useEffect(() => {
    const current = new AbortController(); controller.current = current;
    return () => current.abort();
  }, []);
  async function act(operation: () => Promise<void>) {
    setBusy(true); setError('');
    try { await operation(); }
    catch (e) { if (!controller.current.signal.aborted) setError(e instanceof Error ? e.message : String(e)); }
    finally { if (!controller.current.signal.aborted) setBusy(false); }
  }
  async function load(reset: boolean) {
    await act(async () => {
      const next = await listAgentCheckpoints(sessionId, reset ? undefined : page?.next_cursor ?? undefined,
        reset ? undefined : page?.as_of, controller.current.signal);
      if (controller.current.signal.aborted) return;
      if (!reset && next.as_of !== page?.as_of || next.has_more && next.next_cursor === page?.next_cursor)
        throw new Error('Checkpoint paging did not advance. Refresh the list.');
      const merged = reset ? next.data : [...records, ...next.data];
      if (merged.length > 1000) throw new Error('Checkpoint display limit reached. Refresh to start a new view.');
      setRecords(merged); setPage(next);
      if (reset && !pendingRequest) setSelected('');
    });
  }
  async function submit(fork: boolean) {
    const request = pendingRequest ?? { checkpointId: selected,
      ...(fork ? { target: target.trim() } : {}), key: crypto.randomUUID() };
    await act(async () => {
      saveCheckpointRetry(sessionId, request);
      setPendingRequest(request);
      let receipt: AgentCheckpointCommand;
      try {
        receipt = request.target
          ? await forkAgentSession(sessionId, request.target, request.checkpointId, request.key)
          : await restoreAgentCheckpoint(sessionId, request.checkpointId, request.key);
      } catch (e) {
        if (!pendingRequest && e instanceof AgentRequestError && [400, 401, 403, 404, 409, 413, 422].includes(e.status)) {
          clearCheckpointRetry(sessionId, request.key);
          if (!controller.current.signal.aborted) setPendingRequest(undefined);
        }
        throw e;
      }
      confirm(request, receipt);
    });
  }
  function confirm(request: CheckpointRetry, receipt: AgentCheckpointCommand) {
    if (receipt.source_session_id !== sessionId || receipt.session_id !== (request.target ?? sessionId)
      || !receipt.operation_id || receipt.operation_id !== receipt.command_id)
      throw new Error('Recovery receipt does not match the retained request.');
    if (['completed', 'rejected', 'cancelled'].includes(receipt.command_status)) clearCheckpointRetry(sessionId, request.key);
    else saveCheckpointRetry(sessionId, { ...request, receipt }, true);
    if (!controller.current.signal.aborted) {
      setCommand(receipt);
      setPendingRequest(['completed', 'rejected', 'cancelled'].includes(receipt.command_status) ? undefined : { ...request, receipt });
    }
  }
  const pending = !!pendingRequest;
  const canWrite = !readOnly && !retained.error && !pending && !busy && !!selected;
  return <section style={{ padding: 12 }}>
    <h3>Checkpoints</h3>
    {retained.error && <p role="alert">{retained.error}</p>}
    <button disabled={busy} onClick={() => void load(true)}>Refresh checkpoints</button>
    {records.map(record => <p key={record.checkpoint_id}>
      <label><input type="radio" name={`checkpoint-${sessionId}`} value={record.checkpoint_id}
        checked={selected === record.checkpoint_id} disabled={busy || pending}
        onChange={() => setSelected(record.checkpoint_id)} /> {new Date(record.created_at).toLocaleString()} · {record.turn_id || 'Saved context'}{record.workspace_available ? ' · Workspace image saved' : ''}</label>
    </p>)}
    {page && records.length === 0 && <p>{page.has_more ? 'No checkpoint in this page. Continue loading.' : 'No saved checkpoints.'}</p>}
    {page?.has_more && <button disabled={busy} onClick={() => void load(false)}>Load more checkpoints</button>}
    {pendingRequest && !command && <p role="status">Recovery outcome is not confirmed. Retry the retained request to retrieve its receipt.{' '}
      <button disabled={busy || readOnly} onClick={() => void submit(!!pendingRequest.target)}>Retry pending recovery</button>
    </p>}
    {!readOnly && <div>
      <p>Restore requires an idle Session. It keeps history and does not undo external actions.</p>
      {capabilities.capabilities.checkpoint_restore && <button disabled={!canWrite} onClick={() => void submit(false)}>Restore selected checkpoint</button>}
      {capabilities.capabilities.fork && <div style={{ marginTop: 8 }}>
        <input aria-label="Fork target Session ID" placeholder="Empty target Session ID" maxLength={256}
          value={target} disabled={pending || busy} onChange={event => setTarget(event.target.value)} />{' '}
        <button disabled={!canWrite || !target.trim()} onClick={() => void submit(true)}>Fork to target Session</button>
        <p>Create the empty target with the same Agent first. Its environment, files and budget stay independent.</p>
      </div>}
    </div>}
    {command && <p role="status">Recovery: {command.command_status} {command.error?.code}
      {' '}<code>{command.command_id}</code>{' '}
      <button disabled={busy} onClick={() => void act(async () => {
        const latest = await getAgentCheckpointOperation(sessionId, command.operation_id, controller.current.signal);
        if (pendingRequest) confirm(pendingRequest, latest);
        else if (!controller.current.signal.aborted) setCommand(latest);
      })}>Refresh recovery status</button>
      {!readOnly && pendingRequest && ['validating', 'validated', 'snapshotting'].includes(command.command_status)
        && <button disabled={busy} onClick={() => void act(async () => {
          const latest = await cancelAgentCheckpointOperation(sessionId, command.operation_id);
          confirm(pendingRequest, latest);
        })}>Cancel recovery</button>}
    </p>}
    {error && <p role="alert">{error}</p>}
  </section>;
}

function Budget({ sessionId, readOnly }: { sessionId: string; readOnly: boolean }) {
  const [budget, setBudget] = useState<AgentBudget>();
  const [calls, setCalls] = useState('');
  const [tokens, setTokens] = useState('');
  const [cost, setCost] = useState('');
  const [currency, setCurrency] = useState('USD');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [saved, setSaved] = useState(false);
  const controller = useRef(new AbortController());
  useEffect(() => {
    const current = new AbortController(); controller.current = current;
    getAgentBudget(sessionId, current.signal).then(value => { if (!current.signal.aborted) setBudget(value); })
      .catch(e => { if (!current.signal.aborted) setError(e.message); });
    return () => current.abort();
  }, [sessionId]);
  async function refresh() {
    setBusy(true); setError('');
    try {
      const current = await getAgentBudget(sessionId, controller.current.signal);
      if (!controller.current.signal.aborted) { setBudget(current); setSaved(false); }
    } catch (e) { if (!controller.current.signal.aborted) setError(e instanceof Error ? e.message : String(e)); }
    finally { if (!controller.current.signal.aborted) setBusy(false); }
  }
  async function save() {
    setBusy(true); setError(''); setSaved(false);
    try {
      const limits: AgentBudgetLimits = {};
      for (const [key, text] of [['max_model_calls', calls], ['max_total_tokens', tokens]] as const) {
        if (!text.trim()) continue;
        const value = Number(text);
        if (!Number.isSafeInteger(value) || value < 1) throw new Error('Call and token limits must be positive whole numbers.');
        limits[key] = value;
      }
      if (cost.trim()) { limits.max_cost = cost.trim(); limits.currency = currency.trim().toUpperCase(); }
      const result = await setAgentBudget(sessionId, limits, budget!.revision);
      if (!controller.current.signal.aborted) { setBudget({ ...budget!, ...result }); setSaved(true); }
    } catch (e) { if (!controller.current.signal.aborted) setError(e instanceof Error ? e.message : String(e)); }
    finally { if (!controller.current.signal.aborted) setBusy(false); }
  }
  return <section style={{ padding: 12 }}>
    <h3>Session budget</h3>
    <button disabled={busy} onClick={() => void refresh()}>Refresh budget</button>
    {budget && <>
      <p>Current limits: {JSON.stringify(budget.limits)}</p>
      {!readOnly && budget.root_session_id === sessionId && <form onSubmit={event => { event.preventDefault(); void save(); }}>
        <p>Replace limits below. Blank fields remove a limit; leaving all fields blank clears the Session budget.</p>
        <label>Model calls <input inputMode="numeric" value={calls} disabled={busy} onChange={event => setCalls(event.target.value)} /></label>{' '}
        <label>Total tokens <input inputMode="numeric" value={tokens} disabled={busy} onChange={event => setTokens(event.target.value)} /></label>{' '}
        <label>Cost <input inputMode="decimal" value={cost} maxLength={128} disabled={busy} onChange={event => setCost(event.target.value)} /></label>{' '}
        <label>Currency <input value={currency} maxLength={3} disabled={busy} onChange={event => setCurrency(event.target.value)} /></label>{' '}
        <button disabled={busy} type="submit">Save limits</button>
      </form>}
    </>}
    {saved && <p role="status">Limits saved; they apply before the next model call.</p>}
    <p>Root and child Agents share this budget. Limits use reported usage; concurrent calls can exceed them.</p>
    {error && <p role="alert">{error}</p>}
  </section>;
}
