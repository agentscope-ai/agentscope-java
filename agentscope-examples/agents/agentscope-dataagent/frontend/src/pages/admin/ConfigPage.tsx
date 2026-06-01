import React, { useEffect, useState } from 'react';
import AdminPageLayout from '../../components/admin/AdminPageLayout';
import {
  getAgentscopeConfig, putAgentscopeConfig,
  getRuntimeConfig, putRuntimeConfig,
} from '../../api/admin';

const S: Record<string, React.CSSProperties> = {
  content: { maxWidth: 1100 },
  heading: { fontSize: '1.5rem', fontWeight: 700, color: '#0f172a', marginBottom: '1.75rem', letterSpacing: '-0.02em' },
  tabs: { display: 'flex', gap: 10, marginBottom: 20 },
  textarea: {
    width: '100%',
    minHeight: 420,
    background: '#f8fafc',
    border: '1px solid #e5e7eb',
    borderRadius: 10,
    color: '#0f172a',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
    fontSize: '0.9rem',
    padding: '1.25rem',
    resize: 'vertical',
    outline: 'none',
    boxSizing: 'border-box',
    lineHeight: 1.6,
  },
  row: { display: 'flex', gap: 12, marginTop: 14, alignItems: 'center' },
  saveBtn: {
    background: '#4f46e5',
    color: '#fff',
    border: 'none',
    borderRadius: 8,
    padding: '9px 24px',
    cursor: 'pointer',
    fontWeight: 600,
    fontSize: '0.92rem',
    boxShadow: '0 1px 3px rgba(79,70,229,0.25)',
  },
  reloadBtn: {
    background: '#ffffff',
    border: '1px solid #d1d5db',
    color: '#475569',
    borderRadius: 8,
    padding: '8px 18px',
    cursor: 'pointer',
    fontSize: '0.92rem',
    fontWeight: 500,
  },
  notice: { fontSize: '0.88rem', color: '#92400e', marginLeft: 12, background: '#fffbeb', padding: '6px 12px', borderRadius: 6, border: '1px solid #fcd34d' },
  err: { color: '#dc2626', fontSize: '0.88rem', marginTop: 10 },
  success: { color: '#16a34a', fontSize: '0.88rem', marginTop: 10 },
  runtimeTable: { width: '100%', borderCollapse: 'collapse', fontSize: '0.9rem', background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 12, overflow: 'hidden', boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  rth: { textAlign: 'left', padding: '0.7rem 1.1rem', color: '#64748b', fontWeight: 600, background: '#f8fafc', borderBottom: '1px solid #e5e7eb', fontSize: '0.78rem', textTransform: 'uppercase', letterSpacing: '0.04em' },
  rtd: { padding: '0.7rem 1.1rem', borderBottom: '1px solid #f1f5f9', color: '#334155', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', wordBreak: 'break-all', fontSize: '0.86rem' },
};

function tabBtnStyle(active: boolean): React.CSSProperties {
  return {
    background: active ? '#eef2ff' : '#ffffff',
    border: `1px solid ${active ? '#c7d2fe' : '#d1d5db'}`,
    color: active ? '#4338ca' : '#475569',
    borderRadius: 8,
    padding: '7px 18px',
    cursor: 'pointer',
    fontSize: '0.9rem',
    fontWeight: active ? 600 : 500,
  };
}

function AgentscopeConfigEditor() {
  const [raw, setRaw] = useState('');
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [msg, setMsg] = useState<{ ok: boolean; text: string } | null>(null);

  async function load() {
    setLoading(true);
    setMsg(null);
    try {
      const data = await getAgentscopeConfig();
      setRaw(JSON.stringify(data, null, 2));
    } catch (e) {
      setMsg({ ok: false, text: String(e) });
    } finally {
      setLoading(false);
    }
  }

  async function save() {
    setSaving(true);
    setMsg(null);
    try {
      const parsed = JSON.parse(raw);
      const r = await putAgentscopeConfig(parsed);
      setMsg({ ok: r.success, text: r.message });
    } catch (e) {
      setMsg({ ok: false, text: `Invalid JSON or save failed: ${e}` });
    } finally {
      setSaving(false);
    }
  }

  useEffect(() => { load(); }, []);

  return (
    <div>
      <textarea
        style={{ ...S.textarea, opacity: loading ? 0.5 : 1 }}
        value={loading ? 'Loading…' : raw}
        onChange={e => setRaw(e.target.value)}
        spellCheck={false}
        disabled={loading || saving}
      />
      <div style={S.row}>
        <button style={S.saveBtn} onClick={save} disabled={loading || saving}>{saving ? 'Saving…' : 'Save'}</button>
        <button style={S.reloadBtn} onClick={load} disabled={loading}>↺ Reload</button>
        {msg && <span style={msg.ok ? S.success : S.err}>{msg.text}</span>}
        {!msg && <span style={S.notice}>⚠ Changes require application restart to take effect</span>}
      </div>
    </div>
  );
}

function RuntimeConfigViewer() {
  const [data, setData] = useState<Record<string, unknown> | null>(null);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  async function load() {
    setLoading(true);
    setErr(null);
    try { setData(await getRuntimeConfig()); }
    catch (e) { setErr(String(e)); }
    finally { setLoading(false); }
  }

  useEffect(() => { load(); }, []);

  function renderValue(v: unknown): string {
    if (typeof v === 'object' && v !== null) return JSON.stringify(v, null, 2);
    return String(v ?? '');
  }

  return (
    <div>
      {err && <div style={S.err}>{err}</div>}
      {data && (
        <table style={S.runtimeTable}>
          <thead>
            <tr>
              <th style={S.rth}>Parameter</th>
              <th style={S.rth}>Value</th>
            </tr>
          </thead>
          <tbody>
            {Object.entries(data).map(([k, v]) => (
              <tr key={k}>
                <td style={{ ...S.rtd, color: '#2563eb', fontWeight: 600 }}>{k}</td>
                <td style={S.rtd}>{renderValue(v)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      <div style={{ marginTop: 10 }}>
        <button style={S.reloadBtn} onClick={load} disabled={loading}>{loading ? 'Loading…' : '↺ Refresh'}</button>
      </div>
    </div>
  );
}

const CONFIG_TABS = [
  { key: 'agentscope', label: 'agentscope.json', icon: '⚙️' },
  { key: 'runtime',    label: 'Runtime Parameters', icon: '🔧' },
];

export default function ConfigPage() {
  const [tab, setTab] = useState<'agentscope' | 'runtime'>('agentscope');

  return (
    <>
      <AdminPageLayout
        tabs={CONFIG_TABS}
        activeTab={tab}
        onTabChange={k => setTab(k as 'agentscope' | 'runtime')}
      >
        {tab === 'agentscope' && <AgentscopeConfigEditor />}
        {tab === 'runtime' && <RuntimeConfigViewer />}
      </AdminPageLayout>
    </>
  );
}
