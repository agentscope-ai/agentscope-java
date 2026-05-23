import React, { useCallback, useEffect, useState } from 'react';
import { Link, useParams, useSearchParams } from 'react-router-dom';
import AppShell from '../../components/admin/AdminAppShell';
import AdminPageLayout, { AdminTab } from '../../components/admin/AdminPageLayout';
import {
  getAgentDetail,
  AgentDetailView,
  AgentDefinitionFull,
  updateAgentConfig,
  AgentUpdateRequest,
  getAgentsMd,
  putAgentsMd,
  getWorkspaceMemory,
  getWorkspaceDailyMemory,
  listWorkspaceSkills,
  getWorkspaceSkill,
  createWorkspaceSkill,
  updateWorkspaceSkill,
  deleteWorkspaceSkill,
  listWorkspaceSubagents,
  getWorkspaceSubagent,
  createWorkspaceSubagent,
  updateWorkspaceSubagent,
  deleteWorkspaceSubagent,
  scaffoldWorkspace,
  WorkspaceSkillEntry,
  WorkspaceSubagentEntry,
  WorkspaceMemoryView,
} from '../../api/admin';

// ---------------------------------------------------------------------------
//  Design tokens
// ---------------------------------------------------------------------------

const C = {
  bg:       '#f8fafc',
  surface:  '#ffffff',
  border:   '#e5e7eb',
  text:     '#0f172a',
  muted:    '#64748b',
  dimmed:   '#94a3b8',
  accent:   '#4f46e5',
  accentBg: '#eef2ff',
  green:    '#16a34a',
  greenBg:  '#dcfce7',
  red:      '#dc2626',
  yellow:   '#d97706',
};

// ---------------------------------------------------------------------------
//  Utility helpers
// ---------------------------------------------------------------------------

function csvToArr(s: string): string[] | undefined {
  const a = s.split(',').map(x => x.trim()).filter(Boolean);
  return a.length ? a : undefined;
}
function arrToCsv(a: string[] | null | undefined): string {
  return (a ?? []).join(', ');
}

// ---------------------------------------------------------------------------
//  Page-level tab definitions
// ---------------------------------------------------------------------------

type PageTab = 'config' | 'workspace';

const PAGE_TABS: AdminTab[] = [
  { key: 'config',    label: 'Config',    icon: '⚙️' },
  { key: 'workspace', label: 'Workspace', icon: '📁' },
];

// ---------------------------------------------------------------------------
//  ── CONFIG PANEL ──────────────────────────────────────────────────────────
// ---------------------------------------------------------------------------

