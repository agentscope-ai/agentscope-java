import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { login, saveToken } from '../api/auth';

const s: Record<string, React.CSSProperties> = {
  page: {
    minHeight: '100vh',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    background: '#0f1117',
  },
  card: {
    background: '#1a1d27',
    border: '1px solid #2d3148',
    borderRadius: 12,
    padding: '2.5rem 2rem',
    width: 360,
    display: 'flex',
    flexDirection: 'column',
    gap: '1.25rem',
  },
  title: {
    fontSize: '1.5rem',
    fontWeight: 700,
    color: '#e2e8f0',
    textAlign: 'center',
  },
  sub: { fontSize: '0.85rem', color: '#7c8bad', textAlign: 'center' },
  label: { fontSize: '0.8rem', color: '#94a3b8', marginBottom: 4, display: 'block' },
  input: {
    width: '100%',
    padding: '0.6rem 0.8rem',
    background: '#0f1117',
    border: '1px solid #2d3148',
    borderRadius: 6,
    color: '#e2e8f0',
    fontSize: '0.95rem',
    outline: 'none',
  },
  button: {
    padding: '0.7rem',
    background: '#6366f1',
    color: '#fff',
    border: 'none',
    borderRadius: 6,
    fontSize: '0.95rem',
    fontWeight: 600,
    cursor: 'pointer',
    marginTop: 4,
  },
  error: { color: '#f87171', fontSize: '0.85rem', textAlign: 'center' },
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

  return (
    <div style={s.page}>
      <form style={s.card} onSubmit={handleSubmit}>
        <div style={s.title}>DataAgent — Admin</div>
        <div style={s.sub}>Sign in to your account</div>
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
