import { useMemo, useRef, useState } from 'react';
import { Link, useSearchParams } from 'react-router-dom';
import { authHeaders, readApiError } from '@/api/http';
import type { SessionTarget } from '@/api/applications';
import { SessionApplicationCredentials } from './SessionApplicationCredentials';
import { ServiceClient } from '@/api/serviceSessions';
import { ServiceTurnPanel } from './ServiceTurnPanel';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';

type SessionUsageCardProps = { targetType: SessionTarget['type']; targetRef: string; targetName: string; revisionId?: string; runtimeKind?: string };

export function SessionUsageCard(props: SessionUsageCardProps) {
  return props.targetType === 'agent' && props.runtimeKind === 'managed'
    ? <ManagedSessionUsageCard {...props} /> : <RuntimeSessionUsageCard {...props} />;
}

function ManagedSessionUsageCard({ targetRef, targetName }: SessionUsageCardProps) {
  const [apiKey, setApiKey] = useState('');
  const sample = [
    'curl "$BASE_URL/v1/sessions"',
    '  -H "X-API-Key: $AGENTSCOPE_API_KEY"',
    '  -H "Content-Type: application/json"',
    '  -H "AgentScope-Extensions: turn-inputs-v1"',
    '  -H "Idempotency-Key: create-session-001"',
    `  -d '${JSON.stringify({ agent: targetRef, environment_id: 'environment-id' })}'`,
  ].join(' \\\n') + '\n\n' + [
    'curl "$BASE_URL/v1/sessions/$SESSION_ID/events"',
    '  -H "X-API-Key: $AGENTSCOPE_API_KEY"',
    '  -H "Content-Type: application/json"',
    '  -H "AgentScope-Extensions: turn-inputs-v1"',
    '  -H "Idempotency-Key: task-001"',
    `  -d '${JSON.stringify({ events: [{ type: 'user.message', content: [{ type: 'text', text: 'Complete this task.' }], agentscope_service: { intent: 'new_turn' } }] })}'`,
  ].join(' \\\n') + '\n\n' + [
    'curl "$BASE_URL/v1/sessions/$SESSION_ID/turns/$TURN_ID/output"',
    '  -H "X-API-Key: $AGENTSCOPE_API_KEY"',
  ].join(' \\\n');
  return <Card><CardHeader><CardTitle>Use {targetName} through the Session API</CardTitle>
    <CardDescription>Create a Session, send text events, and read each Turn result independently of the live stream.</CardDescription></CardHeader>
    <CardContent className="space-y-4">
      <SessionApplicationCredentials targetType="agent" targetRef={targetRef} apiKey={apiKey} onApiKey={setApiKey} apiOnly />
      <Link to={`/managed/sessions/new?agentId=${encodeURIComponent(targetRef)}`}>Create Session in Console</Link>
      <p className="text-sm text-muted-foreground">Use an application credential with invoke, read and cancel access to this Agent. Console creation uses your signed-in identity. Replace environment-id with an accessible Environment ID; copy SESSION_ID and TURN_ID from the returned Session and event receipt.</p>
      <details><summary className="cursor-pointer text-sm font-semibold">API example</summary><pre className="mt-2 overflow-auto rounded border p-3 text-xs">{sample}</pre></details>
    </CardContent></Card>;
}

