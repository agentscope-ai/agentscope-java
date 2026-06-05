import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import { ActivityEvent, listActivity } from '../api/activity';

const RTF = new Intl.RelativeTimeFormat(undefined, { numeric: 'auto' });

const ACTION_META: Record<string, { icon: string; verb: string; tone: string }> = {
  CREATE:          { icon: '✨', verb: 'created the agent',          tone: '#6366f1' },
  EDIT_SETTINGS:   { icon: '⚙️', verb: 'updated settings',            tone: '#0891b2' },
  DELETE_AGENT:    { icon: '🗑️', verb: 'deleted the agent',           tone: '#dc2626' },
  CREATE_FILE:     { icon: '📄', verb: 'created file',                tone: '#0891b2' },
  EDIT_FILE:       { icon: '✏️', verb: 'edited file',                 tone: '#0891b2' },
  DELETE_FILE:     { icon: '🗑️', verb: 'deleted file',                tone: '#dc2626' },
  RENAME_FILE:     { icon: '🔀', verb: 'renamed file',                tone: '#0891b2' },
  UPLOAD_FILE:     { icon: '⬆️', verb: 'uploaded file',               tone: '#0891b2' },
  GRANT_SHARE:     { icon: '🤝', verb: 'granted access',              tone: '#15803d' },
  REVOKE_SHARE:    { icon: '🚫', verb: 'revoked access',              tone: '#dc2626' },
  CLONE_FROM:      { icon: '🔁', verb: 'cloned from this agent',      tone: '#a16207' },
  CLONE_TO:        { icon: '🔁', verb: 'cloned this agent',           tone: '#a16207' },
  BIND_CHANNEL:    { icon: '📡', verb: 'bound channel',               tone: '#0891b2' },
  UNBIND_CHANNEL:  { icon: '📴', verb: 'unbound channel',             tone: '#dc2626' },
  EDIT_BINDING:    { icon: '📡', verb: 'edited channel binding',      tone: '#0891b2' },
  RUN_SESSION:     { icon: '▶️', verb: 'started a chat session',      tone: '#6366f1' },
};

const REDACTED_ACTIONS = new Set([
  'GRANT_SHARE', 'REVOKE_SHARE', 'BIND_CHANNEL', 'UNBIND_CHANNEL', 'EDIT_BINDING',
]);

function metaFor(action: string) {
  return ACTION_META[action] ?? { icon: '•', verb: action.toLowerCase().replace(/_/g, ' '), tone: '#64748b' };
}

function formatRelative(ts: number): string {
  const deltaSec = (ts - Date.now()) / 1000;
  const abs = Math.abs(deltaSec);
  if (abs < 60) return RTF.format(Math.round(deltaSec), 'second');
  if (abs < 3600) return RTF.format(Math.round(deltaSec / 60), 'minute');
  if (abs < 86400) return RTF.format(Math.round(deltaSec / 3600), 'hour');
  if (abs < 86400 * 30) return RTF.format(Math.round(deltaSec / 86400), 'day');
  if (abs < 86400 * 365) return RTF.format(Math.round(deltaSec / (86400 * 30)), 'month');
  return RTF.format(Math.round(deltaSec / (86400 * 365)), 'year');
}

function actorInitial(ev: ActivityEvent): string {
  const name = ev.actorUsername || ev.actorUserId || '?';
  return name.charAt(0).toUpperCase();
}

function summarize(ev: ActivityEvent): { primary: string; secondary: string | null } {
  const m = metaFor(ev.action);
  const redacted = REDACTED_ACTIONS.has(ev.action) && !ev.target;
  if (redacted) {
    if (ev.action === 'GRANT_SHARE' || ev.action === 'REVOKE_SHARE') {
      return { primary: 'Owner updated sharing', secondary: null };
    }
    return { primary: 'Owner updated channel binding', secondary: null };
  }
  let secondary: string | null = ev.target ?? null;
  if (ev.action === 'GRANT_SHARE' && ev.metadata && typeof ev.metadata.tier === 'string') {
    secondary = `${ev.target ?? ''} · ${ev.metadata.tier}`;
  }
  if ((ev.action === 'CLONE_FROM' || ev.action === 'CLONE_TO') && ev.metadata && typeof ev.metadata.files === 'number') {
    secondary = `${ev.target ?? ''} · ${ev.metadata.files} file(s)`;
  }
  return { primary: m.verb, secondary };
}

