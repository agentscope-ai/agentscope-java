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
import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { createApplication, createApplicationCredential, listApplications, listApplicationCredentials, revokeApplicationCredential, type SessionTarget } from '@/api/applications';
import { Button } from '@/components/ui/button';
import { Input } from '@/components/ui/input';

export function SessionApplicationCredentials({ targetType, targetRef, apiKey, onApiKey, disabled = false, apiOnly = false }: { targetType: SessionTarget['type']; targetRef: string; apiKey: string; onApiKey: (key: string) => void; disabled?: boolean; apiOnly?: boolean }) {
  const scope = useControlPlaneScope();
  const [application, setApplication] = useState('');
  const [applicationName, setApplicationName] = useState('');
  const [credentialName, setCredentialName] = useState('');
  const [scopes, setScopes] = useState(['invoke', 'read', 'cancel', 'interact']);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const apps = useQuery({ queryKey: ['applications', scope.tenant, scope.namespace], queryFn: () => listApplications(scope.tenant, scope.namespace) });
  const credentials = useQuery({ queryKey: ['application-credentials', application], queryFn: () => listApplicationCredentials(application), enabled: !!application });
  async function perform(work: () => Promise<void>) { setBusy(true); setError(''); try { await work(); } catch (e) { setError(e instanceof Error ? e.message : String(e)); } finally { setBusy(false); } }
  return <div className="space-y-4">
      <details><summary className="cursor-pointer text-sm font-semibold">Application credentials</summary><div className="mt-3 space-y-3">
        <select aria-label="Application" className="w-full rounded border p-2" value={application} onChange={e => setApplication(e.target.value)}><option value="">Select an application</option>{(apps.data?.items ?? []).filter(a => a.status === 'active').map(a => <option key={a.id} value={a.id}>{a.name}</option>)}</select>
        <div className="flex gap-2"><Input aria-label="New application name" placeholder="New application name" value={applicationName} onChange={e => setApplicationName(e.target.value)} /><Button disabled={busy || !applicationName.trim()} onClick={() => void perform(async () => { const result = await createApplication({ tenant: scope.tenant, namespace: scope.namespace, name: applicationName.trim() }); setApplication(result.application.id); setApplicationName(''); await apps.refetch(); })}>Create application</Button></div>
        <div className="flex flex-wrap gap-3">{['invoke', 'read', 'cancel', 'interact', 'webhooks:write'].map(value => <label key={value} className="flex gap-1 text-sm"><input type="checkbox" checked={scopes.includes(value)} onChange={e => setScopes(current => e.target.checked ? [...current, value] : current.filter(s => s !== value))} />{value}</label>)}</div>
        <div className="flex gap-2"><Input aria-label="Credential name" placeholder="Credential name" value={credentialName} onChange={e => setCredentialName(e.target.value)} /><Button disabled={busy || !application || !credentialName.trim()} onClick={() => void perform(async () => { const result = await createApplicationCredential(application, { name: credentialName, scopes, targets: [{ type: targetType, id: targetRef }] }); onApiKey(result.apiKey); await credentials.refetch(); })}>Issue key</Button></div>
        <p className="text-sm text-muted-foreground">This key grants access to this {targetType}. Copy a newly issued key before leaving this page. To rotate it, issue a replacement, update your application, then revoke the previous key.</p>
        {(credentials.data?.items ?? []).map(key => <div key={key.id} className="flex items-center gap-3 text-sm"><span>{key.name} · {key.status}</span><Button size="sm" variant="outline" disabled={busy || key.status !== 'active'} onClick={() => void perform(async () => { await revokeApplicationCredential(application, key.id); await credentials.refetch(); })}>Revoke</Button></div>)}
      </div></details>
      <label className="block space-y-1 text-sm"><span>{apiOnly ? 'Application API key for API requests' : 'Application API key (leave empty to use your signed-in identity)'}</span><Input type="password" autoComplete="off" value={apiKey} disabled={disabled} onChange={e => onApiKey(e.target.value)} /></label>
      {!!apiKey && !disabled && <Button size="sm" variant="outline" onClick={() => void navigator.clipboard.writeText(apiKey)}>Copy API key</Button>}
    {error && <p role="alert" className="text-sm text-red-600">{error}</p>}
  </div>;
}
