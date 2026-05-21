import React, { useEffect, useRef, useState } from 'react';
import AppShell from '../components/AppShell';
import AdminPageLayout from '../components/AdminPageLayout';
import { getDebugInfo, DebugInfo, openLogStream } from '../api/admin';

const S: Record<string, React.CSSProperties> = {
  cards: { display: 'flex', gap: 18, flexWrap: 'wrap', marginBottom: '2rem', padding: '24px 32px 0' },
  card: {
    background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14,
    padding: '1.25rem 1.5rem', flex: '1 1 220px', boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  cardLabel: { fontSize: '0.78rem', color: '#64748b', marginBottom: 8, textTransform: 'uppercase', letterSpacing: '0.06em', fontWeight: 600 },
  cardValue: { fontSize: '1.05rem', color: '#0f172a', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontWeight: 500 },
  logHeader: { display: 'flex', alignItems: 'center', gap: 12, marginBottom: 12, padding: '0 32px' },
  sectionTitle: { fontSize: '1.05rem', fontWeight: 600, color: '#0f172a' },
  logBox: {
    background: '#0f172a', border: '1px solid #1e293b', borderRadius: 10,
    padding: '1rem 1.25rem', height: 520, overflowY: 'auto',
    fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.84rem', color: '#cbd5e1',
    lineHeight: 1.7, whiteSpace: 'pre-wrap', wordBreak: 'break-all',
    margin: '0 32px',
    boxShadow: 'inset 0 1px 0 rgba(255,255,255,0.04)',
  },
  streamBtn: { borderRadius: 8, padding: '6px 16px', cursor: 'pointer', fontSize: '0.88rem', marginLeft: 'auto', fontWeight: 500 },
  clearBtn:  { background: '#ffffff', border: '1px solid #d1d5db', color: '#475569', borderRadius: 8, padding: '6px 14px', cursor: 'pointer', fontSize: '0.86rem', fontWeight: 500 },
  refreshBtn:{ background: '#ffffff', border: '1px solid #d1d5db', color: '#475569', borderRadius: 8, padding: '6px 14px', cursor: 'pointer', fontSize: '0.86rem', fontWeight: 500 },
  okDot:  { width: 8, height: 8, borderRadius: '50%', background: '#16a34a', display: 'inline-block', marginRight: 6, boxShadow: '0 0 0 3px rgba(22,163,74,0.15)' },
  errDot: { width: 8, height: 8, borderRadius: '50%', background: '#dc2626', display: 'inline-block', marginRight: 6, boxShadow: '0 0 0 3px rgba(220,38,38,0.15)' },
  err: { color: '#dc2626', fontSize: '0.92rem', padding: '14px 18px', background: '#fef2f2', borderRadius: 10, border: '1px solid #fecaca', margin: '0 32px 20px' },
};

function logLineColor(line: string): React.CSSProperties {
  const l = line.toLowerCase();
  if (l.includes(' error ') || l.includes(' error-')) return { color: '#fca5a5' };
  if (l.includes(' warn ')) return { color: '#fcd34d' };
  if (l.includes(' info ')) return { color: '#93c5fd' };
  return {};
}

const DEBUG_TABS = [
  { key: 'info', label: 'System Info', icon: '📋' },
  { key: 'logs', label: 'Live Logs',   icon: '📜' },
];

export default function DebugPage() {
  const [tab,       setTab]       = useState<'info' | 'logs'>('info');
  const [info,      setInfo]      = useState<DebugInfo | null>(null);
  const [infoErr,   setInfoErr]   = useState<string | null>(null);
  const [logs,      setLogs]      = useState<string[]>([]);
  const [streaming, setStreaming] = useState(false);
  const cancelRef  = useRef<(() => void) | null>(null);
  const logBoxRef  = useRef<HTMLDivElement>(null);
  const [autoScroll, setAutoScroll] = useState(true);

  async function loadInfo() {
    setInfoErr(null);
    try { setInfo(await getDebugInfo()); }
    catch (e) { setInfoErr(String(e)); }
  }

  function startStream() {
    if (cancelRef.current) return;
    setStreaming(true);
    cancelRef.current = openLogStream(
      (line) => setLogs(prev => [...prev.slice(-2000), line]),
      () => { setStreaming(false); cancelRef.current = null; },
    );
  }

  function stopStream() {
    cancelRef.current?.();
    cancelRef.current = null;
    setStreaming(false);
  }

  function toggleStream() {
    if (streaming) stopStream(); else startStream();
  }

  useEffect(() => {
    loadInfo();
    startStream();
    return () => stopStream();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (autoScroll && logBoxRef.current) {
      logBoxRef.current.scrollTop = logBoxRef.current.scrollHeight;
    }
  }, [logs, autoScroll]);

  function handleScroll() {
    const el = logBoxRef.current;
    if (!el) return;
    setAutoScroll(el.scrollHeight - el.scrollTop - el.clientHeight < 60);
  }

  const streamingBadge = streaming
    ? <span style={{ fontSize: '0.78rem', color: '#16a34a', marginLeft: 4, fontWeight: 500 }}>● live</span>
    : undefined;

  const tabs = DEBUG_TABS.map(t =>
    t.key === 'logs' ? { ...t, badge: streamingBadge ? '●' : undefined } : t
  );

  return (
    <AppShell>
      <AdminPageLayout
        fullWidth
        tabs={tabs}
        activeTab={tab}
        onTabChange={k => setTab(k as 'info' | 'logs')}
        bannerRight={
          tab === 'info'
            ? <button style={S.refreshBtn} onClick={loadInfo}>↺ Refresh</button>
            : undefined
        }
      >
        <div style={{ padding: '24px 0', maxWidth: 1200 }}>
          {/* ── System Info ──────────────────────────────────────── */}
          {tab === 'info' && (
            <>
              {infoErr && <div style={S.err}>{infoErr}</div>}
              {info ? (
                <div style={S.cards}>
                  <div style={S.card}>
                    <div style={S.cardLabel}>Application</div>
                    <div style={S.cardValue}>{info.application}</div>
                  </div>
                  <div style={S.card}>
                    <div style={S.cardLabel}>Java Version</div>
                    <div style={S.cardValue}>{info.javaVersion}</div>
                  </div>
                  <div style={S.card}>
                    <div style={S.cardLabel}>OS</div>
                    <div style={S.cardValue}>{info.osName}</div>
                  </div>
                  <div style={S.card}>
                    <div style={S.cardLabel}>Model</div>
                    <div style={S.cardValue}>{info.modelName}</div>
                  </div>
                  <div style={S.card}>
                    <div style={S.cardLabel}>API Key</div>
                    <div style={S.cardValue}>
                      {info.apiKeyConfigured
                        ? <><span style={S.okDot} />Configured</>
                        : <><span style={S.errDot} />Not set</>}
                    </div>
                  </div>
                  <div style={S.card}>
                    <div style={S.cardLabel}>Log Appender</div>
                    <div style={S.cardValue}>
                      {info.logAppenderAttached
                        ? <><span style={S.okDot} />Attached</>
                        : <><span style={S.errDot} />Not attached</>}
                    </div>
                  </div>
                </div>
              ) : (
                !infoErr && <p style={{ color: '#94a3b8', padding: '0 32px' }}>Loading…</p>
              )}
            </>
          )}

          {/* ── Live Logs ────────────────────────────────────────── */}
          {tab === 'logs' && (
            <>
              <div style={S.logHeader}>
                <button style={S.clearBtn} onClick={() => setLogs([])}>Clear</button>
                <button
                  style={{
                    ...S.streamBtn,
                    background: streaming ? '#dcfce7' : '#ffffff',
                    border: `1px solid ${streaming ? '#86efac' : '#d1d5db'}`,
                    color: streaming ? '#15803d' : '#475569',
                  }}
                  onClick={toggleStream}
                >
                  {streaming ? '⏹ Stop' : '▶ Start'}
                </button>
              </div>
              <div ref={logBoxRef} style={S.logBox} onScroll={handleScroll}>
                {logs.length === 0 && <span style={{ color: '#64748b' }}>(waiting for log output…)</span>}
                {logs.map((line, i) => (
                  <div key={i} style={logLineColor(line)}>{line}</div>
                ))}
              </div>
              <div style={{ marginTop: 10, fontSize: '0.82rem', color: '#94a3b8', padding: '0 32px' }}>
                {autoScroll ? 'Auto-scroll: on (scroll up to pause)' : 'Auto-scroll: off (scroll to bottom to resume)'}
                {' · '}{logs.length} lines
              </div>
            </>
          )}
        </div>
      </AdminPageLayout>
    </AppShell>
  );
}
