import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import AppShell from '../components/AppShell';
import { UserView, getProfile, changePassword } from '../api/users';

// ── Usage types ──────────────────────────────────────────────────────
interface UsageSummary { totalTurns: number; todayTurns: number; avgDurationMs: number; }
interface BucketCount  { epochMs: number; label: string; count: number; }

function authHeader(): Record<string, string> {
  const t = localStorage.getItem('claw_token') ?? '';
  return { Authorization: `Bearer ${t}` };
}

async function fetchMyUsage(): Promise<UsageSummary> {
  const r = await fetch('/api/usage/me/summary', { headers: authHeader() });
  if (!r.ok) throw new Error('Failed to fetch usage');
  return r.json();
}

async function fetchMyDaily(days = 7): Promise<BucketCount[]> {
  const r = await fetch(`/api/usage/me/daily?days=${days}`, { headers: authHeader() });
  if (!r.ok) throw new Error('Failed to fetch daily usage');
  return r.json();
}

// ── Mini sparkline (SVG bar chart) ───────────────────────────────────
function Sparkline({ data }: { data: BucketCount[] }) {
  if (!data.length) return null;
  const max = Math.max(...data.map(d => d.count), 1);
  const W = 220, H = 48, pad = 2;
  const barW = (W - pad * (data.length - 1)) / data.length;

  return (
    <svg width={W} height={H} style={{ display: 'block' }}>
      {data.map((d, i) => {
        const h = Math.max(2, (d.count / max) * H);
        const x = i * (barW + pad);
        const y = H - h;
        return (
          <g key={d.epochMs}>
            <rect x={x} y={y} width={barW} height={h}
              fill={d.count > 0 ? '#6366f1' : '#1e2235'} rx={2} />
            {i === data.length - 1 && d.count > 0 && (
              <text x={x + barW / 2} y={y - 3} textAnchor="middle"
                fontSize={9} fill="#a5b4fc">{d.count}</text>
            )}
          </g>
        );
      })}
    </svg>
  );
}

// ── Styles ───────────────────────────────────────────────────────────
const S: Record<string, React.CSSProperties> = {
  page:  { padding: '28px 32px', maxWidth: 700 },
  title: { margin: '0 0 24px', fontSize: '1.15rem', fontWeight: 700, color: '#e2e8f0' },
  grid:  { display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16, marginBottom: 20 },
  card:  {
    background: '#13151f', border: '1px solid #1e2235', borderRadius: 12,
    padding: '20px 22px',
  },
  cardFull: {
    background: '#13151f', border: '1px solid #1e2235', borderRadius: 12,
    padding: '20px 22px', marginBottom: 16,
  },
  cardLabel: { fontSize: '0.72rem', color: '#4b5280', fontWeight: 700,
    textTransform: 'uppercase' as const, letterSpacing: '0.07em', marginBottom: 10, display: 'block' },
  stat:  { fontSize: '2rem', fontWeight: 800, color: '#a5b4fc', lineHeight: 1 },
  statSub: { fontSize: '0.75rem', color: '#64748b', marginTop: 4 },
  row:   { display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 },
  rowLabel: { width: 110, fontSize: '0.8rem', color: '#64748b', flexShrink: 0 },
  rowValue: { fontSize: '0.88rem', color: '#e2e8f0' },
  badgeBase: {
    display: 'inline-block', padding: '2px 9px', borderRadius: 12, fontSize: '0.72rem',
    fontWeight: 600,
  } as React.CSSProperties,
  fieldLabel: { display: 'block', fontSize: '0.78rem', fontWeight: 500, color: '#94a3b8', marginBottom: 5 },
  input: {
    width: '100%', boxSizing: 'border-box' as const, padding: '8px 11px',
    background: '#0f1117', border: '1px solid #2d3148', borderRadius: 7,
    color: '#e2e8f0', fontSize: '0.85rem',
  },
  saveBtn: {
    marginTop: 14, padding: '8px 20px', background: '#6366f1', color: '#fff',
    border: 'none', borderRadius: 8, cursor: 'pointer', fontSize: '0.85rem', fontWeight: 600,
  },
  success: { color: '#34d399', fontSize: '0.8rem', marginTop: 8 },
  error:   { color: '#f87171', fontSize: '0.8rem', marginTop: 8 },
  link: {
    color: '#6366f1', fontSize: '0.8rem', cursor: 'pointer',
    background: 'none', border: 'none', padding: 0, textDecoration: 'underline',
  },
};

