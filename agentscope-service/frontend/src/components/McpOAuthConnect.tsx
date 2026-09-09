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

import React, { useEffect, useRef, useState } from 'react';
import type { McpServerSpec } from '../api/agents';
import { createVault, listVaults, type Vault } from '../api/vaults';
import { cancelMcpOAuth, completeMcpOAuth, disconnectMcpOAuth, getMcpOAuthStatus, listMcpOAuth, saveMcpOAuth, startMcpOAuth, type McpOAuthConnection, type McpOAuthSettings } from '../api/mcpOAuth';
import { createGitHubConnection, getGitHubProvider, isGitHubMcp, verifyGitHubConnection } from '../api/mcpOAuth';
import { isAdmin } from '../lib/auth';
import { Dialog, DialogBody, DialogContent, DialogDescription, DialogHeader, DialogTitle } from './ui/dialog';

interface Props {
  server: McpServerSpec;
  onClose: () => void;
  onConnected?: (vaultId: string) => Promise<void>;
}
const field: React.CSSProperties = { width: '100%', padding: 8, border: '1px solid #cbd5e1', borderRadius: 6 };
const button: React.CSSProperties = { padding: '8px 12px', border: '1px solid #cbd5e1', borderRadius: 6, background: '#fff' };
const emptySettings = (): McpOAuthSettings => ({ authorizationEndpoint: '', tokenEndpoint: '', clientId: '', authMethod: 'client_secret_basic', scope: '' });
const errorText = (e: unknown) => e instanceof Error ? e.message : 'OAuth request failed';

