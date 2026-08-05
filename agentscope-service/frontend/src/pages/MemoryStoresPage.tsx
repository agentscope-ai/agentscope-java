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

import React, { useEffect, useState } from 'react';
import {
  Memory,
  MemoryStore,
  archiveMemoryStore,
  createMemoryStore,
  deleteMemory,
  deleteMemoryStore,
  listMemories,
  listMemoryStores,
  putMemory,
  redactMemory,
} from '../api/memoryStores';

const S: Record<string, React.CSSProperties> = {
  root: { padding: '40px 44px', maxWidth: 1200 },
  header: { display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 12 },
  title: { margin: 0, fontSize: '1.75rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' },
  blurb: { margin: '0 0 24px', color: '#64748b', fontSize: '1rem', lineHeight: 1.6, maxWidth: 760 },
  primaryBtn: {
    display: 'inline-flex', alignItems: 'center', gap: 8,
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', border: 'none',
    borderRadius: 10, padding: '11px 20px', fontSize: '0.95rem', fontWeight: 600,
    cursor: 'pointer',
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  card: {
    background: '#ffffff', border: '1px solid #e2e8f0',
    borderRadius: 14, padding: '20px 22px', marginBottom: 18,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  rowBtn: {
    padding: '7px 14px', fontSize: '0.84rem', fontWeight: 500, borderRadius: 8, cursor: 'pointer',
    border: '1px solid #cbd5e1', background: '#ffffff', color: '#475569',
  },
  danger: { color: '#dc2626', borderColor: '#fca5a5' },
  formField: { display: 'block', fontSize: '0.85rem', color: '#475569', marginBottom: 6, fontWeight: 500 },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '10px 12px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 8,
    color: '#0f172a', fontSize: '0.92rem',
  },
  textarea: {
    width: '100%', boxSizing: 'border-box', padding: '10px 12px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 8,
    color: '#0f172a', fontSize: '0.92rem', minHeight: 120, fontFamily: 'ui-monospace, monospace',
  },
  err: { color: '#dc2626', fontSize: '0.95rem', marginBottom: 16 },
  table: { width: '100%', borderCollapse: 'collapse', fontSize: '0.88rem' },
  th: { textAlign: 'left', padding: '8px 10px', borderBottom: '1px solid #e2e8f0', color: '#64748b', fontWeight: 600 },
  td: { padding: '10px', borderBottom: '1px solid #f1f5f9', color: '#334155' },
  modal: {
    position: 'fixed', inset: 0, background: 'rgba(15,23,42,0.45)',
    display: 'flex', alignItems: 'center', justifyContent: 'center', zIndex: 200,
  },
  modalBody: {
    background: '#ffffff', borderRadius: 14, padding: '28px 32px',
    width: '100%', maxWidth: 480, boxShadow: '0 20px 50px rgba(15,23,42,0.2)',
  },
};

export default function MemoryStoresPage() {
  const [stores, setStores] = useState<MemoryStore[]>([]);
  const [selected, setSelected] = useState<MemoryStore | null>(null);
  const [memories, setMemories] = useState<Memory[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [creating, setCreating] = useState(false);
  const [editing, setEditing] = useState<{ path: string; content: string } | null>(null);
  const [storeName, setStoreName] = useState('');
  const [storeDesc, setStoreDesc] = useState('');
  const [busyId, setBusyId] = useState<string | null>(null);

  async function refreshStores() {
    setLoading(true);
    setErr(null);
    try {
      setStores(await listMemoryStores());
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load');
    } finally {
      setLoading(false);
    }
  }

  async function loadMemories(store: MemoryStore) {
    setSelected(store);
    setErr(null);
    try {
      setMemories(await listMemories(store.id));
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Failed to load memories');
    }
  }

  useEffect(() => { void refreshStores(); }, []);

  async function handleCreateStore(e: React.FormEvent) {
    e.preventDefault();
    if (!storeName.trim()) return;
    setBusyId('create');
    try {
      const created = await createMemoryStore({ name: storeName.trim(), description: storeDesc.trim() || undefined });
      setCreating(false);
      setStoreName('');
      setStoreDesc('');
      await refreshStores();
      await loadMemories(created);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Create failed');
    } finally {
      setBusyId(null);
    }
  }

  async function handleArchiveStore(id: string) {
    if (!confirm('Archive this memory store? It will no longer mount on new sessions.')) return;
    setBusyId(id);
    try {
      await archiveMemoryStore(id);
      if (selected?.id === id) { setSelected(null); setMemories([]); }
      await refreshStores();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Archive failed');
    } finally {
      setBusyId(null);
    }
  }

  async function handleDeleteStore(id: string) {
    if (!confirm('Delete this memory store and all memories?')) return;
    setBusyId(id);
    try {
      await deleteMemoryStore(id);
      if (selected?.id === id) { setSelected(null); setMemories([]); }
      await refreshStores();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed');
    } finally {
      setBusyId(null);
    }
  }

  async function handleRedactMemory(path: string) {
    if (!selected) return;
    if (!confirm(`Redact "${path}" permanently? Version history will be cleared.`)) return;
    setBusyId(path);
    try {
      await redactMemory(selected.id, path);
      await loadMemories(selected);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Redact failed');
    } finally {
      setBusyId(null);
    }
  }

  async function handleSaveMemory(e: React.FormEvent) {
    e.preventDefault();
    if (!selected || !editing) return;
    setBusyId('save');
    try {
      await putMemory(selected.id, editing.path, { content: editing.content });
      setEditing(null);
      await loadMemories(selected);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Save failed');
    } finally {
      setBusyId(null);
    }
  }

  async function handleDeleteMemory(path: string) {
    if (!selected || !confirm(`Delete memory "${path}"?`)) return;
    setBusyId(path);
    try {
      await deleteMemory(selected.id, path);
      await loadMemories(selected);
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : 'Delete failed');
    } finally {
      setBusyId(null);
    }
  }

  return (
    <div style={S.root}>
      <div style={S.header}>
        <h1 style={S.title}>Memory Stores</h1>
        <button type="button" style={S.primaryBtn} onClick={() => setCreating(true)}>＋ New store</button>
      </div>
      <p style={S.blurb}>
        Cross-session memory stores mountable on managed agent sessions. Documents are versioned by path.
      </p>
      {err && <div style={S.err}>{err}</div>}
      {loading && <div style={{ color: '#64748b' }}>Loading…</div>}

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(260px,1fr) minmax(0,2fr)', gap: 24 }}>
        <div>
          {stores.map(s => (
            <div
              key={s.id}
              style={{
                ...S.card,
                cursor: 'pointer',
                borderColor: selected?.id === s.id ? '#c7d2fe' : '#e2e8f0',
                background: selected?.id === s.id ? '#eef2ff' : '#ffffff',
              }}
              onClick={() => loadMemories(s)}
            >
              <div style={{ fontWeight: 600 }}>{s.name}</div>
              {s.description && <div style={{ fontSize: '0.88rem', color: '#64748b' }}>{s.description}</div>}
              <div style={{ fontSize: '0.76rem', color: '#94a3b8', fontFamily: 'monospace' }}>{s.id}</div>
              <div style={{ display: 'flex', gap: 6, marginTop: 8, flexWrap: 'wrap' }}>
                <button
                  type="button"
                  style={S.rowBtn}
                  onClick={e => { e.stopPropagation(); void handleArchiveStore(s.id); }}
                >
                  Archive
                </button>
                <button
                  type="button"
                  style={{ ...S.rowBtn, ...S.danger }}
                  onClick={e => { e.stopPropagation(); void handleDeleteStore(s.id); }}
                >
                  Delete
                </button>
              </div>
            </div>
          ))}
          {!loading && stores.length === 0 && (
            <div style={{ color: '#94a3b8', fontStyle: 'italic' }}>No memory stores yet.</div>
          )}
        </div>

        <div>
          {selected ? (
            <div style={S.card}>
              <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 16 }}>
                <h2 style={{ margin: 0, fontSize: '1.1rem' }}>{selected.name} — memories</h2>
                <button
                  type="button"
                  style={S.rowBtn}
                  onClick={() => setEditing({ path: 'notes.md', content: '' })}
                >
                  ＋ Add memory
                </button>
              </div>
              <table style={S.table}>
                <thead>
                  <tr>
                    <th style={S.th}>Path</th>
                    <th style={S.th}>Version</th>
                    <th style={S.th}>Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {memories.map(m => (
                    <tr key={m.id}>
                      <td style={S.td}><code>{m.path}</code></td>
                      <td style={S.td}>v{m.headVersion}</td>
                      <td style={S.td}>
                        <button type="button" style={S.rowBtn} onClick={() => setEditing({ path: m.path, content: m.content })}>Edit</button>
                        {' '}
                        <button type="button" style={S.rowBtn} onClick={() => handleRedactMemory(m.path)}>Redact</button>
                        {' '}
                        <button type="button" style={{ ...S.rowBtn, ...S.danger }} onClick={() => handleDeleteMemory(m.path)}>Delete</button>
                      </td>
                    </tr>
                  ))}
                  {memories.length === 0 && (
                    <tr><td colSpan={3} style={{ ...S.td, color: '#94a3b8', fontStyle: 'italic' }}>No memories in this store.</td></tr>
                  )}
                </tbody>
              </table>
            </div>
          ) : (
            <div style={{ color: '#94a3b8', padding: '40px 0', textAlign: 'center' }}>Select a store to view memories.</div>
          )}
        </div>
      </div>

      {creating && (
        <div style={S.modal} onClick={() => setCreating(false)}>
          <div style={S.modalBody} onClick={e => e.stopPropagation()}>
            <h2 style={{ margin: '0 0 18px', fontSize: '1.2rem' }}>New memory store</h2>
            <form onSubmit={handleCreateStore}>
              <label style={S.formField}>Name</label>
              <input style={{ ...S.input, marginBottom: 14 }} value={storeName} onChange={e => setStoreName(e.target.value)} autoFocus />
              <label style={S.formField}>Description</label>
              <input style={{ ...S.input, marginBottom: 20 }} value={storeDesc} onChange={e => setStoreDesc(e.target.value)} />
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button type="button" style={S.rowBtn} onClick={() => setCreating(false)}>Cancel</button>
                <button type="submit" style={S.primaryBtn} disabled={busyId === 'create'}>Create</button>
              </div>
            </form>
          </div>
        </div>
      )}

      {editing && selected && (
        <div style={S.modal} onClick={() => setEditing(null)}>
          <div style={{ ...S.modalBody, maxWidth: 560 }} onClick={e => e.stopPropagation()}>
            <h2 style={{ margin: '0 0 18px', fontSize: '1.2rem' }}>Edit memory</h2>
            <form onSubmit={handleSaveMemory}>
              <label style={S.formField}>Path</label>
              <input style={{ ...S.input, marginBottom: 14 }} value={editing.path} onChange={e => setEditing({ ...editing, path: e.target.value })} />
              <label style={S.formField}>Content</label>
              <textarea style={{ ...S.textarea, marginBottom: 20 }} value={editing.content} onChange={e => setEditing({ ...editing, content: e.target.value })} />
              <div style={{ display: 'flex', gap: 10, justifyContent: 'flex-end' }}>
                <button type="button" style={S.rowBtn} onClick={() => setEditing(null)}>Cancel</button>
                <button type="submit" style={S.primaryBtn} disabled={busyId === 'save'}>Save</button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
