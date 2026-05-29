import React, { useEffect, useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import AdminPageLayout from '../../components/admin/AdminPageLayout';
import { getChannelDetail, ChannelDetailView } from '../../api/admin';

const S: Record<string, React.CSSProperties> = {
  page:    { maxWidth: 1200 },
  back:    { color: '#64748b', textDecoration: 'none', fontSize: '0.88rem', marginBottom: 18, display: 'inline-block', fontWeight: 500 },
  titleRow:{ display: 'flex', alignItems: 'center', gap: 16, marginBottom: 22 },
  title:   { margin: 0, fontSize: '1.6rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' },
  subtitle:{ fontSize: '0.88rem', color: '#64748b', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', marginTop: 4 },
  layout:  { display: 'grid', gridTemplateColumns: '340px 1fr', gap: 28, alignItems: 'flex-start' },
  sideCard:{ background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14, padding: '1.25rem 1.5rem', boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  sideTitle:{ color: '#4f46e5', fontSize: '0.82rem', textTransform: 'uppercase' as const, letterSpacing: '0.06em', fontWeight: 700, marginBottom: 16 },
  kvRow:   { fontSize: '0.9rem', display: 'flex', flexDirection: 'column' as const, gap: 4, marginBottom: 14 },
  k:       { color: '#94a3b8', fontSize: '0.78rem', textTransform: 'uppercase' as const, letterSpacing: '0.04em', fontWeight: 600 },
  v:       { color: '#0f172a', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.9rem' },
  card:    { background: '#ffffff', border: '1px solid #e5e7eb', borderRadius: 14, padding: '1.25rem 1.5rem', marginBottom: 20, boxShadow: '0 1px 3px rgba(15,23,42,0.04)' },
  sectionTitle: { color: '#4f46e5', fontSize: '0.82rem', textTransform: 'uppercase' as const, letterSpacing: '0.06em', fontWeight: 700, marginBottom: 16 },
  topoBox: { background: '#f8fafc', border: '1px dashed #cbd5e1', borderRadius: 10, padding: '1.25rem', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.88rem', color: '#334155', whiteSpace: 'pre-wrap' as const, overflow: 'auto' as const, lineHeight: 1.7 },
  table:   { width: '100%', borderCollapse: 'collapse' as const, fontSize: '0.88rem' },
  th:      { textAlign: 'left' as const, padding: '10px 14px', background: '#f8fafc', color: '#64748b', borderBottom: '1px solid #e5e7eb', fontWeight: 600, fontSize: '0.78rem', textTransform: 'uppercase' as const, letterSpacing: '0.04em' },
  td:      { padding: '10px 14px', borderBottom: '1px solid #f1f5f9', color: '#334155' },
  mono:    { fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.84rem' },
  link:    { color: '#4f46e5', textDecoration: 'none', borderBottom: '1px dashed #4f46e5' },
  tag:     { background: '#eef2ff', color: '#4338ca', borderRadius: 999, padding: '2px 10px', fontSize: '0.8rem', marginRight: 6, fontWeight: 500, display: 'inline-block', marginBottom: 4 },
  badgeBase:{ display: 'inline-block', padding: '3px 10px', borderRadius: 999, fontSize: '0.76rem', marginRight: 6, fontWeight: 500 } as React.CSSProperties,
  loading: { color: '#94a3b8', padding: '2rem', textAlign: 'center' as const, fontSize: '0.92rem' },
  err:     { color: '#dc2626', background: '#fef2f2', border: '1px solid #fecaca', borderRadius: 10, padding: '14px 18px', marginBottom: 18, fontSize: '0.92rem' },
  empty:   { color: '#94a3b8', fontSize: '0.92rem' },
};

function fmtAgo(ms: number): string {
  const s = Math.floor(ms / 1000);
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

/** Build a small ASCII routing diagram showing channel → agents flow. */
function buildTopology(detail: ChannelDetailView): string {
  const { channel, agents } = detail;
  const def = channel.defaultAgentId;
  const bindings = channel.bindings;

  const lines: string[] = [];
  lines.push(`┌─ Channel: ${channel.channelId}  [${channel.dmScope}]`);
  lines.push(`│`);

  if (def) {
    lines.push(`├─▶ (default) ${def}`);
  }

  bindings.forEach((b, i) => {
    const cond = [
      b.peerId   && `peer=${b.peerId}`,
      b.guildId  && `guild=${b.guildId}`,
      b.roomId   && `channel=${b.roomId}`,
    ].filter(Boolean).join(' · ') || '(any)';
    const prefix = i === bindings.length - 1 && !agents.length ? '└' : '├';
    lines.push(`${prefix}─▶ [binding #${i}] ${cond}  →  ${b.agentId}${b.sessionScope ? ` (${b.sessionScope})` : ''}`);
  });

  if (!def && bindings.length === 0) {
    lines.push(`└─▶ (no routing rules — falls back to global default)`);
  }

  return lines.join('\n');
}

export default function AdminChannelDetailPage() {
  const { id } = useParams<{ id: string }>();
  const [detail,  setDetail]  = useState<ChannelDetailView | null>(null);
  const [error,   setError]   = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  async function load() {
    if (!id) return;
    setLoading(true); setError(null);
    try { setDetail(await getChannelDetail(id)); }
    catch (e: unknown) { setError(e instanceof Error ? e.message : String(e)); }
    finally { setLoading(false); }
  }

  useEffect(() => { load(); }, [id]);

  const c = detail?.channel;

  return (
    <>
      <AdminPageLayout>
        <div style={S.page}>
          <Link to="/admin/channels" style={S.back}>← Back to Channels</Link>

          {loading && <div style={S.loading}>Loading…</div>}
          {error && <div style={S.err}>{error}</div>}

          {detail && c && (
            <>
              <div style={S.titleRow}>
                <div>
                  <h2 style={S.title}>{c.channelId}</h2>
                  <div style={S.subtitle}>{c.dmScope} · {c.bindingCount} binding{c.bindingCount !== 1 ? 's' : ''}</div>
                </div>
                <div style={{ marginLeft: 'auto' }}>
                  <span style={{ ...S.badgeBase, background: c.started ? '#dcfce7' : '#fee2e2', color: c.started ? '#15803d' : '#b91c1c', border: c.started ? '1px solid #86efac' : '1px solid #fecaca' }}>
                    {c.started ? '● running' : '○ stopped'}
                  </span>
                </div>
              </div>

              <div style={S.layout}>
                {/* Left: channel summary */}
                <div style={S.sideCard}>
                  <div style={S.sideTitle}>Channel</div>

                  <div style={S.kvRow}>
                    <div style={S.k}>Channel ID</div>
                    <div style={S.v}>{c.channelId}</div>
                  </div>

                  <div style={S.kvRow}>
                    <div style={S.k}>DM Scope</div>
                    <div style={S.v}>{c.dmScope}</div>
                  </div>

                  <div style={S.kvRow}>
                    <div style={S.k}>Default Agent</div>
                    <div style={S.v}>{c.defaultAgentId ?? '—'}</div>
                  </div>

                  <div style={S.kvRow}>
                    <div style={S.k}>Bindings</div>
                    <div style={S.v}>{c.bindingCount}</div>
                  </div>

                  <div style={S.kvRow}>
                    <div style={S.k}>Outbound queue</div>
                    <div style={S.v}>{c.outboundQueueSize}</div>
                  </div>

                  <div style={S.kvRow}>
                    <div style={S.k}>Reachable agents</div>
                    <div>
                      {detail.agents.length === 0
                        ? <span style={{ color: '#94a3b8' }}>—</span>
                        : detail.agents.map(a => (
                            <Link key={a} to={`/admin/agents/${encodeURIComponent(a)}`} style={{ ...S.link, ...S.mono, marginRight: 8, display: 'inline-block', marginBottom: 4 }}>
                              {a}
                            </Link>
                          ))}
                    </div>
                  </div>
                </div>

                {/* Right: topology + sessions */}
                <div>
                  <div style={S.card}>
                    <div style={S.sectionTitle}>Routing Topology</div>
                    <div style={S.topoBox}>{buildTopology(detail)}</div>
                  </div>

                  <div style={S.card}>
                    <div style={S.sectionTitle}>
                      Recent Sessions ({detail.sessions.length})
                    </div>
                    {detail.sessions.length === 0
                      ? <div style={S.empty}>No sessions yet for this channel.</div>
                      : (
                        <table style={S.table}>
                          <thead>
                            <tr>
                              <th style={S.th}>Session</th>
                              <th style={S.th}>Agent</th>
                              <th style={S.th}>User</th>
                              <th style={S.th}>Last activity</th>
                            </tr>
                          </thead>
                          <tbody>
                            {detail.sessions.map(s => (
                              <tr key={s.sessionKey}>
                                <td style={{ ...S.td, ...S.mono }}>{s.sessionKey}</td>
                                <td style={S.td}>
                                  <Link to={`/admin/agents/${encodeURIComponent(s.agentId)}`} style={S.link}>{s.agentId}</Link>
                                </td>
                                <td style={S.td}>{s.userId ?? '—'}</td>
                                <td style={S.td}>{fmtAgo(s.idleMs)}</td>
                              </tr>
                            ))}
                          </tbody>
                        </table>
                      )}
                  </div>

                  <div style={S.card}>
                    <div style={S.sectionTitle}>Users ({detail.users.length})</div>
                    {detail.users.length === 0
                      ? <div style={S.empty}>No users active on this channel.</div>
                      : detail.users.map(u => <span key={u} style={{ ...S.mono, ...S.tag }}>{u}</span>)}
                  </div>
                </div>
              </div>
            </>
          )}
        </div>
      </AdminPageLayout>
    </>
  );
}
