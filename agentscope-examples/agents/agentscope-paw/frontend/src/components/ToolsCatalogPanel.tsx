import React, { useEffect, useMemo, useState } from 'react';
import {
  BuiltinToolInfo,
  McpCatalogEntry,
  McpServerConfig,
  ToolsConfig,
  fetchBuiltinCatalog,
  fetchConfig,
  fetchMcpCatalog,
  saveConfig,
} from '../api/tools';

interface Props {
  agentId: string;
  onSaved: () => void;
}

type Tab = 'builtin' | 'mcp';

function tabStyle(active: boolean): React.CSSProperties {
  return {
    background: 'transparent', border: 'none',
    borderBottom: `2px solid ${active ? '#6366f1' : 'transparent'}`,
    padding: '10px 16px', cursor: 'pointer',
    fontSize: '0.88rem', color: active ? '#0f172a' : '#64748b',
    fontWeight: active ? 600 : 500, marginBottom: -1,
  };
}

const S: Record<string, React.CSSProperties> = {
  root: { display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 },
  tabs: {
    display: 'flex', gap: 4, padding: '12px 20px 0', borderBottom: '1px solid #e2e8f0',
    background: '#ffffff',
  },
  body: { flex: 1, overflow: 'auto', padding: 20, background: '#f8fafc' },
  groupHeader: {
    fontSize: '0.74rem', fontWeight: 700, color: '#94a3b8',
    textTransform: 'uppercase', letterSpacing: '0.1em',
    marginTop: 8, marginBottom: 6,
  },
  row: {
    display: 'flex', alignItems: 'flex-start', gap: 12,
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 10,
    padding: '12px 14px', marginBottom: 8,
  },
  cb: { marginTop: 4 },
  rowName: { fontWeight: 600, color: '#0f172a', fontSize: '0.9rem' },
  rowDesc: { color: '#64748b', fontSize: '0.82rem', marginTop: 2, lineHeight: 1.45 },
  addBtn: {
    marginLeft: 'auto', flexShrink: 0,
    padding: '6px 14px', borderRadius: 7, border: '1px solid #c7d2fe',
    background: '#eef2ff', color: '#4338ca',
    fontSize: '0.78rem', fontWeight: 600, cursor: 'pointer',
  },
  installedTag: {
    marginLeft: 'auto', flexShrink: 0,
    padding: '4px 10px', borderRadius: 999, fontSize: '0.7rem', fontWeight: 600,
    background: '#dcfce7', color: '#166534', border: '1px solid #bbf7d0',
    textTransform: 'uppercase', letterSpacing: '0.04em',
  },
  footer: {
    padding: '12px 20px', borderTop: '1px solid #e2e8f0',
    background: '#ffffff', display: 'flex', alignItems: 'center', gap: 12,
  },
  status: { fontSize: '0.82rem', color: '#64748b', flex: 1 },
  saveBtn: {
    padding: '8px 18px', background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none', borderRadius: 8, cursor: 'pointer',
    fontSize: '0.86rem', fontWeight: 600,
  },
  saveBtnDisabled: { background: '#e2e8f0', color: '#94a3b8', cursor: 'not-allowed' },
  err: { color: '#dc2626', fontSize: '0.82rem' },
  formOverlay: {
    position: 'absolute', inset: 0, background: 'rgba(15,23,42,0.45)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 90,
  },
  formCard: {
    background: '#ffffff', borderRadius: 12, width: 'min(520px, 92vw)',
    maxHeight: '85vh', overflow: 'auto', padding: '20px 22px',
    boxShadow: '0 24px 60px rgba(15,23,42,0.3)',
  },
  formLabel: {
    display: 'block', fontSize: '0.78rem', fontWeight: 600, color: '#334155',
    marginTop: 12, marginBottom: 4,
  },
  formInput: {
    width: '100%', boxSizing: 'border-box',
    padding: '8px 11px', border: '1px solid #cbd5e1', borderRadius: 7,
    fontSize: '0.86rem', outline: 'none',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
  },
};

export default function ToolsCatalogPanel({ agentId, onSaved }: Props) {
  const [tab, setTab] = useState<Tab>('builtin');
  return (
    <div style={S.root}>
      <div style={S.tabs}>
        <button style={tabStyle(tab === 'builtin')} onClick={() => setTab('builtin')}>
          Built-in tools
        </button>
        <button style={tabStyle(tab === 'mcp')} onClick={() => setTab('mcp')}>
          MCP servers
        </button>
      </div>
      {tab === 'builtin' ? (
        <BuiltinTab agentId={agentId} onSaved={onSaved} />
      ) : (
        <McpTab agentId={agentId} onSaved={onSaved} />
      )}
    </div>
  );
}

// -------------------- Built-in tab --------------------