export default function AgentActivityPage() {
  const { agentId } = useOutletContext<{ agentId: string }>();
  const [events, setEvents] = useState<ActivityEvent[]>([]);
  const [err, setErr] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const focusedRef = useRef(true);

  const refresh = useCallback(async () => {
    try {
      const list = await listActivity(agentId, { limit: 100 });
      setEvents(list);
      setErr(null);
    } catch (e) {
      setErr((e as Error).message);
    } finally {
      setLoading(false);
    }
  }, [agentId]);

  useEffect(() => {
    setLoading(true);
    refresh();
  }, [refresh]);

  useEffect(() => {
    const onVis = () => { focusedRef.current = !document.hidden; };
    document.addEventListener('visibilitychange', onVis);
    const id = window.setInterval(() => {
      if (focusedRef.current) refresh();
    }, 15000);
    return () => {
      document.removeEventListener('visibilitychange', onVis);
      window.clearInterval(id);
    };
  }, [refresh]);

  return (
    <div style={{ padding: '32px 44px', maxWidth: 880, margin: '0 auto' }}>
      <div style={{ display: 'flex', alignItems: 'baseline', justifyContent: 'space-between', marginBottom: 22 }}>
        <h2 style={{ margin: 0, fontSize: '1.25rem', color: '#0f172a', letterSpacing: '-0.01em' }}>Activity</h2>
        <span style={{ fontSize: '0.78rem', color: '#94a3b8' }}>auto-refresh · 15s</span>
      </div>

      {err && (
        <div style={{
          padding: '10px 14px', borderRadius: 8, background: '#fef2f2',
          border: '1px solid #fecaca', color: '#b91c1c', fontSize: '0.85rem', marginBottom: 18,
        }}>
          {err}
        </div>
      )}

      {!loading && events.length === 0 && !err && (
        <div style={{
          padding: '40px 24px', textAlign: 'center', color: '#94a3b8',
          background: '#ffffff', borderRadius: 12, border: '1px dashed #e2e8f0',
        }}>
          No activity recorded yet.
        </div>
      )}

      <ol style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 0 }}>
        {events.map((ev, idx) => {
          const meta = metaFor(ev.action);
          const { primary, secondary } = summarize(ev);
          const last = idx === events.length - 1;
          return (
            <li key={ev.id} style={{ display: 'flex', gap: 14, position: 'relative', paddingBottom: last ? 0 : 18 }}>
              <div style={{ position: 'relative', flexShrink: 0, width: 40, display: 'flex', justifyContent: 'center' }}>
                <div style={{
                  width: 36, height: 36, borderRadius: '50%',
                  background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
                  color: '#ffffff', display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: '0.9rem', fontWeight: 700,
                  boxShadow: '0 1px 3px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
                }}>
                  {actorInitial(ev)}
                </div>
                {!last && (
                  <span style={{
                    position: 'absolute', top: 38, bottom: -18, width: 2,
                    background: '#e2e8f0',
                  }} />
                )}
              </div>
              <div style={{
                flex: 1,
                background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 10,
                padding: '12px 16px',
                boxShadow: '0 1px 2px rgba(15,23,42,0.03)',
              }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
                  <span style={{ fontSize: '0.95rem' }}>{meta.icon}</span>
                  <span style={{ fontWeight: 600, color: '#0f172a', fontSize: '0.9rem' }}>
                    {ev.actorUsername || ev.actorUserId || 'someone'}
                  </span>
                  <span style={{ color: meta.tone, fontWeight: 500, fontSize: '0.88rem' }}>
                    {primary}
                  </span>
                  {secondary && (
                    <span style={{
                      padding: '2px 8px', borderRadius: 6,
                      background: '#f1f5f9', color: '#475569',
                      fontSize: '0.78rem', fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace',
                    }}>
                      {secondary}
                    </span>
                  )}
                  <span style={{ flex: 1 }} />
                  <span
                    title={new Date(ev.timestampMs).toLocaleString()}
                    style={{ fontSize: '0.78rem', color: '#94a3b8' }}
                  >
                    {formatRelative(ev.timestampMs)}
                  </span>
                </div>
              </div>
            </li>
          );
        })}
      </ol>
    </div>
  );
}
