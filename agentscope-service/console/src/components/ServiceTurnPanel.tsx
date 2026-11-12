import { useEffect, useRef, useState } from 'react';
import { ServiceAPIError, ServiceProtocolError, ServiceClient, ServiceView, ServiceViewLimitError, terminalTurn, type ServiceData, type ServiceEvent, type ServiceSnapshot } from '@/api/serviceSessions';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';

const record = (value: unknown): ServiceData => value && typeof value === 'object' ? value as ServiceData : {};
const show = (value: unknown) => typeof value === 'string' ? value : JSON.stringify(value, null, 2);
const externalArtifact = (artifact: ServiceData) => {
  if (artifact.file_id || artifact.download_url || !artifact.uri) return undefined;
  try { const url = new URL(String(artifact.uri)); return url.protocol === 'https:' && !url.username && !url.password ? url.href : undefined; }
  catch { return undefined; }
};

/** The same public Turn resources are used for Agent, Team and Workflow calls. */
export function ServiceTurnPanel({ client, humanClient, turnId }: { client: ServiceClient; humanClient: ServiceClient; turnId: string }) {
  const [snapshot, setSnapshot] = useState<ServiceSnapshot>();
  const [events, setEvents] = useState<ServiceEvent[]>([]);
  const [connection, setConnection] = useState('Loading snapshot');
  const [error, setError] = useState('');
  const [answer, setAnswer] = useState('{}');
  const [input, setInput] = useState('');
  const [command, setCommand] = useState<ServiceData>();
  const [busy, setBusy] = useState(false);
  const [observation, setObservation] = useState(0);
  const [asHuman, setAsHuman] = useState(false);
  const [availableCommands, setAvailableCommands] = useState<string[]>([]);
  const pending = useRef<{ identity: string; key: string }>();
  const viewReducer = useRef<ServiceView>();
  const commandClient = asHuman ? humanClient : client;
  useEffect(() => {
    const abort = new AbortController();
    viewReducer.current = undefined;
    setSnapshot(undefined); setEvents([]); setError(''); setCommand(undefined);
    async function observe() {
      let view: ServiceView | undefined;
      while (!abort.signal.aborted) {
        try {
          if (!view) {
            const initial = await client.snapshot(turnId, abort.signal);
            if (abort.signal.aborted) return;
            view = new ServiceView(initial); viewReducer.current = view; setSnapshot(initial);
          }
          if (terminalTurn(view.snapshot().turn.status)) { setConnection(String(view.snapshot().turn.status)); return; }
          setConnection('Live');
          for await (const event of client.stream(turnId, view.snapshot().as_of, abort.signal)) {
            if (abort.signal.aborted) return;
            setError(''); view.apply(event); setSnapshot(view.snapshot());
            setEvents(previous => [...previous, event].slice(-100));
            if (terminalTurn(view.snapshot().turn.status)) { setConnection(String(view.snapshot().turn.status)); return; }
          }
        } catch (cause) {
          if (abort.signal.aborted) return;
          if (cause instanceof ServiceViewLimitError) { setConnection('Display limit reached'); setError(cause.message); return; }
          if (cause instanceof ServiceProtocolError) { setConnection('Unsupported Service response'); setError(cause.message); return; }
          if (cause instanceof ServiceAPIError && cause.status === 410) { view = undefined; setConnection('Restoring snapshot'); continue; }
          setConnection('Reconnecting'); setError(cause instanceof Error ? cause.message : String(cause));
        }
        await new Promise<void>(resolve => {
          const finish = () => { clearTimeout(timer); abort.signal.removeEventListener('abort', finish); resolve(); };
          const timer = setTimeout(finish, 1500);
          abort.signal.addEventListener('abort', finish, { once: true });
        });
      }
    }
    void observe();
    return () => abort.abort();
  }, [client, turnId, observation]);

  async function send(kind: 'actions' | 'inputs' | 'cancel' | 'resume', payload: ServiceData) {
    const identity = JSON.stringify([turnId, kind, payload, asHuman]);
    if (pending.current?.identity !== identity) pending.current = { identity, key: crypto.randomUUID() };
    setBusy(true); setError('');
    try {
      const result = await commandClient.command(turnId, kind, payload, pending.current.key);
      setCommand(result.command); pending.current = undefined;
      if (kind === 'resume') setObservation(value => value + 1);
    } catch (cause) { setError(cause instanceof Error ? cause.message : String(cause)); }
    finally { setBusy(false); }
  }
  async function respond(action: ServiceData, decision?: string) {
    try {
      const payload = JSON.parse(answer) as unknown;
      await send('actions', { request_id: action.request_id, ...(decision ? { decision } : {}),
        payload: action.kind === 'signal' ? { value: payload } : payload });
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Enter valid response JSON'); }
  }
  const status = String(snapshot?.turn.status ?? 'loading');
  const actionIdentity = Object.keys(snapshot?.required_actions ?? {}).join(',');
  useEffect(() => { let active = true; void client.turnCapabilities(turnId).then(value => { if (active) setAvailableCommands(value.available_commands); }).catch(cause => { if (active) { setAvailableCommands([]); setError(String(cause)); } }); return () => { active = false; }; }, [client, turnId, status, actionIdentity]);
  const terminal = terminalTurn(status);
  async function loadMore(kind: string) {
    const reducer = viewReducer.current, page = reducer?.snapshot().resource_pages?.[kind];
    if (!reducer || !page?.has_more) return;
    setBusy(true); setError('');
    try {
      const next = await client.resources(turnId, kind, page);
      if (viewReducer.current !== reducer) return;
      reducer.mergeResourcePage(kind, next); setSnapshot(reducer.snapshot());
    }
    catch (cause) { setError(cause instanceof Error ? cause.message : String(cause)); }
    finally { setBusy(false); }
  }
  return <section className="grid gap-4" aria-label="Turn execution">
    <div className="flex flex-wrap items-center gap-2"><code className="text-xs">{turnId}</code><Badge>{status}</Badge><span className="text-xs text-muted-foreground">{connection}</span>
      <Button size="sm" variant="outline" disabled={busy} onClick={() => setObservation(value => value + 1)}>Refresh snapshot</Button>
      {(!terminal || availableCommands.includes('resume')) && <><Button size="sm" variant="outline" disabled={busy || !availableCommands.includes('cancel')} onClick={() => void send('cancel', {})}>Cancel execution</Button><Button size="sm" variant="outline" disabled={busy || !availableCommands.includes('resume')} onClick={() => void send('resume', {})}>Resume</Button></>}
    </div>
    {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
    <div className="max-h-[30rem] overflow-auto space-y-3 rounded-lg border p-3">
      {Object.entries(snapshot?.items ?? {}).map(([id, value]) => { const item = record(value.item); return <article key={id} className="rounded border bg-background p-3"><div className="mb-1 text-xs text-muted-foreground">{String(item.role ?? 'assistant')} · {String(item.status ?? '')}</div>{(Array.isArray(item.content) ? item.content : []).map((block: unknown, index: number) => { const b = record(block); return <pre key={index} className="whitespace-pre-wrap break-words text-sm">{b.type === 'text' ? String(b.text ?? '') : show(b)}</pre>; })}</article>; })}
      {!Object.keys(snapshot?.items ?? {}).length && <p className="text-sm text-muted-foreground">{terminal ? 'No message output was recorded.' : 'Waiting for output.'}</p>}
    </div>
    {!!Object.keys(snapshot?.tools ?? {}).length && <details open><summary className="text-sm font-semibold">Tool activity</summary><div className="mt-2 grid gap-2">{Object.entries(snapshot?.tools ?? {}).map(([id, tool]) => <details key={id} className="rounded border p-2"><summary className="text-sm">{String(tool.name ?? tool.tool_call_id)} · {String(tool.status ?? '')}</summary><pre className="overflow-auto whitespace-pre-wrap text-xs">{show(tool)}</pre></details>)}</div></details>}
    {!!Object.keys(snapshot?.required_actions ?? {}).length && <section className="rounded border border-amber-300 p-3 space-y-3"><h4 className="text-sm font-semibold">Required actions</h4><label className="flex gap-2 text-xs"><input type="checkbox" checked={asHuman} onChange={event => setAsHuman(event.target.checked)} />Use my signed-in identity for a designated human approval</label><textarea aria-label="Action response JSON" className="w-full rounded border p-2 font-mono text-xs" value={answer} onChange={event => setAnswer(event.target.value)} />{Object.entries(snapshot?.required_actions ?? {}).map(([id, action]) => <div key={id} className="rounded border p-3 space-y-2"><pre className="whitespace-pre-wrap text-xs">{show(action)}</pre><div className="flex gap-2">{['approval', 'confirmation'].includes(String(action.kind)) ? <><Button disabled={busy} size="sm" onClick={() => void respond(action, 'approved')}>Approve</Button><Button disabled={busy} size="sm" variant="outline" onClick={() => void respond(action, 'rejected')}>Reject</Button></> : <Button disabled={busy} size="sm" onClick={() => void respond(action)}>Submit response</Button>}</div></div>)}</section>}
    {!terminal && availableCommands.includes('inputs') && <form className="flex gap-2" onSubmit={event => { event.preventDefault(); void send('inputs', { message: input }); }}><input aria-label="Additional input" className="min-w-0 flex-1 rounded border px-3 text-sm" placeholder="Additional input for this turn" value={input} onChange={event => setInput(event.target.value)} /><Button disabled={busy || !input.trim()} size="sm">Send input</Button></form>}
    {command && <div className="rounded border p-3 text-xs"><p>Request status</p><pre className="whitespace-pre-wrap">{show(command)}</pre><Button size="sm" variant="ghost" onClick={() => void commandClient.commandStatus(turnId, String(command.id)).then(result => setCommand(result.command)).catch(cause => setError(String(cause)))}>Refresh status</Button></div>}
    {snapshot?.turn.result !== undefined && <section><h4 className="text-sm font-semibold">Result</h4><pre className="overflow-auto whitespace-pre-wrap rounded border p-3 text-xs">{show(snapshot.turn.result)}</pre></section>}
    {!!Object.keys(snapshot?.artifacts ?? {}).length && <section><h4 className="text-sm font-semibold">Artifacts</h4>{Object.entries(snapshot?.artifacts ?? {}).map(([id, artifact]) => <div key={id} className="my-2 flex items-center gap-2 text-sm"><span>{String(artifact.name ?? id)}</span>{externalArtifact(artifact) ? <a href={externalArtifact(artifact)} target="_blank" rel="noreferrer" className="underline">Open external artifact</a> : <Button size="sm" variant="outline" onClick={() => void client.artifact(turnId, id).then(blob => { const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = String(artifact.name ?? id); link.click(); setTimeout(() => URL.revokeObjectURL(url), 1000); }).catch(cause => setError(String(cause)))}>Download</Button>}</div>)}</section>}
    <div className="flex flex-wrap gap-2">{Object.entries(snapshot?.resource_pages ?? {}).filter(([kind, page]) => page.has_more && ['items', 'tools', 'required_actions', 'steps', 'artifacts', 'model_usage'].includes(kind)).map(([kind]) => <Button key={kind} size="sm" variant="outline" disabled={busy} onClick={() => void loadMore(kind)}>Load more {kind.replace(/_/g, ' ')}</Button>)}</div>
    <details><summary className="text-sm">Steps and usage</summary><pre className="overflow-auto whitespace-pre-wrap text-xs">{show({ steps: snapshot?.steps, usage: snapshot?.usage_summary, model_calls: snapshot?.usage })}</pre></details>
    <details><summary className="text-sm">Recent events ({events.length}) · cursor {snapshot?.as_of}</summary><pre className="max-h-64 overflow-auto whitespace-pre-wrap text-xs">{events.map(event => JSON.stringify(event)).join('\n')}</pre></details>
  </section>;
}
