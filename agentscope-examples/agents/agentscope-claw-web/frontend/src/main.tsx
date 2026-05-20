import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import LoginPage from './pages/LoginPage';
import OverviewPage from './pages/OverviewPage';
import InstancesPage from './pages/InstancesPage';
import ChannelsPage from './pages/ChannelsPage';
import ConfigPage from './pages/ConfigPage';
import DebugPage from './pages/DebugPage';
import UsagePage from './pages/UsagePage';
import UsersPage from './pages/UsersPage';
import AdminSessionsPage from './pages/AdminSessionsPage';
import AdminAgentsPage from './pages/AdminAgentsPage';
import AdminAgentDetailPage from './pages/AdminAgentDetailPage';
import AdminChannelDetailPage from './pages/AdminChannelDetailPage';

function getToken(): string | null {
  return localStorage.getItem('claw_token');
}

function decodeJwt(token: string): Record<string, unknown> {
  try { return JSON.parse(atob(token.split('.')[1])); } catch { return {}; }
}

function isAdmin(): boolean {
  const token = getToken();
  if (!token) return false;
  const p = decodeJwt(token);
  const roles = Array.isArray(p.roles) ? (p.roles as string[]) : [];
  return roles.some((r: string) => r.toLowerCase() === 'admin');
}

function AdminRoute({ children }: { children: React.ReactElement }) {
  if (!getToken()) return <Navigate to="/login" replace />;
  if (!isAdmin()) return <Navigate to="/login" replace />;
  return children;
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />
        <Route path="/overview" element={<AdminRoute><OverviewPage /></AdminRoute>} />
        <Route path="/instances" element={<AdminRoute><InstancesPage /></AdminRoute>} />
        <Route path="/channels" element={<AdminRoute><ChannelsPage /></AdminRoute>} />
        <Route path="/channels/:id" element={<AdminRoute><AdminChannelDetailPage /></AdminRoute>} />
        <Route path="/config" element={<AdminRoute><ConfigPage /></AdminRoute>} />
        <Route path="/usage" element={<AdminRoute><UsagePage /></AdminRoute>} />
        <Route path="/debug" element={<AdminRoute><DebugPage /></AdminRoute>} />
        <Route path="/users" element={<AdminRoute><UsersPage /></AdminRoute>} />
        <Route path="/sessions" element={<AdminRoute><AdminSessionsPage /></AdminRoute>} />
        <Route path="/agents" element={<AdminRoute><AdminAgentsPage /></AdminRoute>} />
        <Route path="/agents/:id" element={<AdminRoute><AdminAgentDetailPage /></AdminRoute>} />
        <Route path="*" element={<Navigate to="/overview" replace />} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);