const SF: Record<string, React.CSSProperties> = {
  wrap:     { maxWidth: 1000, padding: '28px 32px' },
  grid:     { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24, marginBottom: 28 },
  card:     { background: C.surface, border: `1px solid ${C.border}`, borderRadius: 14, padding: '1.5rem 1.75rem', boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  cardTitle:{ fontSize: '0.8rem', fontWeight: 700, color: C.accent, textTransform: 'uppercase' as const, letterSpacing: '0.06em', marginBottom: 18 },
  label:    { display: 'block', fontSize: '0.85rem', color: '#475569', fontWeight: 500, marginBottom: 6 },
  input:    { width: '100%', boxSizing: 'border-box' as const, padding: '9px 12px', background: '#ffffff', border: `1px solid #d1d5db`, borderRadius: 8, color: C.text, fontSize: '0.92rem', outline: 'none', marginBottom: 14 },
  textarea: { width: '100%', boxSizing: 'border-box' as const, minHeight: 180, padding: '12px', background: '#f8fafc', border: `1px solid #d1d5db`, borderRadius: 8, color: '#0f172a', fontSize: '0.9rem', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', outline: 'none', resize: 'vertical' as const, marginBottom: 14, lineHeight: 1.6 },
  select:   { width: '100%', boxSizing: 'border-box' as const, padding: '9px 12px', background: '#ffffff', border: `1px solid #d1d5db`, borderRadius: 8, color: C.text, fontSize: '0.92rem', marginBottom: 14 },
  row2:     { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 14 },
  checkRow: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 14 },
  hint:     { fontSize: '0.8rem', color: C.dimmed, marginTop: -10, marginBottom: 12 },
  btnRow:   { display: 'flex', gap: 12, alignItems: 'center', marginTop: 8 },
  saveBtn:  { background: '#4f46e5', color: '#ffffff', border: 'none', borderRadius: 8, padding: '10px 28px', cursor: 'pointer', fontSize: '0.92rem', fontWeight: 600, boxShadow: '0 1px 3px rgba(79,70,229,0.25)' } as React.CSSProperties,
  msgOk:    { color: C.green, fontSize: '0.88rem', fontWeight: 500 },
  msgErr:   { color: C.red, fontSize: '0.88rem', fontWeight: 500 },
  msgWarn:  { color: C.yellow, fontSize: '0.88rem', fontWeight: 500 },
  infoBadge:{ display: 'flex', gap: 14, alignItems: 'center', marginBottom: 22, padding: '14px 18px', background: C.surface, border: `1px solid ${C.border}`, borderRadius: 12, fontSize: '0.92rem', boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  readonlyField:{ background: '#f1f5f9', border: `1px solid #e5e7eb`, borderRadius: 8, padding: '9px 12px', fontSize: '0.88rem', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', color: '#64748b', marginBottom: 14 },
};

function ConfigPanel({ detail, agentId }: { detail: AgentDetailView; agentId: string }) {
  const d = detail.definition;
  const [name,        setName]        = useState(d.name);
  const [desc,        setDesc]        = useState(d.description ?? '');
  const [sysPrompt,   setSysPrompt]   = useState(d.sysPrompt ?? '');
  const [model,       setModel]       = useState(d.model ?? '');
  const [maxIters,    setMaxIters]    = useState(d.maxIters != null ? String(d.maxIters) : '');
  const [identName,   setIdentName]   = useState(d.identityName ?? '');
  const [identEmoji,  setIdentEmoji]  = useState(d.identityEmoji ?? '');
  const [toolsAllow,  setToolsAllow]  = useState(arrToCsv(d.toolsAllow));
  const [toolsDeny,   setToolsDeny]   = useState(arrToCsv(d.toolsDeny));
  const [skillsAllow, setSkillsAllow] = useState(arrToCsv(d.skillsAllow));
  const [skillsDeny,  setSkillsDeny]  = useState(arrToCsv(d.skillsDeny));
  const [mentionPat,  setMentionPat]  = useState(arrToCsv(d.groupChatMentionPatterns));
  const [reqMention,  setReqMention]  = useState(d.groupChatRequireMention ?? false);
  const [sbxMode,     setSbxMode]     = useState(d.sandboxMode ?? '');
  const [sbxScope,    setSbxScope]    = useState(d.sandboxScope ?? '');
  const [saving,  setSaving]  = useState(false);
  const [message, setMessage] = useState<{ text: string; kind: 'ok' | 'warn' | 'err' } | null>(null);

  async function save() {
    setSaving(true); setMessage(null);
    const req: AgentUpdateRequest = {
      name,
      description: desc || undefined,
      sysPrompt: sysPrompt || undefined,
      model: model || undefined,
      maxIters: maxIters ? parseInt(maxIters, 10) : undefined,
      identityName: identName || undefined,
      identityEmoji: identEmoji || undefined,
      toolsAllow: csvToArr(toolsAllow),
      toolsDeny: csvToArr(toolsDeny),
      skillsAllow: csvToArr(skillsAllow),
      skillsDeny: csvToArr(skillsDeny),
      groupChatMentionPatterns: csvToArr(mentionPat),
      groupChatRequireMention: reqMention || undefined,
      sandboxMode: sbxMode || undefined,
      sandboxScope: sbxScope || undefined,
    };
    try {
      const r = await updateAgentConfig(agentId, req);
      setMessage({ text: r.restartRequired ? `Saved — restart required: ${r.message}` : 'Saved.', kind: r.restartRequired ? 'warn' : 'ok' });
    } catch (e: unknown) {
      setMessage({ text: e instanceof Error ? e.message : String(e), kind: 'err' });
    } finally {
      setSaving(false);
    }
  }

  return (
    <div style={{ overflowY: 'auto', flex: 1, minHeight: 0 }}>
      <div style={SF.wrap}>
        {/* Agent identity info bar */}
        <div style={SF.infoBadge}>
          {d.identityEmoji && <span style={{ fontSize: '1.8rem' }}>{d.identityEmoji}</span>}
          <div>
            <div style={{ fontWeight: 700, color: C.text, fontSize: '1rem' }}>{d.identityName ?? d.name}</div>
            <div style={{ fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.84rem', color: C.dimmed }}>{d.id}</div>
          </div>
          <div style={{ marginLeft: 'auto', display: 'flex', gap: 8, fontSize: '0.78rem' }}>
            {d.isMain && <span style={{ background: C.accentBg, color: C.accent, borderRadius: 999, padding: '3px 12px', fontWeight: 600, border: '1px solid #c7d2fe' }}>main</span>}
            {d.liveInGateway
              ? <span style={{ background: C.greenBg, color: '#15803d', borderRadius: 999, padding: '3px 12px', fontWeight: 600, border: '1px solid #86efac' }}>● live</span>
              : <span style={{ background: '#fffbeb', color: '#92400e', borderRadius: 999, padding: '3px 12px', border: '1px solid #fcd34d', fontWeight: 600 }}>○ pending restart</span>}
          </div>
        </div>

        {/* Workspace path (readonly) */}
        <div style={SF.card}>
          <div style={SF.cardTitle}>Workspace path</div>
          <div style={SF.readonlyField}>{d.workspacePath ?? '(resolving…)'}</div>
          <div style={{ fontSize: '0.82rem', color: d.workspaceExists ? C.green : C.yellow, fontWeight: 500 }}>
            {d.workspaceExists ? '● Directory exists' : '○ Workspace directory not yet initialised — open the Workspace tab to create it'}
          </div>
        </div>

        <div style={{ height: 20 }} />

        {/* Two-column form */}
        <div style={SF.grid}>
          {/* Left: Basic */}
          <div style={SF.card}>
            <div style={SF.cardTitle}>Basic</div>

            <label style={SF.label}>Agent ID (read-only)</label>
            <div style={SF.readonlyField}>{d.id}</div>

            <label style={SF.label}>Display Name *</label>
            <input style={SF.input} value={name} onChange={e => setName(e.target.value)} />

            <label style={SF.label}>Description</label>
            <input style={SF.input} value={desc} onChange={e => setDesc(e.target.value)} placeholder="One-line summary…" />

            <div style={SF.row2}>
              <div>
                <label style={SF.label}>Model</label>
                <input style={SF.input} value={model} onChange={e => setModel(e.target.value)} placeholder="qwen-max" />
              </div>
              <div>
                <label style={SF.label}>Max Iterations</label>
                <input style={SF.input} type="number" min={1} value={maxIters} onChange={e => setMaxIters(e.target.value)} placeholder="20" />
              </div>
            </div>

            <div style={SF.row2}>
              <div>
                <label style={SF.label}>Identity Name</label>
                <input style={SF.input} value={identName} onChange={e => setIdentName(e.target.value)} placeholder="Claude" />
              </div>
              <div>
                <label style={SF.label}>Emoji</label>
                <input style={SF.input} value={identEmoji} onChange={e => setIdentEmoji(e.target.value)} placeholder="🤖" />
              </div>
            </div>

            <label style={SF.label}>System Prompt</label>
            <textarea style={SF.textarea} value={sysPrompt} onChange={e => setSysPrompt(e.target.value)} placeholder="You are a helpful assistant…" spellCheck={false} />
          </div>

          {/* Right: Tools / Skills / GroupChat / Sandbox */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            <div style={SF.card}>
              <div style={SF.cardTitle}>Tools</div>
              <label style={SF.label}>Allow (comma-separated)</label>
              <input style={SF.input} value={toolsAllow} onChange={e => setToolsAllow(e.target.value)} placeholder="filesystem, shell_execute" />
              <div style={SF.hint}>Empty = all built-in tools allowed.</div>
              <label style={SF.label}>Deny</label>
              <input style={SF.input} value={toolsDeny} onChange={e => setToolsDeny(e.target.value)} placeholder="shell_execute" />
            </div>

            <div style={SF.card}>
              <div style={SF.cardTitle}>Skills policy</div>
              <label style={SF.label}>Allow</label>
              <input style={SF.input} value={skillsAllow} onChange={e => setSkillsAllow(e.target.value)} placeholder="search, todo" />
              <label style={SF.label}>Deny</label>
              <input style={SF.input} value={skillsDeny} onChange={e => setSkillsDeny(e.target.value)} />
            </div>

            <div style={SF.card}>
              <div style={SF.cardTitle}>Group Chat</div>
              <label style={SF.label}>Mention patterns (comma-separated)</label>
              <input style={SF.input} value={mentionPat} onChange={e => setMentionPat(e.target.value)} placeholder="@claude, claude:" />
              <div style={SF.checkRow}>
                <input type="checkbox" id="reqMention" checked={reqMention} onChange={e => setReqMention(e.target.checked)} />
                <label htmlFor="reqMention" style={{ ...SF.label, marginBottom: 0 }}>Require mention in group chats</label>
              </div>
            </div>

            <div style={SF.card}>
              <div style={SF.cardTitle}>Sandbox</div>
              <div style={SF.row2}>
                <div>
                  <label style={SF.label}>Mode</label>
                  <select style={SF.select} value={sbxMode} onChange={e => setSbxMode(e.target.value)}>
                    <option value="">(default)</option>
                    <option value="off">off</option>
                    <option value="prompt">prompt</option>
                    <option value="all">all</option>
                  </select>
                </div>
                <div>
                  <label style={SF.label}>Scope</label>
                  <select style={SF.select} value={sbxScope} onChange={e => setSbxScope(e.target.value)}>
                    <option value="">(default)</option>
                    <option value="agent">agent</option>
                    <option value="session">session</option>
                    <option value="shared">shared</option>
                  </select>
                </div>
              </div>
            </div>
          </div>
        </div>

        <div style={SF.btnRow}>
          <button style={SF.saveBtn} onClick={save} disabled={saving || !name}>
            {saving ? 'Saving…' : 'Save Config'}
          </button>
          {message && (
            <span style={message.kind === 'ok' ? SF.msgOk : message.kind === 'warn' ? SF.msgWarn : SF.msgErr}>
              {message.text}
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
//  ── WORKSPACE EXPLORER ────────────────────────────────────────────────────
// ---------------------------------------------------------------------------

type SelectedFile =
  | { kind: 'agents-md' }
  | { kind: 'skill'; name: string }
  | { kind: 'subagent'; name: string }
  | { kind: 'memory-main' }
  | { kind: 'memory-daily'; filename: string };

function selKey(f: SelectedFile): string {
  switch (f.kind) {
    case 'agents-md':     return '__agents-md__';
    case 'skill':         return `skill:${f.name}`;
    case 'subagent':      return `subagent:${f.name}`;
    case 'memory-main':   return '__memory-main__';
    case 'memory-daily':  return `memory:${f.filename}`;
  }
}

const SW: Record<string, React.CSSProperties> = {
  root:       { display: 'flex', flex: 1, minHeight: 0, overflow: 'hidden', background: '#ffffff' },
  tree:       { width: 260, flexShrink: 0, borderRight: `1px solid ${C.border}`, overflowY: 'auto' as const, display: 'flex', flexDirection: 'column' as const, background: '#fafbfc' },
  treeHeader: { padding: '14px 16px 8px', fontSize: '0.78rem', color: C.muted, textTransform: 'uppercase' as const, letterSpacing: '0.08em', fontWeight: 700, flexShrink: 0 },
  item:       { display: 'flex', alignItems: 'center', gap: 8, padding: '7px 12px 7px 0', cursor: 'pointer', fontSize: '0.9rem', color: C.text, userSelect: 'none' as const, borderRadius: 6, transition: 'background 0.1s', margin: '1px 4px' } as React.CSSProperties,
  itemActive: { background: '#eef2ff', color: C.accent, fontWeight: 500 },
  itemHover:  { background: '#f1f5f9' },
  folder:     { display: 'flex', alignItems: 'center', gap: 8, padding: '8px 12px 6px 0', fontSize: '0.86rem', color: '#475569', fontWeight: 600, userSelect: 'none' as const } as React.CSSProperties,
  addBtn:     { marginLeft: 'auto', background: 'transparent', border: 'none', color: C.accent, cursor: 'pointer', fontSize: '1.05rem', padding: '0 6px', lineHeight: 1, fontWeight: 600 } as React.CSSProperties,
  newInput:   { display: 'flex', alignItems: 'center', gap: 4, padding: '4px 12px 4px 38px' },
  nameInput:  { flex: 1, background: '#ffffff', border: `1px solid ${C.border}`, borderRadius: 6, color: C.text, padding: '4px 9px', fontSize: '0.85rem', outline: 'none', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace' },
  confirmBtn: { background: '#4f46e5', color: '#ffffff', border: 'none', borderRadius: 6, padding: '3px 10px', cursor: 'pointer', fontSize: '0.85rem' } as React.CSSProperties,
  cancelBtn:  { background: 'transparent', border: 'none', color: C.muted, cursor: 'pointer', fontSize: '0.85rem', padding: '3px 6px' } as React.CSSProperties,
  // Editor area
  editor:     { flex: 1, display: 'flex', flexDirection: 'column' as const, minHeight: 0, overflow: 'hidden' },
  edHead:     { display: 'flex', alignItems: 'center', gap: 12, padding: '10px 18px', borderBottom: `1px solid ${C.border}`, background: '#f8fafc', flexShrink: 0 },
  edPath:     { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.88rem', color: '#475569', flex: 1, fontWeight: 500 },
  edSaveBtn:  { background: '#4f46e5', color: '#ffffff', border: 'none', borderRadius: 6, padding: '6px 18px', cursor: 'pointer', fontSize: '0.86rem', fontWeight: 600, boxShadow: '0 1px 3px rgba(79,70,229,0.25)' } as React.CSSProperties,
  edDelBtn:   { background: '#ffffff', color: C.red, border: '1px solid #fecaca', borderRadius: 6, padding: '6px 14px', cursor: 'pointer', fontSize: '0.86rem', fontWeight: 500 } as React.CSSProperties,
  edStatus:   { fontSize: '0.82rem', color: C.dimmed },
  textarea:   { flex: 1, background: '#ffffff', color: C.text, border: 'none', outline: 'none', padding: '20px 22px', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.9rem', resize: 'none' as const, lineHeight: 1.7 } as React.CSSProperties,
  readonlyPre:{ flex: 1, background: '#fafbfc', color: C.text, padding: '20px 22px', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.9rem', overflowY: 'auto' as const, whiteSpace: 'pre-wrap' as const, lineHeight: 1.7, margin: 0 } as React.CSSProperties,
  empty:      { flex: 1, display: 'flex', alignItems: 'center', justifyContent: 'center', color: C.dimmed, fontSize: '0.92rem', flexDirection: 'column' as const, gap: 12 },
  loading:    { color: C.dimmed, padding: '1.25rem', fontSize: '0.88rem' },
  errBox:     { color: C.red, background: '#fef2f2', border: `1px solid #fecaca`, borderRadius: 10, padding: '10px 14px', margin: '10px 18px', fontSize: '0.88rem' },
  // Init banner
  initBanner: { display: 'flex', flexDirection: 'column' as const, alignItems: 'center', justifyContent: 'center', flex: 1, gap: 14, color: C.muted, textAlign: 'center' as const },
  initBtn:    { background: '#4f46e5', color: '#ffffff', border: 'none', borderRadius: 10, padding: '11px 28px', cursor: 'pointer', fontSize: '0.92rem', fontWeight: 600, marginTop: 10, boxShadow: '0 2px 6px rgba(79,70,229,0.25)' } as React.CSSProperties,
};

// Small tree item component
function TreeItem({
  label, icon, indent = 0, isActive, isReadonly,
  onClick, action,
}: {
  label: string; icon: string; indent?: number;
  isActive?: boolean; isReadonly?: boolean;
  onClick?: () => void; action?: React.ReactNode;
}) {
  const [hover, setHover] = useState(false);
  return (
    <div
      style={{
        ...SW.item,
        ...(isActive ? SW.itemActive : hover ? SW.itemHover : {}),
        paddingLeft: 10 + indent * 14,
      }}
      onClick={onClick}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
    >
      <span style={{ opacity: 0.85, fontSize: '1rem' }}>{icon}</span>
      <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{label}</span>
      {isReadonly && <span style={{ fontSize: '0.72rem', color: C.dimmed, marginRight: 6, fontWeight: 500 }}>ro</span>}
      {action}
    </div>
  );
}

function FolderRow({
  label, icon, isExpanded, onToggle, onAdd, indent = 0,
}: {
  label: string; icon: string; isExpanded: boolean;
  onToggle: () => void; onAdd?: () => void; indent?: number;
}) {
  return (
    <div style={{ ...SW.folder, paddingLeft: 12 + indent * 14 }}>
      <span style={{ cursor: 'pointer', color: '#94a3b8' }} onClick={onToggle}>{isExpanded ? '▾' : '▸'}</span>
      <span style={{ opacity: 0.85 }}>{icon}</span>
      <span style={{ cursor: 'pointer', flex: 1 }} onClick={onToggle}>{label}</span>
      {onAdd && (
        <button style={SW.addBtn} title="New item" onClick={e => { e.stopPropagation(); onAdd(); }}>＋</button>
      )}
    </div>
  );
}

export function WorkspaceExplorer({ agentId, agentName }: { agentId: string; agentName: string }) {
  const [skills,      setSkills]      = useState<WorkspaceSkillEntry[]>([]);
  const [subagents,   setSubagents]   = useState<WorkspaceSubagentEntry[]>([]);
  const [memView,     setMemView]     = useState<WorkspaceMemoryView | null>(null);
  const [wsExists,    setWsExists]    = useState<boolean | null>(null);
  const [treeLoading, setTreeLoading] = useState(true);
  const [scaffolding, setScaffolding] = useState(false);

  // Tree UI state
  const [expanded, setExpanded] = useState({ skills: true, subagents: true, memory: true });
  const [creating, setCreating] = useState<'skills' | 'subagents' | null>(null);
  const [newName,  setNewName]  = useState('');

  // Editor state
  const [selected,    setSelected]    = useState<SelectedFile | null>(null);
  const [fileContent, setFileContent] = useState('');
  const [savedContent,setSavedContent]= useState('');
  const [fileLoading, setFileLoading] = useState(false);
  const [saving,      setSaving]      = useState(false);
  const [editorErr,   setEditorErr]   = useState<string | null>(null);
  const [editorOk,    setEditorOk]    = useState<string | null>(null);

  const loadTree = useCallback(async () => {
    setTreeLoading(true);
    try {
      const [sk, sa, mv] = await Promise.all([
        listWorkspaceSkills(agentId).catch(() => []),
        listWorkspaceSubagents(agentId).catch(() => []),
        getWorkspaceMemory(agentId).catch(() => null),
      ]);
      setSkills(sk);
      setSubagents(sa);
      setMemView(mv);
      setWsExists(true);
    } catch {
      setWsExists(false);
    } finally {
      setTreeLoading(false);
    }
  }, [agentId]);

  useEffect(() => { loadTree(); }, [loadTree]);

  async function init() {
    setScaffolding(true);
    try {
      await scaffoldWorkspace(agentId, agentName);
      await loadTree();
    } catch (e: unknown) {
      setEditorErr(e instanceof Error ? e.message : String(e));
    } finally {
      setScaffolding(false);
    }
  }

  async function openFile(file: SelectedFile) {
    setSelected(file); setEditorErr(null); setEditorOk(null); setFileLoading(true);
    try {
      let content = '';
      if (file.kind === 'agents-md') {
        content = (await getAgentsMd(agentId)).content;
      } else if (file.kind === 'skill') {
        content = (await getWorkspaceSkill(agentId, file.name)).content;
      } else if (file.kind === 'subagent') {
        content = (await getWorkspaceSubagent(agentId, file.name)).content;
      } else if (file.kind === 'memory-main') {
        content = (await getWorkspaceMemory(agentId)).memoryMd ?? '';
      } else if (file.kind === 'memory-daily') {
        content = (await getWorkspaceDailyMemory(agentId, file.filename)).content;
      }
      setFileContent(content); setSavedContent(content);
    } catch (e: unknown) {
      setEditorErr(e instanceof Error ? e.message : String(e));
    } finally {
      setFileLoading(false);
    }
  }

  async function saveFile() {
    if (!selected) return;
    setSaving(true); setEditorErr(null); setEditorOk(null);
    try {
      if (selected.kind === 'agents-md') {
        await putAgentsMd(agentId, fileContent);
      } else if (selected.kind === 'skill') {
        await updateWorkspaceSkill(agentId, selected.name, fileContent);
      } else if (selected.kind === 'subagent') {
        await updateWorkspaceSubagent(agentId, selected.name, fileContent);
      }
      setSavedContent(fileContent);
      setEditorOk('Saved');
      setTimeout(() => setEditorOk(null), 2000);
    } catch (e: unknown) {
      setEditorErr(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  }

  async function deleteFile() {
    if (!selected) return;
    const label = selected.kind === 'skill' ? selected.name
      : selected.kind === 'subagent' ? selected.name : '';
    if (!confirm(`Delete "${label}"?`)) return;
    try {
      if (selected.kind === 'skill') await deleteWorkspaceSkill(agentId, selected.name);
      if (selected.kind === 'subagent') await deleteWorkspaceSubagent(agentId, selected.name);
      setSelected(null); setFileContent(''); setSavedContent('');
      await loadTree();
    } catch (e: unknown) {
      setEditorErr(e instanceof Error ? e.message : String(e));
    }
  }

  async function createItem() {
    const name = newName.trim();
    if (!name || !creating) return;
    try {
      if (creating === 'skills') {
        await createWorkspaceSkill(agentId, name);
        await loadTree();
        openFile({ kind: 'skill', name });
      } else {
        await createWorkspaceSubagent(agentId, name);
        await loadTree();
        openFile({ kind: 'subagent', name });
      }
      setCreating(null); setNewName('');
    } catch (e: unknown) {
      setEditorErr(e instanceof Error ? e.message : String(e));
    }
  }

  function toggleExpand(key: keyof typeof expanded) {
    setExpanded(e => ({ ...e, [key]: !e[key] }));
  }

  function startCreate(folder: 'skills' | 'subagents') {
    setCreating(folder);
    setNewName('');
    if (folder === 'skills') setExpanded(e => ({ ...e, skills: true }));
    else setExpanded(e => ({ ...e, subagents: true }));
  }

  // Determine editor header path string
  function editorPath(f: SelectedFile): string {
    switch (f.kind) {
      case 'agents-md':    return 'AGENTS.md';
      case 'skill':        return `skills/${f.name}/SKILL.md`;
      case 'subagent':     return `subagents/${f.name}.md`;
      case 'memory-main':  return 'MEMORY.md';
      case 'memory-daily': return `memory/${f.filename}`;
    }
  }

  const isReadonly = selected?.kind === 'memory-main' || selected?.kind === 'memory-daily';
  const isDirty = !isReadonly && fileContent !== savedContent;
  const canDelete = selected?.kind === 'skill' || selected?.kind === 'subagent';

  // ── Render ──────────────────────────────────────────────────────────────

  if (treeLoading) return <div style={SW.loading}>Loading workspace…</div>;

  if (wsExists === false) {
    return (
      <div style={SW.initBanner}>
        <div style={{ fontSize: '2.4rem' }}>📁</div>
        <div style={{ color: C.text, fontWeight: 600, fontSize: '1.1rem' }}>Workspace not yet initialised</div>
        <div style={{ maxWidth: 420, lineHeight: 1.7, fontSize: '0.92rem', color: C.muted }}>
          Click below to create the skeleton directories and seed an initial <code style={{ background: '#f1f5f9', padding: '1px 6px', borderRadius: 4, color: C.accent }}>AGENTS.md</code>.
        </div>
        <button style={SW.initBtn} disabled={scaffolding} onClick={init}>
          {scaffolding ? 'Creating…' : 'Initialise Workspace'}
        </button>
      </div>
    );
  }

  return (
    <div style={SW.root}>
      {/* ── Left tree ─────────────────────────────────────────── */}
      <div style={SW.tree}>
        <div style={SW.treeHeader}>Files</div>

        {/* AGENTS.md */}
        <TreeItem
          label="AGENTS.md"
          icon="📄"
          indent={0}
          isActive={selected?.kind === 'agents-md'}
          onClick={() => openFile({ kind: 'agents-md' })}
        />

        {/* skills/ */}
        <FolderRow
          label="skills/"
          icon="📂"
          isExpanded={expanded.skills}
          onToggle={() => toggleExpand('skills')}
          onAdd={() => startCreate('skills')}
        />
        {expanded.skills && (
          <>
            {skills.map(sk => (
              <TreeItem
                key={sk.name}
                label={sk.name}
                icon="🔧"
                indent={1}
                isActive={selected?.kind === 'skill' && selected.name === sk.name}
                onClick={() => openFile({ kind: 'skill', name: sk.name })}
              />
            ))}
            {creating === 'skills' && (
              <div style={SW.newInput}>
                <input
                  style={SW.nameInput}
                  placeholder="skill-name"
                  value={newName}
                  onChange={e => setNewName(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') createItem(); if (e.key === 'Escape') setCreating(null); }}
                  autoFocus
                />
                <button style={SW.confirmBtn} onClick={createItem}>✓</button>
                <button style={SW.cancelBtn} onClick={() => setCreating(null)}>✕</button>
              </div>
            )}
          </>
        )}

        {/* subagents/ */}
        <FolderRow
          label="subagents/"
          icon="📂"
          isExpanded={expanded.subagents}
          onToggle={() => toggleExpand('subagents')}
          onAdd={() => startCreate('subagents')}
        />
        {expanded.subagents && (
          <>
            {subagents.map(sa => (
              <TreeItem
                key={sa.name}
                label={sa.name + '.md'}
                icon="🤖"
                indent={1}
                isActive={selected?.kind === 'subagent' && selected.name === sa.name}
                onClick={() => openFile({ kind: 'subagent', name: sa.name })}
              />
            ))}
            {creating === 'subagents' && (
              <div style={SW.newInput}>
                <input
                  style={SW.nameInput}
                  placeholder="subagent-name"
                  value={newName}
                  onChange={e => setNewName(e.target.value)}
                  onKeyDown={e => { if (e.key === 'Enter') createItem(); if (e.key === 'Escape') setCreating(null); }}
                  autoFocus
                />
                <button style={SW.confirmBtn} onClick={createItem}>✓</button>
                <button style={SW.cancelBtn} onClick={() => setCreating(null)}>✕</button>
              </div>
            )}
          </>
        )}

        {/* memory/ */}
        <FolderRow
          label="memory/"
          icon="📂"
          isExpanded={expanded.memory}
          onToggle={() => toggleExpand('memory')}
        />
        {expanded.memory && memView && (
          <>
            {memView.memoryMd != null && (
              <TreeItem
                label="MEMORY.md"
                icon="🧠"
                indent={1}
                isReadonly
                isActive={selected?.kind === 'memory-main'}
                onClick={() => openFile({ kind: 'memory-main' })}
              />
            )}
            {memView.dailyFiles.map(f => (
              <TreeItem
                key={f.name}
                label={f.name}
                icon="📝"
                indent={1}
                isReadonly
                isActive={selected?.kind === 'memory-daily' && selected.filename === f.name}
                onClick={() => openFile({ kind: 'memory-daily', filename: f.name })}
              />
            ))}
          </>
        )}
      </div>

      {/* ── Right editor ──────────────────────────────────────── */}
      <div style={SW.editor}>
        {editorErr && <div style={SW.errBox}>{editorErr}</div>}

        {selected ? (
          <>
            {/* Editor header */}
            <div style={SW.edHead}>
              <span style={SW.edPath}>{editorPath(selected)}</span>
              {isDirty && <span style={{ fontSize: '0.82rem', color: C.yellow, fontWeight: 500 }}>●  Unsaved</span>}
              {editorOk && <span style={{ fontSize: '0.82rem', color: C.green, fontWeight: 500 }}>{editorOk}</span>}
              {canDelete && (
                <button style={SW.edDelBtn} onClick={deleteFile}>Delete</button>
              )}
              {!isReadonly && (
                <button style={SW.edSaveBtn} onClick={saveFile} disabled={saving || !isDirty}>
                  {saving ? 'Saving…' : 'Save'}
                </button>
              )}
            </div>

            {/* Content */}
            {fileLoading
              ? <div style={SW.loading}>Loading…</div>
              : isReadonly
                ? <pre style={SW.readonlyPre}>{fileContent || '(empty)'}</pre>
                : (
                  <textarea
                    style={SW.textarea}
                    value={fileContent}
                    onChange={e => setFileContent(e.target.value)}
                    spellCheck={false}
                  />
                )
            }
          </>
        ) : (
          <div style={SW.empty}>
            <span style={{ fontSize: '2.1rem', opacity: 0.4 }}>📄</span>
            <span>Select a file from the tree to view or edit it</span>
          </div>
        )}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
//  ── MAIN PAGE ─────────────────────────────────────────────────────────────
// ---------------------------------------------------------------------------

export default function AdminAgentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [searchParams] = useSearchParams();
  const [detail,  setDetail]  = useState<AgentDetailView | null>(null);
  const [loading, setLoading] = useState(true);
  const [error,   setError]   = useState<string | null>(null);
  const initialTab = (searchParams.get('tab') as PageTab | null) ?? 'config';
  const [pageTab, setPageTab] = useState<PageTab>(initialTab);

  async function load() {
    if (!id) return;
    setLoading(true); setError(null);
    try { setDetail(await getAgentDetail(id)); }
    catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setLoading(false); }
  }

  useEffect(() => { load(); }, [id]);

  const d = detail?.definition;

  const tabs: AdminTab[] = PAGE_TABS.map(t => {
    if (t.key === 'config' && detail) {
      return { ...t, badge: d?.liveInGateway ? 'live' : undefined };
    }
    if (t.key === 'workspace' && d) {
      return { ...t, badge: d.workspaceExists ? undefined : '!' };
    }
    return t;
  });

  const bannerRight = d && (
    <div style={{ display: 'flex', alignItems: 'center', gap: 12, fontSize: '0.88rem' }}>
      {d.identityEmoji && <span style={{ fontSize: '1.1rem' }}>{d.identityEmoji}</span>}
      <span style={{ color: C.text, fontWeight: 600 }}>{d.identityName ?? d.name}</span>
      <span style={{ color: C.dimmed, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.8rem' }}>{d.id}</span>
    </div>
  );

  return (
    <AppShell>
      <AdminPageLayout
        tabs={tabs}
        activeTab={pageTab}
        onTabChange={k => setPageTab(k as PageTab)}
        bannerRight={bannerRight}
      >
        {/* Back link */}
        <div style={{ padding: '12px 24px', borderBottom: `1px solid ${C.border}`, flexShrink: 0, display: 'flex', alignItems: 'center', background: '#ffffff' }}>
          <Link to="/admin/agents" style={{ color: C.muted, textDecoration: 'none', fontSize: '0.9rem', fontWeight: 500 }}>
            ← All Agents
          </Link>
        </div>

        {loading && (
          <div style={{ color: C.dimmed, padding: '2.5rem', textAlign: 'center', fontSize: '0.92rem' }}>Loading…</div>
        )}
        {error && (
          <div style={{ color: C.red, background: '#fef2f2', border: `1px solid #fecaca`, borderRadius: 10, padding: '12px 18px', margin: 20, fontSize: '0.92rem' }}>{error}</div>
        )}

        {detail && d && (
          <div style={{ display: 'flex', flexDirection: 'column', flex: 1, minHeight: 0 }}>
            {pageTab === 'config' && (
              <ConfigPanel detail={detail} agentId={d.id} />
            )}
            {pageTab === 'workspace' && (
              <WorkspaceExplorer agentId={d.id} agentName={d.name} />
            )}
          </div>
        )}
      </AdminPageLayout>
    </AppShell>
  );
}
