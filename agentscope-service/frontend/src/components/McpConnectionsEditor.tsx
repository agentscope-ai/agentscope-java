import React, { useState } from 'react';
import type { AgentToolset, McpServerSpec, ToolConfigEntry } from '../api/agents';

interface Props {
  servers: McpServerSpec[];
  tools: AgentToolset[];
  readOnly?: boolean;
  onSave: (servers: McpServerSpec[], tools: AgentToolset[]) => Promise<unknown>;
}
const field: React.CSSProperties = { padding: 8, border: '1px solid #cbd5e1', borderRadius: 6, width: '100%', boxSizing: 'border-box' };
const button: React.CSSProperties = { padding: '7px 12px', border: '1px solid #cbd5e1', borderRadius: 6, background: '#fff', cursor: 'pointer' };

export default function McpConnectionsEditor({ servers, tools, readOnly, onSave }: Props) {
  const [editing, setEditing] = useState<string | null>(null);
  const [draft, setDraft] = useState<McpServerSpec>({ name: '', transport: 'http', required: true });
  const [enabled, setEnabled] = useState(false);
  const [policy, setPolicy] = useState('always_ask');
  const [entries, setEntries] = useState<ToolConfigEntry[]>([]);
  const [environment, setEnvironment] = useState('{}');
  const [headers, setHeaders] = useState('{}');
  const [args, setArgs] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  function edit(server?: McpServerSpec) {
    const toolset = tools.find(t => t.type === 'mcp_toolset' && t.mcpServerName === server?.name);
    setEditing(server?.name ?? '');
    setDraft(server ? { ...server, transport: server.transport ?? (server.url ? 'http' : 'stdio') } : { name: '', transport: 'http', required: true, timeout: 'PT30S' });
    setEnabled(server ? toolset?.defaultConfig?.enabled !== false : false);
    setPolicy(toolset?.defaultConfig?.permissionPolicy?.type ?? 'always_ask');
    setEntries(toolset?.configs ?? []);
    setEnvironment(JSON.stringify(server?.env ?? {}, null, 2));
    setHeaders(JSON.stringify(server?.headers ?? {}, null, 2));
    setArgs((server?.args ?? []).join('\n'));
    setError('');
  }
  function stringMap(raw: string): Record<string, string> {
    const result: unknown = JSON.parse(raw);
    if (!result || typeof result !== 'object' || Array.isArray(result) || Object.values(result).some(v => typeof v !== 'string')) throw new Error('Headers and environment must be JSON objects with string values.');
    return result as Record<string, string>;
  }
  async function save(event: React.FormEvent) {
    event.preventDefault(); setError(''); setBusy(true);
    try {
      if (!/^[A-Za-z0-9_-]{1,64}$/.test(draft.name) || draft.name.includes('__')) throw new Error('Use up to 64 letters, numbers, hyphens or single underscores for the connection name.');
      if (servers.some(s => s.name === draft.name && s.name !== editing)) throw new Error('Connection name already exists.');
      if (draft.transport !== 'stdio' && !/^https?:\/\//.test(draft.url ?? '')) throw new Error('Enter an HTTP or HTTPS endpoint.');
      if (draft.transport === 'stdio' && !draft.command?.trim()) throw new Error('Enter a command.');
      if (entries.some(e => !e.name?.trim()) || new Set(entries.map(e => e.name)).size !== entries.length) throw new Error('Tool names must be nonempty and unique.');
      const server = { ...draft, type: draft.transport === 'stdio' ? 'stdio' : 'url', env: stringMap(environment), headers: stringMap(headers), args: args.split('\n').filter(Boolean) };
      const toolset: AgentToolset = { type: 'mcp_toolset', mcpServerName: draft.name, defaultConfig: { enabled, permissionPolicy: { type: policy } }, configs: entries };
      await onSave([...servers.filter(s => s.name !== editing), server], [...tools.filter(t => !(t.type === 'mcp_toolset' && t.mcpServerName === editing)), toolset]);
      setEditing(null);
    } catch (e) { setError(e instanceof Error ? e.message : 'Failed to save connection'); }
    finally { setBusy(false); }
  }
  async function remove(name: string) {
    setBusy(true); setError('');
    try { await onSave(servers.filter(s => s.name !== name), tools.filter(t => !(t.type === 'mcp_toolset' && t.mcpServerName === name))); }
    catch (e) { setError(e instanceof Error ? e.message : 'Failed to remove connection'); }
    finally { setBusy(false); }
  }
  return <section style={{ padding: 16, border: '1px solid #e2e8f0', borderRadius: 10, background: '#fff' }}>
    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}><h3>MCP connections</h3>{!readOnly && <button style={button} disabled={busy} onClick={() => edit()}>Add connection</button>}</div>
    <p style={{ color: '#64748b', fontSize: 13 }}>Configure external tools here. Attach a Vault when starting a session; bearer credentials target the connection name or exact endpoint URL. Connection failures appear in session events.</p>
    {servers.map(server => <div key={server.name} style={{ display: 'flex', gap: 10, padding: '8px 0', alignItems: 'center' }}>
      <span style={{ flex: 1 }}><strong>{server.name}</strong> · {server.transport ?? 'http'} · {server.required === false ? 'optional' : 'required'}<br /><small>{server.url ?? server.command}</small></span>
      {!readOnly && <><button style={button} disabled={busy} onClick={() => edit(server)}>Edit</button><button style={button} disabled={busy} onClick={() => void remove(server.name)}>Remove</button></>}
    </div>)}
    {error && <p role="alert" style={{ color: '#b91c1c' }}>{error}</p>}
    {editing !== null && !readOnly && <form onSubmit={save} style={{ display: 'grid', gap: 12, marginTop: 12 }}>
      <label>Name<input style={field} value={draft.name} onChange={e => setDraft({ ...draft, name: e.target.value })} required /></label>
      <label>Transport<select style={field} value={draft.transport ?? 'http'} onChange={e => setDraft({ ...draft, transport: e.target.value })}><option value="http">Streamable HTTP</option><option value="sse">SSE</option><option value="stdio">stdio (local environment only)</option></select></label>
      {draft.transport === 'stdio' ? <><p>Requires an explicit local environment. Use HTTP or SSE for sandbox and self-hosted environments.</p><label>Command<input style={field} value={draft.command ?? ''} onChange={e => setDraft({ ...draft, command: e.target.value })} /></label><label>Arguments, one per line<textarea style={field} value={args} onChange={e => setArgs(e.target.value)} /></label></> : <label>Endpoint URL<input style={field} value={draft.url ?? ''} onChange={e => setDraft({ ...draft, url: e.target.value })} /></label>}
      <label>Request timeout (ISO duration)<input style={field} value={draft.timeout ?? 'PT30S'} onChange={e => setDraft({ ...draft, timeout: e.target.value })} placeholder="PT30S" /></label>
      <label><input type="checkbox" checked={draft.required !== false} onChange={e => setDraft({ ...draft, required: e.target.checked })} /> Required: fail the turn if this connection cannot load; retry on the next turn</label>
      <label><input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} /> Enable tools by default, including tools added by the server later</label>
      <label>Default permission<select style={field} value={policy} onChange={e => setPolicy(e.target.value)}><option value="always_ask">Ask before calling</option><option value="always_allow">Allow</option><option value="deny">Deny</option></select></label>
      <div>Per-tool overrides (original MCP tool names)</div>
      {entries.map((entry, index) => <div key={index} style={{ display: 'flex', gap: 8 }}>
        <input aria-label={`Tool ${index + 1} name`} style={field} value={entry.name} onChange={e => setEntries(entries.map((item, i) => i === index ? { ...item, name: e.target.value } : item))} />
        <label><input type="checkbox" checked={entry.enabled ?? enabled} onChange={e => setEntries(entries.map((item, i) => i === index ? { ...item, enabled: e.target.checked } : item))} />Enabled</label>
        <select aria-label={`Tool ${index + 1} permission`} value={entry.permissionPolicy?.type ?? ''} onChange={e => setEntries(entries.map((item, i) => i === index ? { ...item, permissionPolicy: e.target.value ? { type: e.target.value } : undefined } : item))}><option value="">Inherit</option><option value="always_ask">Ask</option><option value="always_allow">Allow</option><option value="deny">Deny</option></select>
        <button style={button} type="button" onClick={() => setEntries(entries.filter((_, i) => i !== index))}>Remove</button>
      </div>)}
      <button style={button} type="button" onClick={() => setEntries([...entries, { name: '', enabled: true }])}>Add tool override</button>
      <details><summary>Headers and stdio environment</summary><p>Use explicit Vault environment placeholders such as {'${TOKEN}'}. Keep credentials out of the definition.</p><label>Headers<textarea style={field} value={headers} onChange={e => setHeaders(e.target.value)} /></label><label>Environment<textarea style={field} value={environment} onChange={e => setEnvironment(e.target.value)} /></label></details>
      <div style={{ display: 'flex', gap: 8 }}><button style={button} type="submit" disabled={busy}>{busy ? 'Saving…' : 'Save connection'}</button><button style={button} type="button" disabled={busy} onClick={() => setEditing(null)}>Cancel</button></div>
    </form>}
  </section>;
}
