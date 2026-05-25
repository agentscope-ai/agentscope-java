import React, { useEffect, useState } from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import LoginPage from './pages/LoginPage';
import ProfilePage from './pages/ProfilePage';
import AgentsHubPage from './pages/AgentsHubPage';
import AgentCreatePage from './pages/AgentCreatePage';
import AgentChatPage from './pages/AgentChatPage';
import AgentWorkspacePage from './pages/AgentWorkspacePage';
import AgentSessionsPage from './pages/AgentSessionsPage';
import AgentSessionDetailPage from './pages/AgentSessionDetailPage';
import AgentChannelsPage from './pages/AgentChannelsPage';
import AgentSettingsPage from './pages/AgentSettingsPage';
import AgentActivityPage from './pages/AgentActivityPage';
import AgentSkillsPage from './pages/AgentSkillsPage';
import AgentToolsPage from './pages/AgentToolsPage';
import AgentSubagentsPage from './pages/AgentSubagentsPage';
import AdminUsersPage from './pages/AdminUsersPage';
import ChannelsHubPage from './pages/ChannelsHubPage';
import ChannelDetailPage from './pages/ChannelDetailPage';
import MarketplacesPage from './pages/MarketplacesPage';
import AppShell from './components/AppShell';
import AgentLayout from './components/AgentLayout';
import { clearToken, getToken, me } from './api/auth';

/**
 * Token-presence check is not enough — a stale token (issued by a previous build,
 * tied to a deleted user, or simply expired) lets the React tree render an
 * "authenticated" shell while every /api/** call returns 401. So on mount we
 * also probe /api/auth/me; on failure we clear the token and bounce to /login.
 */
function PrivateRoute({ children }: { children: React.ReactElement }) {
  const token = getToken();
  const [status, setStatus] = useState<'checking' | 'ok' | 'invalid'>(
    token ? 'checking' : 'invalid',
  );

  useEffect(() => {
    if (!token) return;
    let cancelled = false;
    me().then(
      () => {
        if (!cancelled) setStatus('ok');
      },
      () => {
        if (cancelled) return;
        clearToken();
        setStatus('invalid');
      },
    );
    return () => {
      cancelled = true;
    };
  }, [token]);

  if (status === 'invalid') return <Navigate to="/login" replace />;
  if (status === 'checking') return null;
  return children;
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        <Route element={<PrivateRoute><AppShell /></PrivateRoute>}>
          <Route path="/" element={<Navigate to="/agents" replace />} />
          <Route path="/agents" element={<AgentsHubPage />} />
          <Route path="/agents/new" element={<AgentCreatePage />} />
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/admin/users" element={<AdminUsersPage />} />
          <Route path="/marketplaces" element={<MarketplacesPage />} />
          <Route path="/channels" element={<ChannelsHubPage />} />
          <Route path="/channels/:channelId" element={<ChannelDetailPage />} />

          <Route path="/agents/:id" element={<AgentLayout />}>
            <Route index element={<Navigate to="chat" replace />} />
            <Route path="chat" element={<AgentChatPage />} />
            <Route path="workspace" element={<AgentWorkspacePage />} />
            <Route path="sessions" element={<AgentSessionsPage />} />
            <Route path="sessions/:key" element={<AgentSessionDetailPage />} />
            <Route path="channels" element={<AgentChannelsPage />} />
            <Route path="skills" element={<AgentSkillsPage />} />
            <Route path="tools" element={<AgentToolsPage />} />
            <Route path="subagents" element={<AgentSubagentsPage />} />
            <Route path="activity" element={<AgentActivityPage />} />
            <Route path="settings" element={<AgentSettingsPage />} />
          </Route>

          <Route path="*" element={<Navigate to="/agents" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);