function RuntimeSessionUsageCard({ targetType, targetRef, targetName, revisionId }: SessionUsageCardProps) {
  const [searchParams, setSearchParams] = useSearchParams();
  const savedTargetMatches = searchParams.get('sessionTarget') === targetRef && (searchParams.get('sessionRevision') ?? '') === (revisionId ?? '');
  const [apiKey, setApiKey] = useState('');
  const [message, setMessage] = useState(targetType === 'workflow' ? '{}' : 'Describe the task you want this Agent to complete.');
  const [session, setSession] = useState(savedTargetMatches ? searchParams.get('serviceSession') ?? '' : '');
  const [turn, setTurn] = useState(savedTargetMatches ? searchParams.get('serviceTurn') ?? '' : '');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const pending = useRef<{ body: string; key: string }>();
  const sessionKey = useRef(crypto.randomUUID());
  const target = useMemo<SessionTarget>(() => ({ type: targetType, id: targetRef, ...(revisionId ? { revisionId } : {}) }), [targetType, targetRef, revisionId]);
  const headers = useMemo(() => () => {
    const value = authHeaders();
    if (apiKey) { delete value.Authorization; value['X-API-Key'] = apiKey; }
    return value;
  }, [apiKey]);
  const client = useMemo(() => new ServiceClient(session, '', headers), [session, headers]);
  const humanClient = useMemo(() => new ServiceClient(session, '', authHeaders), [session]);
  async function perform(work: () => Promise<void>) { setBusy(true); setError(''); try { await work(); } catch (e) { setError(e instanceof Error ? e.message : String(e)); } finally { setBusy(false); } }
  function rememberSession(id: string, turnId = '') {
    const next = new URLSearchParams(searchParams);
    for (const key of ['serviceSession', 'serviceTurn', 'sessionTarget', 'sessionRevision']) next.delete(key);
    if (id) { next.set('serviceSession', id); next.set('sessionTarget', targetRef); if (turnId) next.set('serviceTurn', turnId); if (revisionId) next.set('sessionRevision', revisionId); }
    setSearchParams(next, { replace: true });
  }
  async function run() {
    await perform(async () => {
      const input = targetType === 'workflow' ? { input: JSON.parse(message) as unknown } : { message };
      let id = session;
      if (!id) {
        const response = await fetch('/api/v1/agent-sessions', { method: 'POST', headers: { ...headers(), 'Idempotency-Key': sessionKey.current }, body: JSON.stringify({ target }) });
        if (!response.ok) throw await readApiError(response, 'Session creation failed');
        id = (await response.json() as { id: string }).id; setSession(id); rememberSession(id);
      }
      const body = JSON.stringify(input);
      if (pending.current?.body !== body) pending.current = { body, key: crypto.randomUUID() };
      const result = await new ServiceClient(id, '', headers).submit(input, pending.current.key);
      setTurn(result.id); rememberSession(id, result.id); pending.current = undefined;
    });
  }
  const sample = `curl "$BASE_URL/api/v1/agent-sessions" \\\n  -H "X-API-Key: $AGENTSCOPE_API_KEY" \\\n  -H "Content-Type: application/json" \\\n  -H "Idempotency-Key: create-session-001" \\\n  -d '${JSON.stringify({ target })}'\n\ncurl "$BASE_URL/api/v1/agent-sessions/$SESSION_ID/turns" \\\n  -H "X-API-Key: $AGENTSCOPE_API_KEY" \\\n  -H "Content-Type: application/json" \\\n  -H "Idempotency-Key: task-001" \\\n  -d '${JSON.stringify(targetType === 'workflow' ? { input: {} } : { message: 'Complete this task.' })}'`;
  return <Card><CardHeader><CardTitle>Use {targetName} through the Session API</CardTitle><CardDescription>Create a Session that selects this {targetType}, then submit each task as a Turn. The same Session keeps its selected configuration and execution history. Application credentials control which resources an application may use.</CardDescription></CardHeader>
    <CardContent className="space-y-5">
      <SessionApplicationCredentials targetType={targetType} targetRef={targetRef} apiKey={apiKey} onApiKey={setApiKey} disabled={!!session} />
      <details><summary className="cursor-pointer text-sm font-semibold">API example</summary><pre className="mt-2 overflow-auto rounded border p-3 text-xs">{sample}</pre></details>
      <label className="block space-y-1 text-sm"><span>{targetType === 'workflow' ? 'Workflow input JSON' : 'Task message'}</span><textarea className="min-h-24 w-full rounded border p-3" value={message} onChange={e => setMessage(e.target.value)} /></label>
      <div className="flex items-center gap-3"><Button disabled={busy || !message.trim()} onClick={() => void run()}>{session ? 'Submit next Turn' : 'Create Session and submit'}</Button>{session && <Button variant="outline" disabled={busy} onClick={() => { setSession(''); setTurn(''); rememberSession(''); sessionKey.current = crypto.randomUUID(); pending.current = undefined; }}>New Session</Button>}</div>
      {session && <p className="text-xs text-muted-foreground">Session: <code>{session}</code></p>}
      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
      {turn && <ServiceTurnPanel client={client} humanClient={humanClient} turnId={turn} />}
    </CardContent></Card>;
}
