import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';

import LoginPage from './pages/LoginPage';
import AppShell from './components/AppShell';
import EditTierGate from './components/EditTierGate';

import ChatPage from './pages/ChatPage';
import WorkspacePage from './pages/WorkspacePage';
import SkillsPage from './pages/configure/SkillsPage';
import SubagentsPage from './pages/configure/SubagentsPage';
import ChannelsPage from './pages/configure/ChannelsPage';
import ToolsPage from './pages/configure/ToolsPage';
import SettingsPage from './pages/configure/SettingsPage';

import ProfilePage from './pages/ProfilePage';
import AppearancePage from './pages/AppearancePage';
import ContributionsPage from './pages/ContributionsPage';
import UserBindingsPage from './pages/UserBindingsPage';
import UsagePage from './pages/UsagePage';

import OverviewPage from './pages/admin/OverviewPage';
import ApprovalsPage from './pages/admin/ApprovalsPage';
import InstancesPage from './pages/admin/InstancesPage';
import AdminChannelsPage from './pages/admin/ChannelsPage';
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

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route path="/login" element={<LoginPage />} />

        <Route element={<PrivateRoute><AppShell /></PrivateRoute>}>
          <Route index element={<Navigate to="/chat" replace />} />

          {/* Primary chat surface */}
          <Route path="/chat" element={<ChatPage />} />

          {/* Workspace browser (RUN-tier read-only allowed) */}
          <Route path="/workspace" element={<WorkspacePage />} />

          {/* Configuration pages — EDIT tier only */}
          <Route path="/configure/skills"    element={<EditTierGate><SkillsPage /></EditTierGate>} />
          <Route path="/configure/subagents" element={<EditTierGate><SubagentsPage /></EditTierGate>} />
          <Route path="/configure/channels"  element={<EditTierGate><ChannelsPage /></EditTierGate>} />
          <Route path="/configure/tools"     element={<EditTierGate><ToolsPage /></EditTierGate>} />
          <Route path="/configure/settings"  element={<EditTierGate><SettingsPage /></EditTierGate>} />

          {/* User utility pages */}
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/appearance" element={<AppearancePage />} />
          <Route path="/contributions" element={<ContributionsPage />} />
          <Route path="/bindings" element={<UserBindingsPage />} />
          <Route path="/usage" element={<UsagePage />} />

          {/* Admin pages */}
          <Route path="/admin/overview"        element={<AdminRoute><OverviewPage /></AdminRoute>} />
          <Route path="/admin/instances"       element={<AdminRoute><InstancesPage /></AdminRoute>} />
          <Route path="/admin/sessions"        element={<AdminRoute><AdminSessionsPage /></AdminRoute>} />
          <Route path="/admin/channels"        element={<AdminRoute><AdminChannelsPage /></AdminRoute>} />
          <Route path="/admin/channels/:id"    element={<AdminRoute><AdminChannelDetailPage /></AdminRoute>} />
          <Route path="/admin/agents"          element={<AdminRoute><AdminAgentsPage /></AdminRoute>} />
          <Route path="/admin/agents/:id"      element={<AdminRoute><AdminAgentDetailPage /></AdminRoute>} />
          <Route path="/admin/approvals"       element={<AdminRoute><ApprovalsPage /></AdminRoute>} />
          <Route path="/admin/users"           element={<AdminRoute><UsersPage /></AdminRoute>} />
          <Route path="/admin/usage"           element={<AdminRoute><AdminUsagePage /></AdminRoute>} />
          <Route path="/admin/config"          element={<AdminRoute><ConfigPage /></AdminRoute>} />
          <Route path="/admin/debug"           element={<AdminRoute><DebugPage /></AdminRoute>} />
        </Route>

        <Route path="*" element={<Navigate to="/chat" replace />} />
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);
