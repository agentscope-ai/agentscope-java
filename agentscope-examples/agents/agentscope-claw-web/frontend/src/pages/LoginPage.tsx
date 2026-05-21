import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { login, saveToken } from '../api/auth';

const s: Record<string, React.CSSProperties> = {
  page: {
    minHeight: '100vh',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    background: 'linear-gradient(135deg, #eef2ff 0%, #f5f7fa 60%, #f8fafc 100%)',
    padding: '2rem',
  },
  card: {
    background: '#ffffff',
    border: '1px solid #e5e7eb',
    borderRadius: 16,
    padding: '3rem 2.5rem',
    width: 420,
    display: 'flex',
    flexDirection: 'column',
    gap: '1.5rem',
    boxShadow: '0 20px 50px rgba(15,23,42,0.08), 0 4px 12px rgba(15,23,42,0.04)',
  },
  title: {
    fontSize: '1.75rem',
    fontWeight: 700,
    color: '#0f172a',
    textAlign: 'center',
    letterSpacing: '-0.02em',
  },
  sub: { fontSize: '0.95rem', color: '#64748b', textAlign: 'center', marginTop: -8 },
  label: { fontSize: '0.85rem', color: '#475569', marginBottom: 6, display: 'block', fontWeight: 500 },
  input: {
    width: '100%',
    padding: '0.7rem 0.9rem',
    background: '#ffffff',
    border: '1px solid #d1d5db',
    borderRadius: 8,
    color: '#0f172a',
    fontSize: '1rem',
    outline: 'none',
    transition: 'border-color 0.15s, box-shadow 0.15s',
  },
  button: {
    padding: '0.85rem',
    background: '#4f46e5',
    color: '#fff',
    border: 'none',
    borderRadius: 8,
    fontSize: '1rem',
    fontWeight: 600,
    cursor: 'pointer',
    marginTop: 8,
    transition: 'background 0.15s, transform 0.06s',
    boxShadow: '0 2px 6px rgba(79,70,229,0.25)',
  },
  error: { color: '#dc2626', fontSize: '0.9rem', textAlign: 'center', background: '#fef2f2', padding: '8px 12px', borderRadius: 6, border: '1px solid #fecaca' },
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
      navigate('/chat', { replace: true });
    } catch {
      setError('Invalid username or password');
    } finally {
      setLoading(false);
    }
  }

  function inputFocus(e: React.FocusEvent<HTMLInputElement>) {
    e.currentTarget.style.borderColor = '#4f46e5';
    e.currentTarget.style.boxShadow = '0 0 0 3px rgba(79,70,229,0.12)';
  }
  function inputBlur(e: React.FocusEvent<HTMLInputElement>) {
    e.currentTarget.style.borderColor = '#d1d5db';
    e.currentTarget.style.boxShadow = 'none';
  }

  return (
    <div style={s.page}>
      <form style={s.card} onSubmit={handleSubmit}>
        <div>
          <div style={s.title}>DataAgent</div>
          <div style={{ ...s.sub, marginTop: 6 }}>Sign in to your admin account</div>
        </div>
        <div>
          <label style={s.label}>Username</label>
          <input
            style={s.input}
            type="text"
            value={username}
            onChange={e => setUsername(e.target.value)}
            onFocus={inputFocus}
            onBlur={inputBlur}
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
            onFocus={inputFocus}
            onBlur={inputBlur}
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
