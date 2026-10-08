import { useEffect, useRef, useState } from 'react';
import {
  AgentSessionEvent, AgentSessionSnapshot, AgentSubagentSnapshot, AgentTurn, getAgentSessionSnapshot, streamAgentSession, listAgentTurns,
  submitAgentTurn, cancelAgentTurn, resumeAgentTurn, answerAgentActions,
  steerAgentTurn, injectAgentContext, uploadAgentFile, getAgentSubagentSnapshot,
} from '../api/agentSessions';
import { AgentSessionView } from '../api/agentSessionView';

/** Restores committed message/tool prefixes and follows the shared session event cursor. */
export default function SessionExecution({ sessionId, readOnly = false }: { sessionId: string; readOnly?: boolean }) {
  const [view, setView] = useState<AgentSessionSnapshot>();
  const [mode, setMode] = useState<'submit' | 'steer' | 'inject'>('submit');
  const fileInput = useRef<HTMLInputElement>(null);
  const [file, setFile] = useState<File>();
  const [childView, setChildView] = useState<AgentSubagentSnapshot>();
  const [events, setEvents] = useState<AgentSessionEvent[]>([]);
  const [actions, setActions] = useState<AgentSessionEvent[]>([]);
  const [turns, setTurns] = useState<AgentTurn[]>([]);
  const [connection, setConnection] = useState('loading');
  const [error, setError] = useState('');
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [revision, setRevision] = useState(0);
  const [answers, setAnswers] = useState<Record<string, string>>({});
  // Retain the key on an ambiguous submit so a retry cannot enqueue a second turn.
  const [submission, setSubmission] = useState<{ message: string; mode: string; file?: File; fileId?: string; turn?: string; key: string }>();
  useEffect(() => {
    const controller = new AbortController();
    setError(''); setConnection('loading'); setEvents([]); setActions([]); setView(undefined); setChildView(undefined);
    (async () => {
      const snapshot = await getAgentSessionSnapshot(sessionId, controller.signal);
      if (controller.signal.aborted) return;
      const seen = new Set<string>();
      const reducer = new AgentSessionView(snapshot);
      setView(snapshot); setActions(snapshot.required_actions);
      setTurns(await listAgentTurns(sessionId));
      await streamAgentSession(sessionId, { after: snapshot.as_of, signal: controller.signal, onConnection: setConnection,
        onEvent: async event => {
          if (event.id && seen.has(event.id)) return;
          if (event.id) { seen.add(event.id); if (seen.size > 1024) seen.delete(seen.values().next().value!); }
          if (event.type === 'required_action.rejected')
            for (const signature of answerKeys.current.keys()) if (JSON.parse(signature)[0] === event.data.request_id) answerKeys.current.delete(signature);
          reducer.apply(event);
          const updated = reducer.snapshot();
          setView(updated); setActions(updated.required_actions);
          // The diagnostic tail is bounded; conversation state is reconciled by resource ID.
          setEvents(previous => [...previous, event].slice(-100));
          if (event.type.startsWith('turn.')) setTurns(await listAgentTurns(sessionId));
        },
      });
    })().catch(e => { if (!controller.signal.aborted) { setError(e.message); setConnection('disconnected'); } });
    return () => controller.abort();
  }, [sessionId, revision]);
  async function act(operation: () => Promise<unknown>) {
    setBusy(true); setError('');
    try { await operation(); setTurns(await listAgentTurns(sessionId)); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setBusy(false); }
  }
  async function send() {
    const turn = mode === 'steer' ? turns.find(item => item.status === 'running')?.id : undefined;
    if (mode === 'steer' && !turn) { setError('No running turn accepts steering.'); return; }
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
      else if (mode === 'inject') await injectAgentContext(sessionId, input, current.key);
      else await submitAgentTurn(sessionId, input, current.key);
      setMessage(''); setFile(undefined); setSubmission(undefined);
      if (fileInput.current) fileInput.current.value = '';
    });
  }
  const answerKeys = useRef(new Map<string, string>());
  useEffect(() => {
    setSubmission(undefined); setFile(undefined); setMessage(''); setAnswers({}); answerKeys.current.clear();
    if (fileInput.current) fileInput.current.value = '';
  }, [sessionId]);
  function answer(event: AgentSessionEvent, allow?: boolean) {
    const id = String(event.data.request_id);
    const signature = JSON.stringify([id, allow, answers[id]]);
    if (!answerKeys.current.has(signature)) answerKeys.current.set(signature, crypto.randomUUID());
    void act(() => answerAgentActions(sessionId, String(event.data.turn_id), [{ request_id: id, allow,
      ...(allow === undefined ? { output: answers[id] || '' } : {}) }], answerKeys.current.get(signature)!));
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
          <option value="submit">New turn</option><option value="steer">Steer running turn</option><option value="inject">Inject context</option>
        </select>
        <input ref={fileInput} type="file" aria-label="Attach a file" onChange={e => setFile(e.target.files?.[0])} />
        <button disabled={busy || (!message.trim() && !file)} type="submit">Send</button>
      </div>
      <small>{mode === 'inject' ? 'Context is consumed at the next execution step; this does not start a turn.' : mode === 'steer' ? 'Updates the current logical task at its next execution step.' : 'Queues a new logical task.'}</small>
    </form>}
    <h3>Turns</h3>
    {turns.map(turn => <div key={turn.id} style={{ marginBottom: 8 }}>
      <code>{turn.id.slice(0, 12)}</code> · {turn.status} {(turn.error || turn.errorCode) && `· ${turn.error?.message || turn.error?.code || turn.errorCode}`} {' '}
      {!readOnly && ['queued', 'running'].includes(turn.status) && <button disabled={busy} onClick={() => void act(() => cancelAgentTurn(sessionId, turn.id))}>Cancel</button>}
      {!readOnly && (['failed', 'interrupted'].includes(turn.status) || (turn.status === 'requires_action' && !actions.some(a => a.data.turn_id === turn.id))) && <button disabled={busy} onClick={() => void act(() => resumeAgentTurn(sessionId, turn.id))}>Resume</button>}
    </div>)}
    {actions.length > 0 && <h3>Required actions</h3>}
    {actions.map(event => <div key={String(event.data.request_id)} style={{ border: '1px solid #cbd5e1', padding: 12, marginBottom: 8 }}>
      <pre style={{ whiteSpace: 'pre-wrap' }}>{JSON.stringify(event.data.tool_call, null, 2)}</pre>
      {!readOnly && (event.data.kind === 'confirmation' ? <>
        <button disabled={busy} onClick={() => answer(event, true)}>Allow</button>{' '}
        <button disabled={busy} onClick={() => answer(event, false)}>Deny</button>
      </> : <>
        <textarea aria-label="Tool result" value={answers[String(event.data.request_id)] || ''} onChange={e => setAnswers(prev => ({ ...prev, [String(event.data.request_id)]: e.target.value }))} />
        <button disabled={busy} onClick={() => answer(event)}>Submit result</button>
      </>)}
    </div>)}
    <h3>Messages</h3>
    {view?.items.map(event => {
      const item = event.data.item as { role?: string; status?: string; content?: Array<Record<string, unknown>> };
      const content = item?.content?.filter(block => !['tool_use', 'tool_result'].includes(String(block.type))) ?? [];
      if (!content.length) return null;
      return <article key={String(event.data.item_id)} style={{ border: '1px solid #e2e8f0', padding: 12, margin: '8px 0' }}>
        <strong>{item.role}</strong> <small>{item.status}</small>
        {content.map((block, i) => <p key={i} style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{block.type === 'text' ? String(block.text ?? '') : `[${block.type}]`}</p>)}
      </article>;
    })}
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
    <h3>Recent committed events</h3>
    {events.map((event, index) => <details key={event.id || index} style={{ padding: '7px 0', borderBottom: '1px solid #e2e8f0' }}>
      <summary>{event.type} · {event.created_at ? new Date(event.created_at).toLocaleTimeString() : ''}</summary>
      <pre style={{ whiteSpace: 'pre-wrap', overflowWrap: 'anywhere' }}>{JSON.stringify(event.data, null, 2)}</pre>
    </details>)}
  </section>;
}
