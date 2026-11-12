import { useEffect, useRef, useState } from 'react';
import {
  getAgentTurnOutput, type SessionProtocolEvent, AgentSessionEvent, AgentSessionSnapshot, AgentSubagentSnapshot, AgentTurn, getAgentSessionSnapshot, streamAgentSession, listAgentTurnPage,
  submitAgentTurn, cancelAgentTurn, answerAgentActions,
  steerAgentTurn, injectAgentContext, uploadAgentFile, getAgentSubagentSnapshot,
  getAgentResourcePage, answerAgentSignal, getAgentTurn, getAgentTurnCapabilities, mergeAgentTurns,
  AgentTurnPage, AgentTurnCapabilities, AgentSessionCapabilities, getAgentSessionCapabilities,
} from '../api/agentSessions';
import { AgentSessionView, AgentViewLimitError } from '../api/agentSessionView';
import ToolExecutionRecovery from './ToolExecutionRecovery';
import SessionArtifacts from './SessionArtifacts';
import SessionMaintenance from './SessionMaintenance';
import SessionEventExport from './SessionEventExport';

/** Restores committed message/tool prefixes and follows the shared session event cursor. */
export default function SessionExecution({ sessionId, readOnly = false, initialMessage = '', initialRequestKey }: { sessionId: string; readOnly?: boolean; initialMessage?: string; initialRequestKey?: string }) {
  const [view, setView] = useState<AgentSessionSnapshot>();
  const [mode, setMode] = useState<'submit' | 'steer' | 'inject'>('submit');
  const fileInput = useRef<HTMLInputElement>(null);
  const viewReducer = useRef<AgentSessionView>();
  const [file, setFile] = useState<File>();
  const [childView, setChildView] = useState<AgentSubagentSnapshot>();
  const [events, setEvents] = useState<AgentSessionEvent[]>([]);
  const [actions, setActions] = useState<AgentSessionEvent[]>([]);
  const [turns, setTurns] = useState<AgentTurn[]>([]);
  const turnRecords = useRef<AgentTurn[]>([]);
  function updateTurns(incoming: AgentTurn[], fresh = false) {
    const merged = mergeAgentTurns(turnRecords.current, incoming, fresh);
    turnRecords.current = merged; setTurns(merged);
  }
  const [turnPage, setTurnPage] = useState<AgentTurnPage>();
  const [selectedTurnId, setSelectedTurnId] = useState<string>();
  const [capabilities, setCapabilities] = useState<AgentTurnCapabilities>();
  const [sessionCapabilities, setSessionCapabilities] = useState<AgentSessionCapabilities>();
  const selectedTurn = turns.find(turn => turn.id === selectedTurnId);
  const selectedStatus = selectedTurn?.status;
  const [finalOutput, setFinalOutput] = useState<SessionProtocolEvent[]>([]);
  const [outputError, setOutputError] = useState('');
  const [outputRefresh, setOutputRefresh] = useState(0);
  useEffect(() => { setFinalOutput([]); setOutputError(''); }, [sessionId, selectedTurnId]);
  useEffect(() => {
    const controller = new AbortController();
    let timer: ReturnType<typeof setTimeout> | undefined;
    const terminal = selectedStatus && ['completed', 'failed', 'cancelled'].includes(selectedStatus);
    const observe = async () => {
      try {
        if (!selectedTurnId) return;
        if (!terminal) {
          const turn = await getAgentTurn(sessionId, selectedTurnId, controller.signal);
          if (!controller.signal.aborted) updateTurns([turn], true);
        } else {
          const collected: SessionProtocolEvent[] = [], cursors = new Set<string>();
          let after = '';
          while (true) {
            const page = await getAgentTurnOutput(sessionId, selectedTurnId, after, controller.signal);
            if (!Array.isArray(page.data) || typeof page.output_available !== 'boolean' || (!page.output_available && (page.data.length > 0 || page.has_more)) || typeof page.has_more !== 'boolean' ||
              (page.has_more ? !page.next_cursor || cursors.has(page.next_cursor) : page.next_cursor != null)) throw new Error('Invalid final output page');
            collected.push(...page.data);
            if (collected.length > 2000) throw new Error('Final output exceeds the display limit');
            if (!page.has_more) break;
            after = page.next_cursor!; cursors.add(after);
          }
          if (!controller.signal.aborted) { setFinalOutput(collected); setOutputError(''); }
          return;
        }
      } catch (reason) { if (!controller.signal.aborted) setOutputError(String(reason)); }
      if (!controller.signal.aborted) timer = setTimeout(() => void observe(), 1000);
    };
    if (selectedTurnId) void observe();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [sessionId, selectedTurnId, selectedStatus, outputRefresh]);
  const cancelKeys = useRef(new Map<string, string>());
  async function cancel(turn: string) {
    const key = cancelKeys.current.get(turn) ?? crypto.randomUUID();
    cancelKeys.current.set(turn, key);
    await cancelAgentTurn(sessionId, turn, key);
    cancelKeys.current.delete(turn);
  }

  const available = (command: string) => !!capabilities?.available_commands.includes(command);
  const [connection, setConnection] = useState('loading');
  const [error, setError] = useState('');
  const [message, setMessage] = useState(initialMessage);
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  // Retain the key on an ambiguous submit so a retry cannot enqueue a second turn.
  const [submission, setSubmission] = useState<{ message: string; mode: string; file?: File; fileId?: string; turn?: string; key: string }>();
  useEffect(() => { setSessionCapabilities(undefined); }, [sessionId]);
  useEffect(() => {
    const controller = new AbortController();
    setError(''); setConnection('loading'); setEvents([]); setActions([]); setView(undefined); setChildView(undefined); setTurns([]); turnRecords.current = []; setTurnPage(undefined); setCapabilities(undefined); setSelectedTurnId(undefined);
    (async () => {
      const seen = new Set<string>();
      let reducer: AgentSessionView;
      const restore = async () => {
      const [snapshot, latest, running, sessionCapabilities] = await Promise.all([
        getAgentSessionSnapshot(sessionId, controller.signal),
        listAgentTurnPage(sessionId, undefined, undefined, controller.signal),
        listAgentTurnPage(sessionId, undefined, 'running', controller.signal),
        getAgentSessionCapabilities(sessionId, controller.signal),
      ]);
      if (controller.signal.aborted) return;
      reducer = new AgentSessionView(snapshot);
      viewReducer.current = reducer;
      setView(snapshot); setActions(snapshot.required_actions);
      setSessionCapabilities(sessionCapabilities);
      updateTurns(mergeAgentTurns(latest.data, running.data)); setTurnPage(latest);
      setSelectedTurnId(running.data[0]?.id ?? latest.data[0]?.id);
        seen.clear();
      };
      await streamAgentSession(sessionId, { signal: controller.signal, onConnection: setConnection, onReady: restore,
        onUnavailable: async () => { if (!viewReducer.current) await restore(); },
        onEvent: async event => {
          if (event.id && seen.has(event.id)) return;
          if (event.id) { seen.add(event.id); if (seen.size > 1024) seen.delete(seen.values().next().value!); }
          if (event.type === 'required_action.resolved') {
            const id = String(event.data.request_id);
            for (const signature of answerKeys.current.keys()) if (JSON.parse(signature)[0] === id) answerKeys.current.delete(signature);
            setAnswers(previous => { const next = { ...previous }; delete next[id]; return next; });
          }
          if (event.type === 'required_action.rejected')
            for (const signature of answerKeys.current.keys()) if (JSON.parse(signature)[0] === event.data.request_id) answerKeys.current.delete(signature);
          reducer.apply(event);
          const updated = reducer.snapshot();
          setView(updated); setActions(updated.required_actions);
          // The diagnostic tail is bounded; conversation state is reconciled by resource ID.
          setEvents(previous => [...previous, event].slice(-100));
          if (event.type.startsWith('turn.') && event.data.turn_id) {
            try {
              const turn = await getAgentTurn(sessionId, String(event.data.turn_id), controller.signal);
              if (!controller.signal.aborted) {
                updateTurns([turn], true);
                setSelectedTurnId(previous => previous ?? turn.id);
              }
            } catch (e) { if (!controller.signal.aborted) setError(e instanceof Error ? e.message : String(e)); }
          }
        },
      });
    })().catch(e => { if (!controller.signal.aborted) { setError(e.message); setConnection(e instanceof AgentViewLimitError ? 'display paused' : 'disconnected'); } });
    return () => { controller.abort(); viewReducer.current = undefined; };
  }, [sessionId, revision]);
  const selectedActionRevision = actions.filter(event => event.data.turn_id === selectedTurnId).map(event => `${event.data.request_id}:${event.type}`).join(',');
  useEffect(() => {
    const controller = new AbortController();
    setCapabilities(undefined);
    if (selectedTurnId) getAgentTurnCapabilities(sessionId, selectedTurnId, controller.signal)
      .then(value => { if (!controller.signal.aborted) setCapabilities(value); })
      .catch(e => { if (!controller.signal.aborted) setError(e.message); });
    return () => controller.abort();
  }, [sessionId, selectedTurnId, selectedTurn?.status, selectedTurn?.updatedAt, selectedActionRevision]);
  useEffect(() => {
    if (mode !== 'submit' && !capabilities?.available_commands.includes(mode)) setMode('submit');
  }, [capabilities, mode]);
  async function act(operation: () => Promise<unknown>) {
    setBusy(true); setError('');
    try { await operation(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  async function send() {
    if (file && !sessionCapabilities?.capabilities.file_input) { setError('This Session does not support file input.'); return; }
    const turn = mode !== 'submit' && available(mode) ? selectedTurnId : undefined;
    if (mode !== 'submit' && !turn) { setError('No selected turn accepts this input.'); return; }
    const current = submission?.message === message && submission.mode === mode && submission.file === file && submission.turn === turn
      ? submission : { message, mode, file, turn, key: crypto.randomUUID(), fileId: undefined as string | undefined };
    setSubmission(current);
    await act(async () => {
      if (file && !current.fileId) {
        current.fileId = (await uploadAgentFile(sessionId, file, `${current.key}-file`)).file_id;
        setSubmission({ ...current });
      }
      const content: Record<string, unknown>[] = [];
      if (message.trim()) content.push({ type: 'text', text: message });
      if (current.fileId) content.push({ type: 'file', file_id: current.fileId });
      const input = { input: [{ role: 'user' as const, content }] };
      if (mode === 'steer') await steerAgentTurn(sessionId, turn!, input, current.key);
      else if (mode === 'inject') await injectAgentContext(sessionId, turn!, input, current.key);
      else {
        const accepted = await submitAgentTurn(sessionId, input, current.key);
        updateTurns([accepted], true); setSelectedTurnId(accepted.id);
      }
      setMessage(''); setFile(undefined); setSubmission(undefined);
      if (fileInput.current) fileInput.current.value = '';
    });
  }
  const answerKeys = useRef(new Map<string, string>());
  useEffect(() => {
    setSubmission(initialRequestKey ? { message: initialMessage, mode: 'submit', key: initialRequestKey } : undefined);
    setFile(undefined); setMessage(initialMessage); setAnswers({}); answerKeys.current.clear();
    if (fileInput.current) fileInput.current.value = '';
  }, [sessionId, initialMessage, initialRequestKey]);
  function answer(event: AgentSessionEvent, allow?: boolean) {
    const id = String(event.data.request_id);
    const signature = JSON.stringify([id, allow, answers[id]]);
    if (!answerKeys.current.has(signature)) {
      if (answerKeys.current.size >= 1024) { setError('Action retry display limit reached. Refresh before answering another action.'); return; }
      answerKeys.current.set(signature, crypto.randomUUID());
    }
    const key = answerKeys.current.get(signature)!;
    void act(() => event.data.kind === 'signal'
      ? answerAgentSignal(sessionId, String(event.data.turn_id), id, answers[id] || 'null', key)
      : answerAgentActions(sessionId, String(event.data.turn_id), [{ request_id: id, allow,
        ...(allow === undefined ? { output: answers[id] || '' } : {}) }], key));
  }
  async function more(kind: string) {
    const reducer = viewReducer.current;
    const page = view?.resource_pages?.[kind];
    if (!reducer || !page?.has_more) return;
    await act(async () => {
      const next = await getAgentResourcePage(sessionId, kind, page);
      if (viewReducer.current !== reducer) return;
      reducer.mergeResourcePage(kind, next);
      const updated = reducer.snapshot();
      setView(updated); setActions(updated.required_actions);
    });
  }
  return <section style={{ padding: 24, maxWidth: 1100 }}>
    <div style={{ display: 'flex', gap: 16, alignItems: 'center' }}>
      <h2>Execution</h2><span role="status">{connection}</span>
      <button onClick={() => setRevision(v => v + 1)}>Refresh</button>
    </div>
    {error && <p role="alert" style={{ color: '#b91c1c' }}>{error}</p>}
    {!readOnly && <form onSubmit={e => { e.preventDefault(); void send(); }}>
      <textarea aria-label="New turn message" value={message} onChange={e => setMessage(e.target.value)} style={{ width: '100%', minHeight: 70 }} />
      <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
        <select aria-label="Input operation" value={mode} onChange={e => setMode(e.target.value as typeof mode)}>
          <option value="submit">New turn</option>{available('steer') && <option value="steer">Steer selected turn</option>}{available('inject') && <option value="inject">Inject context</option>}
        </select>
        {sessionCapabilities?.capabilities.file_input && <input ref={fileInput} type="file" aria-label="Attach a file" onChange={e => setFile(e.target.files?.[0])} />}
        <button disabled={busy || (!message.trim() && !file)} type="submit">Send</button>
      </div>
      <small>{mode === 'inject' ? 'Context is consumed at the next execution step; this does not start a turn.' : mode === 'steer' ? 'Updates the current logical task at its next execution step.' : 'Queues a new logical task.'}</small>
    </form>}
    <h3>Turns</h3>
    {turns.map(turn => <div key={turn.id} style={{ marginBottom: 8 }}>
      <button aria-pressed={selectedTurnId === turn.id} onClick={() => setSelectedTurnId(turn.id)}><code>{turn.id.slice(0, 12)}</code></button> · {turn.status} {(turn.error || turn.errorCode || turn.deliveryError) && `· ${turn.error?.message || turn.error?.code || turn.errorCode || turn.deliveryError}`} {' '}
      {!readOnly && selectedTurnId === turn.id && available('cancel') && <button disabled={busy} onClick={() => void act(() => cancel(turn.id))}>Cancel</button>}
    </div>)}
    {turnPage?.has_more && <button disabled={busy} onClick={() => void act(async () => {
      const next = await listAgentTurnPage(sessionId, turnPage.next_cursor ?? undefined);
      updateTurns(next.data); setTurnPage(next);
    })}>Load older turns</button>}
    {selectedTurnId && capabilities?.supported_commands.actions && <ToolExecutionRecovery key={selectedTurnId}
      sessionId={sessionId} turnId={selectedTurnId} capabilities={capabilities} readOnly={readOnly} />}
    {actions.length > 0 && <h3>Required actions</h3>}
    {actions.map(event => <div key={String(event.data.request_id)} style={{ border: '1px solid #cbd5e1', padding: 12, marginBottom: 8 }}>
      <pre style={{ whiteSpace: 'pre-wrap' }}>{JSON.stringify(event.data.tool_call, null, 2)}</pre>
      {!readOnly && (selectedTurnId !== event.data.turn_id ?
        <button onClick={() => setSelectedTurnId(String(event.data.turn_id))}>Select turn to respond</button> :
        !available('actions') ? <small>Action response is currently unavailable.</small> : event.data.kind === 'confirmation' ? <>
        <button disabled={busy} onClick={() => answer(event, true)}>Allow</button>{' '}
        <button disabled={busy} onClick={() => answer(event, false)}>Deny</button>
      </> : <>
        <textarea aria-label={event.data.kind === 'signal' ? 'Signal value as JSON' : 'Tool result'} placeholder={event.data.kind === 'signal' ? 'JSON value' : undefined} value={answers[String(event.data.request_id)] || ''} onChange={e => setAnswers(prev => ({ ...prev, [String(event.data.request_id)]: e.target.value }))} />
        <button disabled={busy} onClick={() => answer(event)}>Submit result</button>
      </>)}
    </div>)}
    <h3>Messages</h3>
    {selectedTurnId && <details open={!!finalOutput.length}>
      <summary>Final output</summary>
      <button onClick={() => setOutputRefresh(value => value + 1)}>Refresh result</button>
      {outputError && <p role="alert">{outputError}</p>}
      {!finalOutput.length && !outputError && <p>No final output has been committed.</p>}
      {finalOutput.map(event => <div key={event.id}>{event.content?.map((block, index) => <p key={index} style={{ whiteSpace: 'pre-wrap' }}>{String(block.text ?? '')}</p>)}</div>)}
    </details>}
    {view?.items.map(event => {
      const item = event.data.item as { role?: string; status?: string; content?: Array<Record<string, unknown>> };
      const content = item?.content?.filter(block => !['tool_use', 'tool_result'].includes(String(block.type))) ?? [];
      if (!content.length) return null;
      return <article key={String(event.data.item_id)} style={{ border: '1px solid #e2e8f0', padding: 12, margin: '8px 0' }}>
        <strong>{item.role}</strong> <small>{item.status}</small>
        {content.map((block, i) => <p key={i} style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{block.type === 'text' ? String(block.text ?? '') : `[${block.type}]`}</p>)}
      </article>;
    })}
    {view?.resource_pages?.items?.has_more && <small>Earlier message prefixes may be in unloaded pages. Load more items to restore them.</small>}
    {!!view?.steps?.length && <details><summary>Workflow and Team steps</summary>
      {view.steps.map(event => <p key={String(event.data.step_id)} title={String(event.data.step_id)}>
        {String(event.data.name ?? event.data.kind ?? 'Step')} · {String(event.data.status)}
      </p>)}
    </details>}
    {!!view?.tools?.length && <h3>Tools</h3>}
    {view?.tools?.map(event => <details key={`${event.data.turn_id}:${event.data.tool_call_id}`} open={['running', 'generating_arguments', 'suspended'].includes(String(event.data.status))}>
      <summary>{String(event.data.name ?? event.data.tool_call_id)} · {String(event.data.status)}</summary>
      <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify({ input: event.data.input, arguments: event.data.arguments, progress: event.data.progress, output: event.data.output }, null, 2)}</pre>
    </details>)}
    {!!view?.inputs?.length && <details><summary>Submitted context and steering</summary>
      {view.inputs.map(event => <p key={String(event.data.input_id)}>{String(event.data.kind ?? 'input')} · {String(event.data.status)}</p>)}
    </details>}
    {!!view?.subagents?.length && <details><summary>Sub-agent sessions</summary>
      {view.subagents.map(event => <button key={String(event.data.childSessionId)} onClick={() => void act(async () => setChildView(await getAgentSubagentSnapshot(sessionId, String(event.data.childSessionId))))}>{String(event.data.childSessionId)}</button>)}
      {childView && <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify(childView.items, null, 2)}</pre>}
    </details>}
    <SessionArtifacts key={`artifacts-${sessionId}`} sessionId={sessionId} artifacts={view?.artifacts ?? []}
      filesSupported={!!sessionCapabilities?.capabilities.files} readOnly={readOnly || view?.session.status !== 'active'} />
    {sessionCapabilities && (sessionCapabilities.capabilities.checkpoints || sessionCapabilities.capabilities.budget)
      && <SessionMaintenance key={`maintenance-${sessionId}`} sessionId={sessionId} capabilities={sessionCapabilities}
        readOnly={readOnly || view?.session.status !== 'active'} />}
    <div>
      {Object.entries(view?.resource_pages ?? {}).filter(([kind, page]) => !['model_usage', 'turns'].includes(kind) && page.has_more)
        .map(([kind]) => <button key={kind} disabled={busy} onClick={() => void more(kind)}>Load more {kind.replace(/_/g, ' ')}</button>)}
    </div>
    {view?.usage && <details><summary>Reported usage · {String(view.usage.model_calls ?? 0)} model calls</summary>
      <pre>{JSON.stringify(view.usage.totals, null, 2)}</pre>
      <small>Budgets use reported usage. Concurrent calls can exceed configured limits with no fixed bound on the excess.</small>
    </details>}
    <h3>Recent committed events</h3>
    {view?.as_of && <SessionEventExport key={`export-${sessionId}`} sessionId={sessionId} through={view.as_of} turnId={selectedTurnId} />}
    {events.map((event, index) => <details key={event.id || index} style={{ padding: '7px 0', borderBottom: '1px solid #e2e8f0' }}>
      <summary>{event.type} · {event.created_at ? new Date(event.created_at).toLocaleTimeString() : ''}</summary>
      <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify(event.data, null, 2)}</pre>
    </details>)}
  </section>;
}
