import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Navigate, Route, Routes, useParams } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import './index.css';

import AppShell from './app/AppShell';
import { PrivateRoute } from './app/PrivateRoute';

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
import AgentSkillsPage from './pages/AgentSkillsPage';
import AgentToolsPage from './pages/AgentToolsPage';
import AgentSubagentsPage from './pages/AgentSubagentsPage';
import AdminUsersPage from './pages/AdminUsersPage';
import ChannelsHubPage from './pages/ChannelsHubPage';
import ChannelDetailPage from './pages/ChannelDetailPage';
import EnvironmentsHubPage from './pages/EnvironmentsHubPage';
import MemoryStoresPage from './pages/MemoryStoresPage';
import VaultsPage from './pages/VaultsPage';
import DeploymentsPage from './features/build/deployments/DeploymentsPage';
import AgentLayout from './components/AgentLayout';

import FleetOverviewPage from './features/operate/FleetOverviewPage';
import OperateAgentsPage from './features/operate/OperateAgentsPage';
import OperateAgentDetailPage from './features/operate/OperateAgentDetailPage';
import OperateSessionsPage from './features/operate/OperateSessionsPage';
import OperateSessionDetailPage from './features/operate/OperateSessionDetailPage';
import GovernancePage from './features/operate/GovernancePage';

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});

function OperateAgentDetailRoute() {
  const { name = '' } = useParams();
  return <OperateAgentDetailPage name={name} />;
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/login" element={<LoginPage />} />

          <Route
            element={
              <PrivateRoute>
                <AppShell />
              </PrivateRoute>
            }
          >
            <Route path="/" element={<Navigate to="/agents" replace />} />

            {/* Build workspace */}
            <Route path="/agents" element={<AgentsHubPage />} />
            <Route path="/agents/new" element={<AgentCreatePage />} />
            <Route path="/profile" element={<ProfilePage />} />
            <Route path="/admin/users" element={<AdminUsersPage />} />
            <Route path="/environments" element={<EnvironmentsHubPage />} />
            <Route path="/memory-stores" element={<MemoryStoresPage />} />
            <Route path="/vaults" element={<VaultsPage />} />
            <Route path="/deployments" element={<DeploymentsPage />} />
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
              <Route path="settings" element={<AgentSettingsPage />} />
            </Route>

            {/* Operate workspace */}
            <Route path="/operate" element={<FleetOverviewPage />} />
            <Route path="/operate/agents" element={<OperateAgentsPage />} />
            <Route path="/operate/agents/:name" element={<OperateAgentDetailRoute />} />
            <Route path="/operate/sessions" element={<OperateSessionsPage />} />
            <Route path="/operate/sessions/:sessionId" element={<OperateSessionDetailPage />} />
            <Route path="/operate/governance" element={<GovernancePage />} />

            <Route path="*" element={<Navigate to="/agents" replace />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);
