import { useMemo, useRef, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { authHeaders, readApiError } from '@/api/http';
import { createApplication, createApplicationCredential, listApplications, listApplicationCredentials, revokeApplicationCredential, type SessionTarget } from '@/api/applications';
import { ServiceClient } from '@/api/serviceSessions';
import { ServiceTurnPanel } from './ServiceTurnPanel';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

export function SessionUsageCard({ targetType, targetRef, targetName, revisionId }: { targetType: SessionTarget['type']; targetRef: string; targetName: string; revisionId?: string }) {
  const scope = useControlPlaneScope();
  const [searchParams, setSearchParams] = useSearchParams();
  const savedTargetMatches = searchParams.get('sessionTarget') === targetRef && (searchParams.get('sessionRevision') ?? '') === (revisionId ?? '');
  const [application, setApplication] = useState('');
  const [applicationName, setApplicationName] = useState('');
  const [apiKey, setApiKey] = useState('');
  const [credentialName, setCredentialName] = useState('');
  const [scopes, setScopes] = useState(['invoke', 'read', 'cancel', 'interact']);
  const [message, setMessage] = useState(targetType === 'workflow' ? '{}' : 'Describe the task you want this Agent to complete.');
  const [session, setSession] = useState(savedTargetMatches ? searchParams.get('serviceSession') ?? '' : '');
  const [turn, setTurn] = useState(savedTargetMatches ? searchParams.get('serviceTurn') ?? '' : '');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const pending = useRef<{ body: string; key: string }>();
  const sessionKey = useRef(crypto.randomUUID());
  const target = useMemo<SessionTarget>(() => ({ type: targetType, id: targetRef, ...(revisionId ? { revisionId } : {}) }), [targetType, targetRef, revisionId]);
  const headers = useMemo(() => () => ({ ...authHeaders(), ...(apiKey ? { 'X-API-Key': apiKey } : {}) }), [apiKey]);
  const client = useMemo(() => new ServiceClient(session, '', headers), [session, headers]);
  const humanClient = useMemo(() => new ServiceClient(session, '', authHeaders), [session]);
  const apps = useQuery({ queryKey: ['applications', scope.tenant, scope.namespace], queryFn: () => listApplications(scope.tenant, scope.namespace) });
  const credentials = useQuery({ queryKey: ['application-credentials', application], queryFn: () => listApplicationCredentials(application), enabled: !!application });
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
      <details><summary className="cursor-pointer text-sm font-semibold">Application credentials</summary><div className="mt-3 space-y-3">
        <select aria-label="Application" className="w-full rounded border p-2" value={application} onChange={e => setApplication(e.target.value)}><option value="">Select an application</option>{(apps.data?.items ?? []).filter(a => a.status === 'active').map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select>
        <div className="flex gap-2"><Input aria-label="New application name" placeholder="New application name" value={applicationName} onChange={e => setApplicationName(e.target.value)} /><Button disabled={busy || !applicationName.trim()} onClick={() => void perform(async () => { const result = await createApplication({ tenant: scope.tenant, namespace: scope.namespace, name: applicationName.trim() }); setApplication(result.application.id); setApplicationName(''); await apps.refetch(); })}>Create application</Button></div>
        <div className="flex flex-wrap gap-3">{['invoke', 'read', 'cancel', 'interact', 'webhooks:write'].map(value => <label key={value} className="flex gap-1 text-sm"><input type="checkbox" checked={scopes.includes(value)} onChange={e => setScopes(current => e.target.checked ? [...current, value] : current.filter(s => s !== value))} />{value}</label>)}</div>
        <div className="flex gap-2"><Input aria-label="Credential name" placeholder="Credential name" value={credentialName} onChange={e => setCredentialName(e.target.value)} /><Button disabled={busy || !application || !credentialName.trim()} onClick={() => void perform(async () => { const result = await createApplicationCredential(application, { name: credentialName, scopes, targets: [{ type: targetType, id: targetRef }] }); setApiKey(result.apiKey); await credentials.refetch(); })}>Issue key</Button></div>
        <p className="text-sm text-muted-foreground">This key grants access to this {targetType}. Copy a newly issued key before leaving this page. To rotate it, issue a replacement, update your application, then revoke the previous key.</p>
        {(credentials.data?.items ?? []).map(key => <div key={key.id} className="flex items-center gap-3 text-sm"><span>{key.name} · {key.status}</span><Button size="sm" variant="outline" disabled={busy || key.status !== 'active'} onClick={() => void perform(async () => { await revokeApplicationCredential(application, key.id); await credentials.refetch(); })}>Revoke</Button></div>)}
      </div></details>
      <label className="block space-y-1 text-sm"><span>Application API key (leave empty to use your signed-in identity)</span><Input type="password" autoComplete="off" value={apiKey} disabled={!!session} onChange={e => setApiKey(e.target.value)} /></label>
      {!!apiKey && !session && <Button size="sm" variant="outline" onClick={() => void navigator.clipboard.writeText(apiKey)}>Copy API key</Button>}
      <details><summary className="cursor-pointer text-sm font-semibold">API example</summary><pre className="mt-2 overflow-auto rounded border p-3 text-xs">{sample}</pre></details>
      <label className="block space-y-1 text-sm"><span>{targetType === 'workflow' ? 'Workflow input JSON' : 'Task message'}</span><textarea className="min-h-24 w-full rounded border p-3" value={message} onChange={e => setMessage(e.target.value)} /></label>
      <div className="flex items-center gap-3"><Button disabled={busy || !message.trim()} onClick={() => void run()}>{session ? 'Submit next Turn' : 'Create Session and submit'}</Button>{session && <Button variant="outline" disabled={busy} onClick={() => { setSession(''); setTurn(''); rememberSession(''); sessionKey.current = crypto.randomUUID(); pending.current = undefined; }}>New Session</Button>}</div>
      {session && <p className="text-xs text-muted-foreground">Session: <code>{session}</code></p>}
      {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
      {turn && <ServiceTurnPanel client={client} humanClient={humanClient} turnId={turn} />}
    </CardContent></Card>;
}
