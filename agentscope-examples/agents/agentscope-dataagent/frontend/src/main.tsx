import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import LoginPage from './pages/LoginPage';
import ChatPage from './pages/ChatPage';
import SessionsPage from './pages/SessionsPage';
import UsagePage from './pages/UsagePage';
import AppearancePage from './pages/AppearancePage';
import SkillsPage from './pages/SkillsPage';
import WorkspacePage from './pages/WorkspacePage';
import ProfilePage from './pages/ProfilePage';
import UserBindingsPage from './pages/UserBindingsPage';
import OverviewPage from './pages/admin/OverviewPage';
import InstancesPage from './pages/admin/InstancesPage';
import ChannelsPage from './pages/admin/ChannelsPage';
import ConfigPage from './pages/admin/ConfigPage';
import DebugPage from './pages/admin/DebugPage';
import AdminUsagePage from './pages/admin/UsagePage';
import UsersPage from './pages/admin/UsersPage';
import AdminSessionsPage from './pages/admin/SessionsPage';
import AdminAgentsPage from './pages/admin/AgentsPage';
import AdminAgentDetailPage from './pages/admin/AgentDetailPage';
import AdminChannelDetailPage from './pages/admin/ChannelDetailPage';

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

function PrivateRoute({ children }: { children: React.ReactElement }) {
  return getToken() ? children : <Navigate to="/login" replace />;
}

function AdminRoute({ children }: { children: React.ReactElement }) {
  if (!getToken()) return <Navigate to="/login" replace />;
  if (!isAdmin()) return <Navigate to="/chat" replace />;
  return children;
}

function RoleBasedHome() {
  if (!getToken()) return <Navigate to="/login" replace />;
  return <Navigate to={isAdmin() ? '/admin/overview' : '/chat'} replace />;
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        {/* User-facing routes — any authenticated user */}
        <Route path="/chat" element={<PrivateRoute><ChatPage /></PrivateRoute>} />
        <Route path="/sessions" element={<PrivateRoute><SessionsPage /></PrivateRoute>} />
        <Route path="/usage" element={<PrivateRoute><UsagePage /></PrivateRoute>} />
        <Route path="/appearance" element={<PrivateRoute><AppearancePage /></PrivateRoute>} />
        <Route path="/skills" element={<PrivateRoute><SkillsPage /></PrivateRoute>} />
        <Route path="/workspace" element={<PrivateRoute><WorkspacePage /></PrivateRoute>} />
        <Route path="/profile" element={<PrivateRoute><ProfilePage /></PrivateRoute>} />
        <Route path="/bindings" element={<PrivateRoute><UserBindingsPage /></PrivateRoute>} />

        {/* Admin console routes — ROLE_ADMIN required */}
        <Route path="/admin/overview" element={<AdminRoute><OverviewPage /></AdminRoute>} />
        <Route path="/admin/instances" element={<AdminRoute><InstancesPage /></AdminRoute>} />
        <Route path="/admin/sessions" element={<AdminRoute><AdminSessionsPage /></AdminRoute>} />
        <Route path="/admin/channels" element={<AdminRoute><ChannelsPage /></AdminRoute>} />
        <Route path="/admin/channels/:id" element={<AdminRoute><AdminChannelDetailPage /></AdminRoute>} />
        <Route path="/admin/agents" element={<AdminRoute><AdminAgentsPage /></AdminRoute>} />
        <Route path="/admin/agents/:id" element={<AdminRoute><AdminAgentDetailPage /></AdminRoute>} />
        <Route path="/admin/users" element={<AdminRoute><UsersPage /></AdminRoute>} />
        <Route path="/admin/usage" element={<AdminRoute><AdminUsagePage /></AdminRoute>} />
        <Route path="/admin/config" element={<AdminRoute><ConfigPage /></AdminRoute>} />
        <Route path="/admin/debug" element={<AdminRoute><DebugPage /></AdminRoute>} />

        <Route path="/" element={<RoleBasedHome />} />
        <Route path="*" element={<RoleBasedHome />} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);
