import React, { useEffect, useState } from 'react';
import { UserProfile, getProfile, changePassword } from '../api/auth';

const S: Record<string, React.CSSProperties> = {
  page: { padding: '36px 40px', maxWidth: 760 },
  title: { margin: '0 0 28px', fontSize: '1.6rem', fontWeight: 700, color: '#0f172a', letterSpacing: '-0.02em' },
  card: {
    background: '#ffffff', border: '1px solid #e2e8f0', borderRadius: 14,
    padding: '24px 28px', marginBottom: 20,
    boxShadow: '0 1px 3px rgba(15,23,42,0.04)',
  },
  cardLabel: {
    fontSize: '0.78rem', color: '#94a3b8', fontWeight: 700,
    textTransform: 'uppercase', letterSpacing: '0.1em', marginBottom: 16, display: 'block',
  },
  row: { display: 'flex', alignItems: 'center', gap: 14, marginBottom: 12 },
  rowLabel: { width: 120, fontSize: '0.88rem', color: '#94a3b8', flexShrink: 0 },
  rowValue: { fontSize: '0.95rem', color: '#0f172a' },
  badge: {
    display: 'inline-block', padding: '3px 11px', borderRadius: 999, fontSize: '0.78rem',
    fontWeight: 600,
  },
  fieldLabel: { display: 'block', fontSize: '0.88rem', fontWeight: 500, color: '#475569', marginBottom: 8 },
  input: {
    width: '100%', boxSizing: 'border-box', padding: '11px 14px',
    background: '#ffffff', border: '1px solid #cbd5e1', borderRadius: 9,
    color: '#0f172a', fontSize: '0.95rem',
  },
  saveBtn: {
    marginTop: 18, padding: '11px 24px',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff',
    border: 'none', borderRadius: 9, cursor: 'pointer', fontSize: '0.95rem', fontWeight: 600,
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  success: { color: '#059669', fontSize: '0.9rem', marginTop: 10 },
  error: { color: '#dc2626', fontSize: '0.9rem', marginTop: 10 },
};

export default function ProfilePage() {
  const [profile, setProfile] = useState<UserProfile | null>(null);
  const [loadErr, setLoadErr] = useState<string | null>(null);

  const [curPwd, setCurPwd] = useState('');
  const [newPwd, setNewPwd] = useState('');
  const [conPwd, setConPwd] = useState('');
  const [pwdErr, setPwdErr] = useState<string | null>(null);
  const [pwdOk, setPwdOk] = useState(false);

  useEffect(() => {
    getProfile().then(setProfile).catch(e => setLoadErr(e.message));
  }, []);

  async function handleChangePwd() {
    setPwdErr(null);
    setPwdOk(false);
    if (newPwd.length < 6) { setPwdErr('Password must be ≥ 6 characters'); return; }
    if (newPwd !== conPwd) { setPwdErr('Passwords do not match'); return; }
    try {
      await changePassword(curPwd, newPwd);
      setPwdOk(true);
      setCurPwd(''); setNewPwd(''); setConPwd('');
    } catch (e: unknown) {
      setPwdErr(e instanceof Error ? e.message : 'Error');
    }
  }

  return (
    <div style={S.page}>
      <h2 style={S.title}>My Profile</h2>
      {loadErr && <p style={S.error}>{loadErr}</p>}

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
              <span style={{ ...S.rowValue, fontFamily: 'ui-monospace, SFMono-Regular, Menlo, monospace', fontSize: '0.88rem', color: '#64748b' }}>
                {profile.userId}
              </span>
            </div>
            <div style={S.row}>
              <span style={S.rowLabel}>Role</span>
              <span>
                {profile.roles.map(r => (
                  <span
                    key={r}
                    style={{
                      ...S.badge,
                      background: r === 'admin' ? '#eef2ff' : '#f1f5f9',
                      color: r === 'admin' ? '#4338ca' : '#64748b',
                      border: r === 'admin' ? '1px solid #c7d2fe' : '1px solid #e2e8f0',
                    }}
                  >
                    {r}
                  </span>
                ))}
              </span>
            </div>
          </>
        )}
      </div>

      <div style={S.card}>
        <span style={S.cardLabel}>Change Password</span>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }}>
          <div>
            <label style={S.fieldLabel}>Current</label>
            <input style={S.input} type="password" value={curPwd} onChange={e => setCurPwd(e.target.value)} />
          </div>
          <div>
            <label style={S.fieldLabel}>New</label>
            <input style={S.input} type="password" value={newPwd} onChange={e => setNewPwd(e.target.value)} placeholder="≥ 6 chars" />
          </div>
          <div>
            <label style={S.fieldLabel}>Confirm</label>
            <input style={S.input} type="password" value={conPwd} onChange={e => setConPwd(e.target.value)} />
          </div>
        </div>
        {pwdErr && <p style={S.error}>{pwdErr}</p>}
        {pwdOk && <p style={S.success}>Password changed successfully!</p>}
        <button style={S.saveBtn} onClick={handleChangePwd}>Update Password</button>
      </div>
    </div>
  );
}
