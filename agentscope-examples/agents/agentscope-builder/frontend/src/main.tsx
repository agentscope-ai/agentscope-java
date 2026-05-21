import React from 'react';
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
import AdminUsersPage from './pages/AdminUsersPage';
import AppShell from './components/AppShell';
import AgentLayout from './components/AgentLayout';
import { getToken } from './api/auth';

function PrivateRoute({ children }: { children: React.ReactElement }) {
  return getToken() ? children : <Navigate to="/login" replace />;
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

          <Route path="/agents/:id" element={<AgentLayout />}>
            <Route index element={<Navigate to="chat" replace />} />
            <Route path="chat" element={<AgentChatPage />} />
            <Route path="workspace" element={<AgentWorkspacePage />} />
            <Route path="sessions" element={<AgentSessionsPage />} />
            <Route path="sessions/:key" element={<AgentSessionDetailPage />} />
            <Route path="channels" element={<AgentChannelsPage />} />
            <Route path="activity" element={<AgentActivityPage />} />
            <Route path="settings" element={<AgentSettingsPage />} />
          </Route>

          <Route path="*" element={<Navigate to="/agents" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);