export default function McpOAuthConnect({ server, onClose, onConnected }: Props) {
  const [vaults, setVaults] = useState<Vault[]>([]);
  const [vaultId, setVaultId] = useState('');
  const [connection, setConnection] = useState<McpOAuthConnection>();
  const [settings, setSettings] = useState<McpOAuthSettings>(() => ({ ...emptySettings(), resource: server.url }));
  const [params, setParams] = useState('{}');
  const [loading, setLoading] = useState(true);
  const [busy, setBusy] = useState(false);
  const [waiting, setWaiting] = useState(false);
  const [dirty, setDirty] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [needsAttach, setNeedsAttach] = useState(false);
  const github = isGitHubMcp(server.url);
  const [provider, setProvider] = useState<{ configured: boolean; scope: string }>();
  const managedGitHub = github && (!connection || connection.provider === 'github');
  const active = useRef<{ connection: McpOAuthConnection; flowId: string; popup: Window; timer?: ReturnType<typeof setTimeout> }>();
  const mounted = useRef(true);
  const popupRef = useRef<Window | null>(null);

  useEffect(() => {
    mounted.current = true;
    listVaults().then(items => { if (mounted.current) setVaults(items); }).catch(e => { if (mounted.current) setError(errorText(e)); }).finally(() => { if (mounted.current) setLoading(false); });
    return () => {
      mounted.current = false;
      popupRef.current?.close();
      const flow = active.current;
      active.current = undefined;
      if (flow) { clearTimeout(flow.timer); void cancelMcpOAuth(flow.connection, flow.flowId).catch(() => {}); }
    };
  }, []);
  useEffect(() => {
    let live = true;
    if (github) getGitHubProvider().then(value => { if (live) setProvider(value); }).catch(e => { if (live) setError(errorText(e)); });
    return () => { live = false; };
  }, [github]);
  useEffect(() => {
    let live = true;
    setConnection(undefined); setSettings({ ...emptySettings(), resource: server.url }); setParams('{}'); setDirty(false); setNotice(''); setNeedsAttach(false);
    if (!vaultId) return;
    setLoading(true); setError('');
    listMcpOAuth(vaultId).then(items => {
      if (!live) return;
      const found = items.find(item => item.endpoint === server.url);
      setConnection(found);
      if (found) { setSettings({ ...found.settings, clientSecret: '' }); setParams(JSON.stringify(found.settings.authorizationParams ?? {}, null, 2)); }
    }).catch(e => { if (live) setError(errorText(e)); }).finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [vaultId, server.url]);

  function change(key: keyof McpOAuthSettings, value: string) {
    setSettings(old => ({ ...old, [key]: value, ...(key === 'authMethod' && value === 'none' ? { clientSecret: '' } : {}) }));
    setDirty(true);
  }
  async function save(e: React.FormEvent) {
    e.preventDefault(); setBusy(true); setError(''); setNotice('');
    try {
      const parsed: unknown = JSON.parse(params);
      if (!parsed || Array.isArray(parsed) || typeof parsed !== 'object' || Object.values(parsed).some(v => typeof v !== 'string')) throw new Error('Authorization parameters must be a JSON object with string values.');
      const saved = await saveMcpOAuth(vaultId, { ...settings, authorizationParams: parsed as Record<string, string>, serverName: connection?.serverName ?? server.name, endpoint: server.url! }, connection?.id);
      setConnection(saved); setSettings({ ...saved.settings, clientSecret: '' }); setDirty(false);
      setNotice('Application saved. Register the callback URL below with your provider, then connect your account.');
    } catch (e) { setError(errorText(e)); } finally { setBusy(false); }
  }
  async function attach(id: string) {
    if (!onConnected) return;
    setNeedsAttach(true);
    await onConnected(id);
    setNeedsAttach(false);
  }
  function finishWaiting() {
    const flow = active.current;
    active.current = undefined;
    if (flow) clearTimeout(flow.timer);
    popupRef.current?.close(); popupRef.current = null;
    if (mounted.current) { setWaiting(false); setBusy(false); }
  }
  async function cancel() {
    const flow = active.current;
    if (!flow) return;
    setBusy(true);
    try { await cancelMcpOAuth(flow.connection, flow.flowId); finishWaiting(); setNotice('Authorization cancelled.'); }
    catch (e) { setError(errorText(e)); setBusy(false); }
  }
  async function connect() {
    if (!connection && (!managedGitHub || !vaultId || !provider?.configured)) return;
    const popup = window.open('about:blank', '_blank', 'popup,width=620,height=760');
    if (!popup) { setError('Allow pop-ups for this console, then try again.'); return; }
    popup.opener = null;
    popupRef.current = popup;
    setBusy(true); setError(''); setNotice('');
    try {
      const selected = connection ?? await createGitHubConnection(vaultId, server.name, server.url!);
      if (!mounted.current) { popup.close(); return; }
      setConnection(selected);
      const flow = await startMcpOAuth(selected);
      if (!mounted.current) { popup.close(); await cancelMcpOAuth(selected, flow.flowId); return; }
      const current = { connection: selected, flowId: flow.flowId, popup, timer: undefined as ReturnType<typeof setTimeout> | undefined };
      active.current = current;
      popup.location.replace(flow.authorizationUrl);
      setWaiting(true); setBusy(false);
      const poll = async () => {
        if (active.current !== current) return;
        try {
          const state = await getMcpOAuthStatus(selected, flow.flowId);
          if (active.current !== current) return;
          if (state.status === 'authorized' || state.status === 'completed') {
            setBusy(true);
            const result = await completeMcpOAuth(selected, flow.flowId);
            if (active.current !== current) return;
            finishWaiting(); setBusy(true); setConnection({ ...selected, connected: true, account: undefined });
            setNotice(onConnected ? 'Account connected. Adding the Vault to this agent…' : 'Account connected. Select this Vault in the agent runtime configuration or when starting a session.');
            try {
              await attach(result.vaultId);
              if (onConnected && mounted.current) setNotice('Account connected and Vault added to this agent. Start a new session to use it.');
            }
            catch (e) { if (mounted.current) setError(`Account connected, but the agent could not be updated: ${errorText(e)}. Retry below.`); }
            if (selected.provider === 'github' && mounted.current) {
              try {
                const account = await verifyGitHubConnection(selected);
                if (mounted.current) setConnection({ ...selected, connected: true, account });
              } catch (e) { if (mounted.current) setError(`Account authorized; connection verification failed: ${errorText(e)}`); }
            }
            if (mounted.current) setBusy(false);
            return;
          }
          if (['failed', 'cancelled', 'expired'].includes(state.status)) throw new Error(`Authorization ${state.status}${state.errorCode ? ` (${state.errorCode})` : ''}. Try connecting again.`);
          if (Date.now() >= flow.expiresAt) throw new Error('Authorization expired. Try connecting again.');
          // A provider can close or sever the popup reference. Keep polling until completion,
          // cancellation or expiry; popup.closed alone is not a reliable OAuth result.
          current.timer = setTimeout(() => void poll(), 1500);
        } catch (e) {
          if (active.current !== current) return;
          // Stop on authorization/permission failures; no token is exposed to the browser.
          void cancelMcpOAuth(selected, flow.flowId).catch(() => {});
          finishWaiting(); setError(errorText(e));
        }
      };
      void poll();
    } catch (e) { finishWaiting(); if (mounted.current) setError(errorText(e)); }
  }
  async function create() {
    setBusy(true); setError('');
    try { const vault = await createVault({ displayName: `${server.name} OAuth` }); setVaults(items => [...items, vault]); setVaultId(vault.id); }
    catch (e) { setError(errorText(e)); } finally { setBusy(false); }
  }
  async function disconnect() {
    if (!connection) return;
    setBusy(true); setError('');
    try { await disconnectMcpOAuth(connection); setConnection({ ...connection, connected: false }); setNeedsAttach(false); setNotice('Credential removed from this Vault. Existing sessions may retain an issued access token until it expires. Revoke access at the provider to revoke the grant.'); }
    catch (e) { setError(errorText(e)); } finally { setBusy(false); }
  }
  const disabled = busy || waiting || loading;
  return <Dialog open onOpenChange={open => { if (!open && !busy) onClose(); }}>
    <DialogContent size="lg" onPointerDownOutside={e => e.preventDefault()}>
      <DialogHeader><DialogTitle>{github ? 'Connect GitHub' : 'Connect account'} · {server.name}</DialogTitle><DialogDescription>Sign in with the MCP provider. Tokens are encrypted in the selected Vault and used for this exact endpoint.</DialogDescription></DialogHeader>
      <DialogBody>
        <p className="mb-4 break-all text-sm text-slate-500">{server.url}</p>
        <div className="flex items-end gap-3"><label className="flex-1">Vault<select style={field} value={vaultId} disabled={disabled} onChange={e => setVaultId(e.target.value)}><option value="">Select a Vault</option>{vaults.map(v => <option key={v.id} value={v.id}>{v.displayName}</option>)}</select></label><button style={button} disabled={disabled} onClick={() => void create()}>Create Vault</button></div>
        <p className="my-3 text-sm text-slate-500">Choose who can use this account by choosing its Vault. Agents and sessions using this Vault can access the authorized MCP endpoint.</p>
        {error && <p role="alert" className="my-3 text-red-700">{error}</p>}
        {notice && <p role="status" className="my-3 text-emerald-800">{notice}</p>}
        {loading && <p>Loading…</p>}
        {managedGitHub && <div className="my-4 rounded-md bg-slate-50 p-3 text-sm">
          {!provider ? <p>Checking GitHub integration…</p> : provider.configured ? <><p>GitHub is configured by your platform administrator. Select or create a Vault, then sign in to authorize your account.</p><p className="mt-2">{provider.scope ? `Requested OAuth scopes: ${provider.scope}` : 'Access follows the GitHub application’s configured permissions.'} Organization approval may be required.</p></> : <p>A platform administrator must configure the GitHub application before accounts can connect. {isAdmin() && <a className="text-indigo-600 underline" href="/settings/integrations" target="_blank" rel="noreferrer">Open integration settings</a>}</p>}
        </div>}
        {vaultId && !loading && <>
          {!managedGitHub && <form onSubmit={save}>
            <fieldset disabled={disabled} className="grid gap-3">
              <details open={!connection || dirty}>
                <summary className="mb-3 cursor-pointer font-medium">OAuth application settings</summary>
                <div className="grid gap-3">
                  <p className="text-sm text-slate-500">Register an OAuth application with your provider and enter its settings. All connections use Authorization Code with PKCE (S256).</p>
                  <label>Authorization endpoint<input style={field} type="url" required placeholder="https://auth.example.com/oauth/authorize" value={settings.authorizationEndpoint} onChange={e => change('authorizationEndpoint', e.target.value)} /></label>
                  <label>Token endpoint<input style={field} type="url" required placeholder="https://auth.example.com/oauth/token" value={settings.tokenEndpoint} onChange={e => change('tokenEndpoint', e.target.value)} /></label>
                  <label>Client ID<input style={field} required value={settings.clientId} onChange={e => change('clientId', e.target.value)} /></label>
                  <label>Client authentication<select style={field} value={settings.authMethod} onChange={e => change('authMethod', e.target.value)}><option value="client_secret_basic">Client secret — Basic</option><option value="client_secret_post">Client secret — POST</option><option value="none">Public client — no secret</option></select></label>
                  {settings.authMethod !== 'none' && <label>Client secret<input style={field} type="password" autoComplete="new-password" required={!connection?.hasClientSecret} placeholder={connection?.hasClientSecret ? 'Saved securely; leave blank to keep' : 'Provided by your OAuth application'} value={settings.clientSecret ?? ''} onChange={e => change('clientSecret', e.target.value)} /></label>}
                  <label>Scopes<input style={field} placeholder="crm.read offline_access" value={settings.scope} onChange={e => change('scope', e.target.value)} /></label>
                  <label>Resource (optional)<input style={field} type="url" placeholder={server.url} value={settings.resource ?? ''} onChange={e => change('resource', e.target.value)} /></label>
                  <p className="text-sm text-slate-500">For MCP authorization servers, set Resource to the protected MCP resource URI (often the endpoint above). Use the provider’s documented scopes.</p>
                  <label>Expected issuer (optional)<input style={field} type="url" placeholder="https://auth.example.com" value={settings.issuer ?? ''} onChange={e => change('issuer', e.target.value)} /></label>
                  <p className="text-sm text-slate-500">Set issuer only if the provider returns the OAuth authorization response “iss” parameter.</p>
                  <label>Additional authorization parameters<textarea style={field} rows={3} value={params} placeholder={'{"access_type":"offline","prompt":"consent"}'} onChange={e => { setParams(e.target.value); setDirty(true); }} /></label>
                  <p className="text-sm text-slate-500">Optional JSON: access_type, prompt, audience, login_hint, include_granted_scopes.</p>
                  <button style={button} type="submit">Save application</button>
                </div>
              </details>
            </fieldset>
          </form>}
          {managedGitHub && !connection && <button style={button} disabled={disabled || !provider?.configured} onClick={() => void connect()}>Connect GitHub account</button>}
          {connection && <div className="mt-4 grid gap-3">
            {!managedGitHub && <><label>Callback URL<input style={field} readOnly value={connection.callbackUrl} onFocus={e => e.target.select()} /></label>
            <p className="text-sm text-slate-500">Register this exact URL in the provider’s OAuth application before connecting.</p></>}
            <p>Account: <strong>{connection.connected ? 'Connected' : 'Not connected'}</strong></p>
            {connection.connected && managedGitHub && <div className="rounded-md border p-3 text-sm" role="status">
              {connection.account?.login && <p>GitHub account: <strong>@{connection.account.login}</strong></p>}
              <p>{connection.account?.errorCode?.includes('reauthorization_required') || connection.account?.status === 'reauthorization_required' ? 'GitHub authorization could not be used. Reconnect your account.' : connection.account?.status === 'ready' ? `MCP connection verified · ${connection.account.toolCount} tools discovered` : connection.account?.login ? 'Account authorized; MCP tools are not yet available.' : 'Account authorized; verification is required.'}</p>
              {connection.account?.scope && <p>Granted scopes: {connection.account.scope}</p>}
              {connection.account?.errorCode && <p className="mt-2 text-amber-800">{connection.account.errorCode}. Check account permissions and organization access, or reconnect your account.</p>}
              {connection.account?.checkedAt && <p>Last checked: {new Date(connection.account.checkedAt).toLocaleString()}</p>}
              <p className="mt-2 text-slate-500">This checks connectivity from the control plane. Agent tool permissions and runtime connectivity still apply. Start a new session after adding this Vault.</p>
            </div>}
            {waiting ? <><p role="status">Complete login and consent in the provider window, then return here. Keep this dialog open.</p><button style={button} disabled={busy} onClick={() => void cancel()}>Cancel authorization</button></> : <div className="flex flex-wrap gap-3">
              <button style={button} disabled={disabled || dirty || (managedGitHub && !provider?.configured)} onClick={() => void connect()}>{connection.connected ? 'Reconnect account' : 'Connect account'}</button>
              {connection.connected && managedGitHub && <button style={button} disabled={disabled} onClick={async () => { setBusy(true); setError(''); try { const account = await verifyGitHubConnection(connection); setConnection({ ...connection, account }); } catch (e) { setError(errorText(e)); } finally { setBusy(false); } }}>Verify connection</button>}
              {connection.connected && <button style={button} disabled={disabled} onClick={() => void disconnect()}>Disconnect from Vault</button>}
              {connection.connected && onConnected && <button style={button} disabled={disabled} onClick={async () => { setBusy(true); setError(''); try { await attach(vaultId); setNotice('Vault added to this agent. Start a new session to use it.'); } catch (e) { setError(errorText(e)); } finally { setBusy(false); } }}>{needsAttach ? 'Retry adding Vault to agent' : 'Use for this agent'}</button>}
            </div>}
            {dirty && <p className="text-sm">Save application changes before connecting.</p>}
          </div>}
        </>}
      </DialogBody>
    </DialogContent>
  </Dialog>;
}
