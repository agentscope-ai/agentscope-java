import React, { useEffect, useRef, useState } from 'react';
import AppShell from '../components/AppShell';
import AdminPageLayout from '../components/AdminPageLayout';
import { getDebugInfo, DebugInfo, openLogStream } from '../api/admin';

const S: Record<string, React.CSSProperties> = {
  cards: { display: 'flex', gap: 12, flexWrap: 'wrap', marginBottom: '1.5rem' },
  card: {
    background: '#13151f', border: '1px solid #1e2235', borderRadius: 10,
    padding: '1rem 1.25rem', flex: '1 1 200px',
  },
  cardLabel: { fontSize: '0.72rem', color: '#374056', marginBottom: 5, textTransform: 'uppercase', letterSpacing: '0.05em' },
  cardValue: { fontSize: '0.95rem', color: '#94a3b8', fontFamily: 'monospace' },
  logHeader: { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 8 },
  sectionTitle: { fontSize: '1rem', fontWeight: 600, color: '#94a3b8' },
  logBox: {
    background: '#0a0b10', border: '1px solid #1e2235', borderRadius: 8,
    padding: '0.75rem 1rem', height: 480, overflowY: 'auto',
    fontFamily: 'monospace', fontSize: '0.76rem', color: '#4b5571',
    lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-all',
  },
  streamBtn: { borderRadius: 6, padding: '4px 12px', cursor: 'pointer', fontSize: '0.8rem', marginLeft: 'auto' },
  clearBtn:  { background: 'transparent', border: '1px solid #2d3148', color: '#7c8bad', borderRadius: 6, padding: '4px 10px', cursor: 'pointer', fontSize: '0.78rem' },
  refreshBtn:{ background: 'transparent', border: '1px solid #2d3148', color: '#7c8bad', borderRadius: 6, padding: '4px 12px', cursor: 'pointer', fontSize: '0.8rem' },
  okDot:  { width: 8, height: 8, borderRadius: '50%', background: '#4ade80', display: 'inline-block', marginRight: 4 },
  errDot: { width: 8, height: 8, borderRadius: '50%', background: '#f87171', display: 'inline-block', marginRight: 4 },
  err: { color: '#f87171', fontSize: '0.85rem', padding: '1rem', background: '#1f1520', borderRadius: 8, border: '1px solid #5b2030' },
};

function logLineColor(line: string): React.CSSProperties {
  const l = line.toLowerCase();
  if (l.includes(' error ') || l.includes(' error-')) return { color: '#f87171' };
  if (l.includes(' warn ')) return { color: '#fbbf24' };
  if (l.includes(' info ')) return { color: '#60a5fa' };
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
    ? <span style={{ fontSize: '0.68rem', color: '#4ade80', marginLeft: 4 }}>● live</span>
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
        <div style={{ padding: '0 0', maxWidth: 1100 }}>
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
                !infoErr && <p style={{ color: '#4b5280' }}>Loading…</p>
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
                    background: streaming ? '#14532d' : '#1e2235',
                    border: `1px solid ${streaming ? '#166534' : '#2d3148'}`,
                    color: streaming ? '#4ade80' : '#7c8bad',
                  }}
                  onClick={toggleStream}
                >
                  {streaming ? '⏹ Stop' : '▶ Start'}
                </button>
              </div>
              <div ref={logBoxRef} style={S.logBox} onScroll={handleScroll}>
                {logs.length === 0 && <span style={{ color: '#1e2235' }}>(waiting for log output…)</span>}
                {logs.map((line, i) => (
                  <div key={i} style={logLineColor(line)}>{line}</div>
                ))}
              </div>
              <div style={{ marginTop: 6, fontSize: '0.72rem', color: '#374056' }}>
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