export default function ProfilePage() {
  const navigate = useNavigate();
  const [profile,  setProfile]  = useState<UserView | null>(null);
  const [usage,    setUsage]    = useState<UsageSummary | null>(null);
  const [daily,    setDaily]    = useState<BucketCount[]>([]);
  const [loadErr,  setLoadErr]  = useState<string | null>(null);

  const [curPwd,   setCurPwd]   = useState('');
  const [newPwd,   setNewPwd]   = useState('');
  const [conPwd,   setConPwd]   = useState('');
  const [pwdErr,   setPwdErr]   = useState<string | null>(null);
  const [pwdOk,    setPwdOk]    = useState(false);

  useEffect(() => {
    getProfile().then(setProfile).catch(e => setLoadErr(e.message));
    fetchMyUsage().then(setUsage).catch(() => {});
    fetchMyDaily(7).then(setDaily).catch(() => {});
  }, []);

  async function handleChangePwd() {
    setPwdErr(null); setPwdOk(false);
    if (newPwd.length < 6) { setPwdErr('Password must be ≥ 6 characters'); return; }
    if (newPwd !== conPwd) { setPwdErr('Passwords do not match'); return; }
    try {
      await changePassword(curPwd, newPwd);
      setPwdOk(true); setCurPwd(''); setNewPwd(''); setConPwd('');
    } catch (e: unknown) {
      setPwdErr(e instanceof Error ? e.message : 'Error');
    }
  }

  const fmt = (ms: number) =>
    ms < 1000 ? `${ms}ms` : ms < 60_000 ? `${(ms / 1000).toFixed(1)}s` : `${(ms / 60_000).toFixed(1)}m`;

  return (
    <AppShell>
      <div style={S.page}>
        <h2 style={S.title}>My Profile</h2>

        {loadErr && <p style={S.error}>{loadErr}</p>}

        {/* Account info + Usage stats grid */}
        <div style={S.grid}>

          {/* Account card */}
          <div style={S.card}>
            <span style={S.cardLabel}>Account</span>
            {profile && (
              <>
                <div style={S.row}>
                  <span style={S.rowLabel}>Username</span>
                  <span style={{ ...S.rowValue, fontWeight: 600 }}>{profile.username}</span>
                </div>
                <div style={S.row}>
                  <span style={S.rowLabel}>User ID</span>
                  <span style={{ ...S.rowValue, fontFamily: 'monospace', fontSize: '0.78rem', color: '#64748b' }}>{profile.userId}</span>
                </div>
                <div style={S.row}>
                  <span style={S.rowLabel}>Role</span>
                  <span>
                    {profile.roles.map(r => (
                      <span key={r} style={{ ...S.badgeBase, background: r === 'admin' ? '#312e81' : '#1e2235', color: r === 'admin' ? '#a5b4fc' : '#64748b' }}>{r}</span>
                    ))}
                  </span>
                </div>
                {profile.roles.includes('admin') && (
                  <button style={S.link} onClick={() => navigate('/admin/overview')}>
                    Go to Admin Panel →
                  </button>
                )}
              </>
            )}
          </div>

          {/* Usage stats card */}
          <div style={S.card}>
            <span style={S.cardLabel}>My Usage</span>
            {usage ? (
              <>
                <div style={{ display: 'flex', gap: 24, marginBottom: 12 }}>
                  <div>
                    <div style={S.stat}>{usage.totalTurns}</div>
                    <div style={S.statSub}>total turns</div>
                  </div>
                  <div>
                    <div style={S.stat}>{usage.todayTurns}</div>
                    <div style={S.statSub}>today</div>
                  </div>
                  {usage.avgDurationMs > 0 && (
                    <div>
                      <div style={{ ...S.stat, fontSize: '1.4rem' }}>{fmt(usage.avgDurationMs)}</div>
                      <div style={S.statSub}>avg response</div>
                    </div>
                  )}
                </div>
                {daily.length > 0 && (
                  <>
                    <div style={{ fontSize: '0.7rem', color: '#4b5280', marginBottom: 4 }}>Last 7 days</div>
                    <Sparkline data={daily} />
                  </>
                )}
              </>
            ) : (
              <div style={{ color: '#4b5280', fontSize: '0.8rem' }}>No usage data yet.</div>
            )}
          </div>
        </div>

        {/* Change password card */}
        <div style={S.cardFull}>
          <span style={S.cardLabel}>Change Password</span>
          <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 12 }}>
            <div>
              <label style={S.fieldLabel}>Current password</label>
              <input style={S.input} type="password" value={curPwd}
                onChange={e => setCurPwd(e.target.value)} placeholder="current" />
            </div>
            <div>
              <label style={S.fieldLabel}>New password</label>
              <input style={S.input} type="password" value={newPwd}
                onChange={e => setNewPwd(e.target.value)} placeholder="≥ 6 chars" />
            </div>
            <div>
              <label style={S.fieldLabel}>Confirm new</label>
              <input style={S.input} type="password" value={conPwd}
                onChange={e => setConPwd(e.target.value)} placeholder="repeat" />
            </div>
          </div>
          {pwdErr && <p style={S.error}>{pwdErr}</p>}
          {pwdOk  && <p style={S.success}>Password changed successfully!</p>}
          <button style={S.saveBtn} onClick={handleChangePwd}>Update Password</button>
        </div>
      </div>
    </AppShell>
  );
}
