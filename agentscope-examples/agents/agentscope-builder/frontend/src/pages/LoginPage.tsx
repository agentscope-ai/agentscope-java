import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { login, saveToken } from '../api/auth';

const s: Record<string, React.CSSProperties> = {
  page: {
    minHeight: '100vh',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
    background: 'linear-gradient(135deg,#f8fafc 0%,#eef2ff 100%)',
  },
  card: {
    background: '#ffffff',
    border: '1px solid #e2e8f0',
    borderRadius: 16,
    padding: '40px 36px',
    width: 420,
    display: 'flex',
    flexDirection: 'column',
    gap: '1.5rem',
    boxShadow: '0 12px 40px rgba(15,23,42,0.08), 0 2px 8px rgba(15,23,42,0.04)',
  },
  brand: {
    display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 10,
    marginBottom: 4,
  },
  brandIcon: {
    display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
    width: 40, height: 40, borderRadius: 10,
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff', fontSize: '1.25rem',
    boxShadow: '0 2px 8px rgba(99,102,241,0.4)',
  },
  title: {
    fontSize: '1.5rem',
    fontWeight: 700,
    color: '#0f172a',
    textAlign: 'center',
    letterSpacing: '-0.01em',
  },
  sub: { fontSize: '0.95rem', color: '#64748b', textAlign: 'center', marginTop: -8 },
  label: { fontSize: '0.85rem', fontWeight: 500, color: '#475569', marginBottom: 6, display: 'block' },
  input: {
    width: '100%',
    padding: '0.75rem 0.95rem',
    background: '#ffffff',
    border: '1px solid #cbd5e1',
    borderRadius: 8,
    color: '#0f172a',
    fontSize: '0.95rem',
  },
  button: {
    padding: '0.85rem',
    background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
    color: '#ffffff',
    border: 'none',
    borderRadius: 8,
    fontSize: '0.98rem',
    fontWeight: 600,
    cursor: 'pointer',
    marginTop: 6,
    boxShadow: '0 2px 6px rgba(99,102,241,0.35), inset 0 1px 0 rgba(255,255,255,0.18)',
  },
  error: { color: '#dc2626', fontSize: '0.88rem', textAlign: 'center' },
};

export default function LoginPage() {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const navigate = useNavigate();

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError('');
    setLoading(true);
    try {
      const res = await login(username, password);
      saveToken(res.token);
      navigate('/agents', { replace: true });
    } catch {
      setError('Invalid username or password');
    } finally {
      setLoading(false);
    }
  }

  return (
    <div style={s.page}>
      <form style={s.card} onSubmit={handleSubmit}>
        <div style={s.brand}>
          <span style={s.brandIcon}>⚙</span>
        </div>
        <div style={s.title}>AgentScope Builder</div>
        <div style={s.sub}>Sign in to continue</div>
        <div>
          <label style={s.label}>Username</label>
          <input
            style={s.input}
            type="text"
            value={username}
            onChange={e => setUsername(e.target.value)}
            autoFocus
            autoComplete="username"
          />
        </div>
        <div>
          <label style={s.label}>Password</label>
          <input
            style={s.input}
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </div>
        {error && <div style={s.error}>{error}</div>}
        <button style={s.button} type="submit" disabled={loading}>
          {loading ? 'Signing in…' : 'Sign in'}
        </button>
      </form>
    </div>
  );
}
