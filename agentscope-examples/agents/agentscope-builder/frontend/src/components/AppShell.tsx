import React, { useEffect, useRef, useState } from 'react';
import { useNavigate, useLocation, Outlet } from 'react-router-dom';
import { clearToken, getUsername, isAdmin } from '../api/auth';
import AgentRail from './AgentRail';

function UserMenu({ username, admin, onLogout }: {
  username: string;
  admin: boolean;
  onLogout: () => void;
}) {
  const navigate = useNavigate();
  const location = useLocation();
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handleClick(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false);
    }
    if (open) document.addEventListener('mousedown', handleClick);
    return () => document.removeEventListener('mousedown', handleClick);
  }, [open]);

  useEffect(() => { setOpen(false); }, [location.pathname]);

  return (
    <div ref={ref} style={{ position: 'relative' }}>
      <button
        onClick={() => setOpen(o => !o)}
        style={{
          display: 'flex', alignItems: 'center', gap: 10,
          background: open ? '#eef2ff' : 'transparent',
          border: `1px solid ${open ? '#c7d2fe' : 'transparent'}`,
          borderRadius: 999, padding: '6px 12px 6px 6px', cursor: 'pointer',
        }}
      >
        <div style={{
          width: 32, height: 32, borderRadius: '50%', flexShrink: 0,
          background: admin
            ? 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)'
            : 'linear-gradient(135deg,#94a3b8 0%,#64748b 100%)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          fontSize: '0.95rem', fontWeight: 700, color: '#ffffff',
          boxShadow: '0 1px 3px rgba(15,23,42,0.12)',
        }}>
          {username.charAt(0).toUpperCase() || '?'}
        </div>
        <span style={{ fontSize: '0.92rem', color: '#1e293b', fontWeight: 600 }}>{username || 'guest'}</span>
        <span style={{ fontSize: '0.7rem', color: '#94a3b8', transform: open ? 'rotate(180deg)' : 'none', transition: 'transform 0.15s' }}>▾</span>
      </button>

      {open && (
        <div style={{
          position: 'absolute', top: 'calc(100% + 8px)', right: 0,
          minWidth: 220, background: '#ffffff',
          border: '1px solid #e2e8f0', borderRadius: 12,
          boxShadow: '0 12px 32px rgba(15,23,42,0.12), 0 2px 6px rgba(15,23,42,0.06)',
          overflow: 'hidden', zIndex: 100,
        }}>
          <div style={{ padding: '14px 16px 12px', borderBottom: '1px solid #f1f5f9' }}>
            <div style={{ fontSize: '0.95rem', fontWeight: 700, color: '#0f172a' }}>{username}</div>
            <div style={{ fontSize: '0.78rem', color: '#94a3b8', marginTop: 2 }}>
              {admin ? 'Administrator' : 'Standard user'}
            </div>
          </div>
          <button
            onClick={() => { navigate('/profile'); setOpen(false); }}
            style={{
              display: 'flex', alignItems: 'center', gap: 10,
              width: '100%', background: 'transparent', border: 'none',
              padding: '12px 16px', cursor: 'pointer', fontSize: '0.92rem', color: '#334155',
              textAlign: 'left',
            }}
            onMouseEnter={e => (e.currentTarget.style.background = '#f8fafc')}
            onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
          >
            <span style={{ fontSize: '1rem' }}>👤</span> Profile
          </button>
          {admin && (
            <button
              onClick={() => { navigate('/admin/users'); setOpen(false); }}
              style={{
                display: 'flex', alignItems: 'center', gap: 10,
                width: '100%', background: 'transparent', border: 'none',
                padding: '12px 16px', cursor: 'pointer', fontSize: '0.92rem', color: '#334155',
                textAlign: 'left',
              }}
              onMouseEnter={e => (e.currentTarget.style.background = '#f8fafc')}
              onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
            >
              <span style={{ fontSize: '1rem' }}>🛡️</span> Manage users
            </button>
          )}
          <div style={{ height: 1, background: '#f1f5f9' }} />
          <button
            onClick={() => { onLogout(); setOpen(false); }}
            style={{
              display: 'flex', alignItems: 'center', gap: 10,
              width: '100%', background: 'transparent', border: 'none',
              padding: '12px 16px', cursor: 'pointer', fontSize: '0.92rem', color: '#dc2626',
              textAlign: 'left',
            }}
            onMouseEnter={e => (e.currentTarget.style.background = '#fef2f2')}
            onMouseLeave={e => (e.currentTarget.style.background = 'transparent')}
          >
            <span style={{ fontSize: '1rem' }}>↩</span> Sign out
          </button>
        </div>
      )}
    </div>
  );
}

export default function AppShell() {
  const navigate = useNavigate();
  const username = getUsername();
  const admin = isAdmin();

  function logout() {
    clearToken();
    navigate('/login', { replace: true });
  }

  return (
    <div style={{ display: 'flex', height: '100vh', background: '#f8fafc', color: '#0f172a', overflow: 'hidden' }}>
      <AgentRail />

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
        <div style={{
          height: 64, background: '#ffffff', borderBottom: '1px solid #e2e8f0',
          display: 'flex', alignItems: 'center', padding: '0 28px', flexShrink: 0,
          justifyContent: 'space-between',
        }}>
          <span
            onClick={() => navigate('/agents')}
            style={{
              display: 'inline-flex', alignItems: 'center', gap: 10,
              fontWeight: 700, color: '#0f172a', fontSize: '1.05rem',
              letterSpacing: '-0.01em', cursor: 'pointer',
            }}
          >
            <span style={{
              display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
              width: 30, height: 30, borderRadius: 8,
              background: 'linear-gradient(135deg,#6366f1 0%,#8b5cf6 100%)',
              color: '#ffffff', fontSize: '1rem',
              boxShadow: '0 2px 6px rgba(99,102,241,0.35)',
            }}>⚙</span>
            AgentScope Builder
          </span>
          <UserMenu username={username} admin={admin} onLogout={logout} />
        </div>

        <div style={{ flex: 1, overflow: 'auto' }}>
          <Outlet />
        </div>
      </div>
    </div>
  );
}