function BuiltinTab({ agentId, onSaved }: { agentId: string; onSaved: () => void }) {
  const [catalog, setCatalog] = useState<BuiltinToolInfo[]>([]);
  const [cfg, setCfg] = useState<ToolsConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  // Track checked names — the saved state derives from cfg.allow/deny semantics.
  // Empty allow + non-empty deny is the canonical default; if allow is non-empty we treat
  // it as "only these"; otherwise "all minus deny".
  const [enabled, setEnabled] = useState<Set<string>>(new Set());
  const [dirty, setDirty] = useState(false);

  useEffect(() => {
    let cancelled = false;
    setLoading(true); setErr(null);
    Promise.all([fetchBuiltinCatalog(agentId), fetchConfig(agentId)])
      .then(([cat, c]) => {
        if (cancelled) return;
        setCatalog(cat);
        setCfg(c);
        setEnabled(computeEnabled(cat, c));
        setDirty(false);
      })
      .catch(e => { if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [agentId]);

  function toggle(id: string) {
    setEnabled(prev => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
    setDirty(true);
  }

  async function save() {
    if (!cfg) return;
    setSaving(true); setErr(null);
    try {
      // Translate the checkbox state back into allow/deny. We use deny-as-default
      // (empty allow + deny = catalog-minus-enabled) to match the scaffolder.
      const next: ToolsConfig = { ...cfg };
      const all = catalog.map(b => b.id);
      const disabled = all.filter(id => !enabled.has(id));
      if (next.allow && next.allow.length > 0) {
        // user previously used allow-list mode; respect it.
        next.allow = all.filter(id => enabled.has(id));
        next.deny = [];
      } else {
        next.allow = [];
        next.deny = disabled;
      }
      await saveConfig(agentId, next);
      setCfg(next);
      setDirty(false);
      onSaved();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setSaving(false);
    }
  }

  const groups = useMemo(() => {
    const out = new Map<string, BuiltinToolInfo[]>();
    for (const b of catalog) {
      const g = b.group ?? 'other';
      if (!out.has(g)) out.set(g, []);
      out.get(g)!.push(b);
    }
    return out;
  }, [catalog]);

  return (
    <>
      <div style={S.body}>
        {loading && <div style={{ color: '#64748b' }}>Loading…</div>}
        {err && <div style={S.err}>{err}</div>}
        {!loading && Array.from(groups.entries()).map(([group, items]) => (
          <div key={group}>
            <div style={S.groupHeader}>{group}</div>
            {items.map(b => (
              <label key={b.id} style={S.row}>
                <input
                  type="checkbox"
                  checked={enabled.has(b.id)}
                  onChange={() => toggle(b.id)}
                  style={S.cb}
                />
                <div style={{ flex: 1, minWidth: 0 }}>
                  <div style={S.rowName}>{b.id}</div>
                  {b.description && <div style={S.rowDesc}>{b.description}</div>}
                </div>
              </label>
            ))}
          </div>
        ))}
      </div>
      <div style={S.footer}>
        <span style={S.status}>
          {dirty ? `${enabled.size} of ${catalog.length} enabled — unsaved` : `${enabled.size} of ${catalog.length} enabled`}
        </span>
        <button
          style={{ ...S.saveBtn, ...((!dirty || saving) ? S.saveBtnDisabled : {}) }}
          onClick={save}
          disabled={!dirty || saving}
        >
          {saving ? 'Saving…' : 'Save'}
        </button>
      </div>
    </>
  );
}

function computeEnabled(catalog: BuiltinToolInfo[], cfg: ToolsConfig): Set<string> {
  const allow = cfg.allow ?? [];
  const deny = new Set(cfg.deny ?? []);
  if (allow.length > 0) {
    // Allow-list mode: only listed tools are on.
    return new Set(allow);
  }
  // Deny-list mode (default): everything except denied.
  return new Set(catalog.map(b => b.id).filter(id => !deny.has(id)));
}

// -------------------- MCP tab --------------------

function McpTab({ agentId, onSaved }: { agentId: string; onSaved: () => void }) {
  const [catalog, setCatalog] = useState<McpCatalogEntry[]>([]);
  const [cfg, setCfg] = useState<ToolsConfig | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [adding, setAdding] = useState<McpCatalogEntry | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoading(true); setErr(null);
    Promise.all([fetchMcpCatalog(agentId), fetchConfig(agentId)])
      .then(([cat, c]) => {
        if (cancelled) return;
        setCatalog(cat);
        setCfg(c);
      })
      .catch(e => { if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [agentId]);

  async function install(name: string, server: McpServerConfig) {
    if (!cfg) return;
    const next: ToolsConfig = { ...cfg };
    next.mcpServers = { ...(next.mcpServers ?? {}), [name]: server };
    await saveConfig(agentId, next);
    setCfg(next);
    setAdding(null);
    onSaved();
  }

  const installed = useMemo(() => new Set(Object.keys(cfg?.mcpServers ?? {})), [cfg]);

  return (
    <>
      <div style={S.body}>
        {loading && <div style={{ color: '#64748b' }}>Loading…</div>}
        {err && <div style={S.err}>{err}</div>}
        {!loading && catalog.length === 0 && (
          <div style={{ color: '#94a3b8', padding: 24, textAlign: 'center' }}>
            No MCP servers in the catalog. You can still add servers by editing
            <code> workspace/tools.json</code> directly.
          </div>
        )}
        {catalog.map(entry => {
          const isInstalled = installed.has(entry.id);
          return (
            <div key={entry.id} style={S.row}>
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={S.rowName}>{entry.name}</div>
                {entry.description && <div style={S.rowDesc}>{entry.description}</div>}
                {entry.docsUrl && (
                  <a
                    href={entry.docsUrl}
                    target="_blank"
                    rel="noreferrer"
                    style={{ fontSize: '0.78rem', color: '#4338ca', marginTop: 4, display: 'inline-block' }}
                  >
                    docs ↗
                  </a>
                )}
              </div>
              {isInstalled ? (
                <span style={S.installedTag}>installed</span>
              ) : (
                <button style={S.addBtn} onClick={() => setAdding(entry)}>+ Add</button>
              )}
            </div>
          );
        })}
      </div>
      {adding && (
        <McpAddForm
          entry={adding}
          existingNames={installed}
          onCancel={() => setAdding(null)}
          onSubmit={install}
        />
      )}
    </>
  );
}

interface AddFormProps {
  entry: McpCatalogEntry;
  existingNames: Set<string>;
  onCancel: () => void;
  onSubmit: (name: string, server: McpServerConfig) => Promise<void>;
}

function McpAddForm({ entry, existingNames, onCancel, onSubmit }: AddFormProps) {
  const [name, setName] = useState(entry.id);
  const [envValues, setEnvValues] = useState<Record<string, string>>(() => {
    const out: Record<string, string> = {};
    for (const k of entry.requiredEnv ?? []) out[k] = '';
    return out;
  });
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function submit() {
    if (!name.trim()) { setErr('Name is required'); return; }
    if (existingNames.has(name.trim())) { setErr('That name is already in use'); return; }
    setBusy(true); setErr(null);
    try {
      const server: McpServerConfig = {
        transport: entry.transport,
      };
      if (entry.url) server.url = entry.url;
      if (entry.command) server.command = entry.command;
      if (entry.args && entry.args.length > 0) server.args = entry.args;
      if (entry.headers) server.headers = { ...entry.headers };
      if (entry.queryParams) server.queryParams = { ...entry.queryParams };
      if (entry.env) server.env = { ...entry.env };
      // Merge any user-supplied env values into server.env so they're persisted at the
      // agent-config level. (Substitution still resolves ${VAR} at agent boot if
      // the value happens to reference an env var.)
      if (Object.keys(envValues).length > 0) {
        server.env = { ...(server.env ?? {}), ...envValues };
      }
      await onSubmit(name.trim(), server);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Add failed');
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={S.formOverlay} onClick={onCancel}>
      <div style={S.formCard} onClick={e => e.stopPropagation()}>
        <div style={{ fontSize: '1.02rem', fontWeight: 600, color: '#0f172a' }}>
          Add MCP server: {entry.name}
        </div>
        <div style={{ fontSize: '0.82rem', color: '#64748b', marginTop: 4 }}>
          Transport: <b>{entry.transport}</b>
          {entry.url && <> &middot; URL: <code>{entry.url}</code></>}
        </div>

        <label style={S.formLabel}>Server name (key in tools.json)</label>
        <input
          style={S.formInput}
          value={name}
          onChange={e => setName(e.target.value)}
          disabled={busy}
          placeholder="e.g. amap"
        />

        {(entry.requiredEnv ?? []).length > 0 && (
          <>
            <div style={{ ...S.formLabel, marginTop: 18 }}>Environment variables</div>
            <div style={{ fontSize: '0.78rem', color: '#64748b', marginBottom: 6 }}>
              Leave blank to use the process environment (recommended for secrets).
              Values here are persisted into <code>tools.json</code>.
            </div>
            {(entry.requiredEnv ?? []).map(envKey => (
              <div key={envKey} style={{ marginBottom: 8 }}>
                <label style={{ fontSize: '0.78rem', color: '#475569', fontFamily: 'ui-monospace, monospace' }}>
                  {envKey}
                </label>
                <input
                  style={S.formInput}
                  type="text"
                  value={envValues[envKey] ?? ''}
                  onChange={e => setEnvValues(prev => ({ ...prev, [envKey]: e.target.value }))}
                  placeholder={`$${envKey}`}
                  disabled={busy}
                />
              </div>
            ))}
          </>
        )}

        {err && <div style={{ marginTop: 12, ...S.err }}>{err}</div>}

        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
          <button
            onClick={onCancel}
            disabled={busy}
            style={{
              padding: '7px 14px', border: '1px solid #cbd5e1', background: '#ffffff',
              color: '#475569', borderRadius: 7, cursor: busy ? 'not-allowed' : 'pointer',
              fontSize: '0.86rem', fontWeight: 500,
            }}
          >
            Cancel
          </button>
          <button
            onClick={submit}
            disabled={busy || !name.trim()}
            style={{
              padding: '7px 18px', border: 'none', borderRadius: 7,
              background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
              color: '#ffffff', cursor: busy ? 'not-allowed' : 'pointer',
              fontSize: '0.86rem', fontWeight: 600,
              opacity: busy || !name.trim() ? 0.6 : 1,
            }}
          >
            {busy ? 'Adding…' : 'Add server'}
          </button>
        </div>
      </div>
    </div>
  );
}
