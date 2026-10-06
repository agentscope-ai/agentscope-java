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

import { useEffect, useMemo, useRef, useState } from 'react';
import { listEndpointCredentials, revealEndpointCredential, type Endpoint } from '@/api/agentEndpoints';
import { getToken } from '@/lib/apiClient';
import { ServiceClient } from '@/api/serviceInvocations';
import { ServiceInvocationPanel } from '@/components/ServiceInvocationPanel';
import { selectEndpointTestCredential } from '@/components/endpointTestCredential';
import { schemaExample } from '@/components/endpointExamples';
import { Button } from '@/components/ui/button';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { Input } from '@/components/ui/input';

export interface InvocationPlaygroundProps { endpoint: Endpoint; onInvoked?: () => void; initialInvocationId?: string; onInvocationChange?: (id: string) => void }

export function InvocationPlayground({ endpoint, onInvoked, initialInvocationId = '', onInvocationChange }: InvocationPlaygroundProps) {
  const [credential, setCredential] = useState('');
  const [message, setMessage] = useState(endpoint.invocationMode === 'job' ? JSON.stringify(schemaExample(endpoint.inputSchema), null, 2) : '');
  const [invocationId, setInvocationId] = useState(initialInvocationId);
  const [restoreId, setRestoreId] = useState(initialInvocationId);
  const [conversationId, setConversationId] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const pending = useRef<{ input: string; key: string }>();
  const client = useMemo(() => new ServiceClient('', (): HeadersInit => endpoint.authPolicy?.type === 'platform'
    ? { Authorization: `Bearer ${getToken() ?? ''}` } : { 'X-API-Key': credential }), [endpoint.authPolicy?.type, credential]);
  const humanClient = useMemo(() => new ServiceClient('', () => ({ Authorization: `Bearer ${getToken() ?? ''}` })), []);
  useEffect(() => { setInvocationId(initialInvocationId); setRestoreId(initialInvocationId); }, [initialInvocationId]);
  useEffect(() => {
    if (endpoint.authPolicy?.type === 'platform') return;
    let active = true;
    void listEndpointCredentials(endpoint.id).then(({ items }) => {
      const key = selectEndpointTestCredential(items);
      return key ? revealEndpointCredential(endpoint.id, key.id) : undefined;
    }).then(value => { if (active && value) setCredential(value.secret); })
      .catch(() => { /* The caller can provide an application credential below. */ });
    return () => { active = false; };
  }, [endpoint.id, endpoint.authPolicy?.type]);
  async function submit() {
    const fingerprint = JSON.stringify([message, conversationId]);
    if (pending.current?.input !== fingerprint) pending.current = { input: fingerprint, key: crypto.randomUUID() };
    setBusy(true); setError('');
    try {
      const receipt = endpoint.invocationMode === 'job'
        ? await client.submit(endpoint.slug, JSON.parse(message), pending.current.key, endpoint.name)
        : await client.converse(endpoint.slug, message, pending.current.key, conversationId);
      setInvocationId(receipt.invocationId); setRestoreId(receipt.invocationId);
      onInvocationChange?.(receipt.invocationId);
      if (receipt.conversationId) setConversationId(receipt.conversationId);
      pending.current = undefined; onInvoked?.();
    } catch (cause) { setError(cause instanceof Error ? cause.message : 'Submission failed'); }
    finally { setBusy(false); }
  }
  const ready = endpoint.authPolicy?.type === 'platform' || !!credential;
  return <Card><CardHeader><CardTitle>Test published API</CardTitle><CardDescription>Submit an Invocation, restore its snapshot, and follow its committed SSE events.</CardDescription></CardHeader><CardContent className="grid gap-4">
    {endpoint.authPolicy?.type !== 'platform' && <label className="grid gap-1 text-sm">Application API credential<Input type="password" value={credential} onChange={event => setCredential(event.target.value)} /><span className="text-xs text-muted-foreground">A key from the same Application can reopen its invocations. Command operations require their corresponding scopes.</span></label>}
    {endpoint.authPolicy?.type === 'platform' && <p className="text-xs text-muted-foreground">Uses your signed-in platform credential.</p>}
    {conversationId && <div className="flex gap-2 items-center text-xs"><span>Conversation {conversationId}</span><Button size="sm" variant="ghost" onClick={() => setConversationId('')}>Start new conversation</Button></div>}
    <label className="grid gap-1 text-sm">{endpoint.invocationMode === 'job' ? 'Input JSON' : 'Message'}<textarea className="min-h-28 rounded border p-3 font-mono text-sm" value={message} onChange={event => setMessage(event.target.value)} /></label>
    <div><Button disabled={busy || !ready || endpoint.status !== 'published' || !message.trim()} onClick={() => void submit()}>{busy ? 'Submitting…' : 'Submit'}</Button></div>
    <form className="flex gap-2" onSubmit={event => { event.preventDefault(); setInvocationId(restoreId.trim()); onInvocationChange?.(restoreId.trim()); }}><Input aria-label="Invocation ID to restore" placeholder="Restore an existing Invocation ID" value={restoreId} onChange={event => setRestoreId(event.target.value)} /><Button variant="outline" disabled={!ready || !restoreId.trim()}>Observe</Button></form>
    {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
    {invocationId && ready && <ServiceInvocationPanel client={client} humanClient={humanClient} invocationId={invocationId} />}
  </CardContent></Card>;
}
