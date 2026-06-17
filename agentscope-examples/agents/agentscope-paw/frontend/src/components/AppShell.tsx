import { useNavigate, Outlet } from 'react-router-dom';
import AgentRail from './AgentRail';

export default function AppShell() {
  const navigate = useNavigate();

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
            AgentScope Claw
          </span>
        </div>

        <div style={{ flex: 1, overflow: 'auto' }}>
          <Outlet />
        </div>
      </div>
    </div>
  );
}
