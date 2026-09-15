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
import { getGitHubApplication, saveGitHubApplication, type GitHubApplication } from '@/api/mcpOAuth';
import { isAdmin } from '@/lib/auth';

export default function IntegrationsPage() {
  const [app, setApp] = useState<GitHubApplication>();
  const [secret, setSecret] = useState('');
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [busy, setBusy] = useState(false);
  const admin = isAdmin();
  useEffect(() => {
    if (admin) getGitHubApplication().then(setApp).catch(e => setError(e.message));
  }, [admin]);
  if (!admin) return <p className="p-8">A platform administrator manages integrations.</p>;
  const input = 'mt-1 w-full rounded-md border border-slate-300 px-3 py-2';
  return <main className="max-w-3xl p-5 sm:p-8">
    <h1 className="text-xl font-semibold">Integrations</h1>
    <p className="mt-2 text-sm text-slate-500">Configure applications once. Users authorize their own accounts from an Agent’s MCP connection.</p>
    {error && <p role="alert" className="my-4 text-red-700">{error}</p>}
    {notice && <p role="status" className="my-4 text-emerald-700">{notice}</p>}
    {!app && !error && <p className="mt-4">Loading…</p>}
    {app && <form className="mt-6 grid gap-4 rounded-lg border p-5" onSubmit={async e => {
      e.preventDefault(); setBusy(true); setError(''); setNotice('');
      try {
        setApp(await saveGitHubApplication({ enabled: app.enabled, clientId: app.clientId, scope: app.scope, revision: app.revision, clientSecret: secret }));
        setSecret(''); setNotice('GitHub application saved. Users can connect from Tools & MCP. Pending authorizations must be restarted.');
      } catch (e) { setError(e instanceof Error ? e.message : 'Could not save GitHub application'); }
      finally { setBusy(false); }
    }}>
      <h2 className="font-semibold">GitHub</h2>
      <p className="text-sm text-slate-600">Register an OAuth App or a GitHub App with user authorization on GitHub. Use its Client ID and client secret. This connects GitHub tools; it does not change platform sign-in.</p>
      <a className="text-sm text-indigo-600 underline" href="https://github.com/settings/developers" target="_blank" rel="noreferrer">Open GitHub developer settings</a>
      <label>Callback URL<input className={input} readOnly value={app.callbackUrl} onFocus={e => e.target.select()} /></label>
      <p className="text-sm text-slate-500">Register this exact callback URL in your GitHub application. It is shared by all account connections on this deployment.</p>
      <fieldset disabled={busy} className="grid gap-4">
        <label><input type="checkbox" checked={app.enabled} onChange={e => setApp({ ...app, enabled: e.target.checked })} /> Enable GitHub account connections</label>
        <label>Client ID<input className={input} required={app.enabled} value={app.clientId} onChange={e => setApp({ ...app, clientId: e.target.value })} /></label>
        <label>Client secret<input className={input} type="password" autoComplete="new-password" value={secret} placeholder={app.hasClientSecret ? 'Saved securely; leave blank to keep' : 'GitHub application client secret'} onChange={e => setSecret(e.target.value)} /></label>
        <label>OAuth scopes<input className={input} value={app.scope} placeholder="read:user" onChange={e => setApp({ ...app, scope: e.target.value })} /></label>
        <p className="text-sm text-slate-500">For an OAuth App, request only the scopes you need. Private repositories typically require repo, which also grants write access. GitHub Apps use permissions and repository access configured on GitHub; leave scopes empty for that flow. Organization approval may be required.</p>
        <p className="text-sm text-slate-500">Disabling stops new authorizations; it does not revoke existing grants. Revoke access on GitHub when needed. Changing Client ID requires a new secret and users must reconnect.</p>
        <button type="submit" className="justify-self-start rounded-md bg-indigo-600 px-4 py-2 text-white">{busy ? 'Saving…' : 'Save GitHub application'}</button>
      </fieldset>
    </form>}
  </main>;
}
