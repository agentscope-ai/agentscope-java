import React, { useEffect, useState } from 'react';
import { listEvents, SessionEvent } from '../api/managedSessions';

const S: Record<string, React.CSSProperties> = {
  root: { marginTop: 28, paddingTop: 24, borderTop: '1px solid #e2e8f0' },
  title: { fontSize: '1rem', fontWeight: 700, color: '#0f172a', margin: '0 0 14px' },
  event: {
    padding: '10px 14px', borderRadius: 8, marginBottom: 8,
    background: '#f8fafc', border: '1px solid #e2e8f0', fontSize: '0.85rem',
  },
  type: {
    fontFamily: 'ui-monospace, monospace', fontSize: '0.78rem', fontWeight: 600,
    color: '#6366f1', marginBottom: 4,
  },
  payload: {
    fontFamily: 'ui-monospace, monospace', fontSize: '0.76rem',
    color: '#64748b', whiteSpace: 'pre-wrap', wordBreak: 'break-word',
  },
  time: { fontSize: '0.72rem', color: '#94a3b8', marginTop: 4 },
  err: { color: '#dc2626', fontSize: '0.88rem' },
};

function eventColor(type: string): string {
  if (type.startsWith('user.')) return '#eef2ff';
  if (type.startsWith('agent.')) return '#ecfdf5';
  if (type.includes('requires_action')) return '#fef3c7';
  if (type.startsWith('session.status')) return '#f1f5f9';
  return '#f8fafc';
}

export default function SessionEventTimeline({ managedSessionId }: { managedSessionId: string }) {
  const [events, setEvents] = useState<SessionEvent[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    setLoading(true);
    setErr(null);
    listEvents(managedSessionId)
      .then(list => { if (!cancelled) setEvents(list); })
      .catch(e => { if (!cancelled) setErr(e instanceof Error ? e.message : 'Failed'); })
      .finally(() => { if (!cancelled) setLoading(false); });
    return () => { cancelled = true; };
  }, [managedSessionId]);

  return (
    <div style={S.root}>
      <h3 style={S.title}>Managed session event timeline</h3>
      <div style={{ fontSize: '0.78rem', color: '#94a3b8', fontFamily: 'monospace', marginBottom: 14 }}>
        {managedSessionId}
      </div>
      {loading && <div style={{ color: '#94a3b8' }}>Loading events…</div>}
      {err && <div style={S.err}>{err}</div>}
      {!loading && !err && events.length === 0 && (
        <div style={{ color: '#94a3b8', fontStyle: 'italic' }}>No events recorded.</div>
      )}
      {events.map(evt => (
        <div key={evt.id} style={{ ...S.event, background: eventColor(evt.type) }}>
          <div style={S.type}>#{evt.seq} {evt.type}</div>
          {evt.payload && Object.keys(evt.payload).length > 0 && (
            <div style={S.payload}>{JSON.stringify(evt.payload, null, 2)}</div>
          )}
          <div style={S.time}>{new Date(evt.createdAt).toLocaleString()}</div>
        </div>
      ))}
    </div>
  );
}
