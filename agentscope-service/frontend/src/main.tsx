/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import React from 'react';
import ReactDOM from 'react-dom/client';
import {
  BrowserRouter,
  Navigate,
  Route,
  Routes,
  useParams,
  useSearchParams,
} from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import './index.css';

import AppShell from './app/AppShell';
import { PrivateRoute } from './app/PrivateRoute';

import LoginPage from './pages/LoginPage';
import ProfilePage from './pages/ProfilePage';
import AgentsHubPage from './pages/AgentsHubPage';
import AgentCreatePage from './pages/AgentCreatePage';
import WorkspacesHubPage from './pages/WorkspacesHubPage';
import WorkspaceDetailPage from './pages/WorkspaceDetailPage';
import SessionsHubPage from './pages/SessionsHubPage';
import SessionCreatePage from './pages/SessionCreatePage';
import SessionDetailPage from './pages/SessionDetailPage';
import AgentWorkspacePage from './pages/AgentWorkspacePage';
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

import TeamsOverviewPage from './features/teams/TeamsOverviewPage';
import TeamsHubPage from './features/teams/TeamsHubPage';
import TeamCreatePage from './features/teams/TeamCreatePage';
import TeamDetailPage from './features/teams/TeamDetailPage';
import TeamsTemplatesPage from './features/teams/TeamsTemplatesPage';

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

/** Legacy Build Agent Chat → top-level Sessions. */
function AgentChatRedirect() {
  const { id = '' } = useParams();
  const [searchParams] = useSearchParams();
  const managed = searchParams.get('managed');
  if (managed) {
    return <Navigate to={`/sessions/${encodeURIComponent(managed)}`} replace />;
  }
  return <Navigate to={`/sessions?agentId=${encodeURIComponent(id)}`} replace />;
}

function AgentSessionsRedirect() {
  const { id = '' } = useParams();
  return <Navigate to={`/sessions?agentId=${encodeURIComponent(id)}`} replace />;
}

function AgentSessionDetailRedirect() {
  const { id = '', key = '' } = useParams();
  const [searchParams] = useSearchParams();
  const managed = searchParams.get('managed');
  if (key === '_managed' && managed) {
    return <Navigate to={`/sessions/${encodeURIComponent(managed)}?tab=details`} replace />;
  }
  return <Navigate to={`/sessions?agentId=${encodeURIComponent(id)}`} replace />;
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
            <Route path="/sessions" element={<SessionsHubPage />} />
            <Route path="/sessions/new" element={<SessionCreatePage />} />
            <Route path="/sessions/:sessionId" element={<SessionDetailPage />} />
            <Route path="/workspaces" element={<WorkspacesHubPage />} />
            <Route path="/workspaces/:id" element={<WorkspaceDetailPage />} />
            <Route path="/profile" element={<ProfilePage />} />
            <Route path="/admin/users" element={<AdminUsersPage />} />
            <Route path="/environments" element={<EnvironmentsHubPage />} />
            <Route path="/memory-stores" element={<MemoryStoresPage />} />
            <Route path="/vaults" element={<VaultsPage />} />
            <Route path="/deployments" element={<DeploymentsPage />} />
            <Route path="/channels" element={<ChannelsHubPage />} />
            <Route path="/channels/:channelId" element={<ChannelDetailPage />} />

            <Route path="/agents/:id" element={<AgentLayout />}>
              <Route index element={<Navigate to="settings" replace />} />
              <Route path="chat" element={<AgentChatRedirect />} />
              <Route path="workspace" element={<AgentWorkspacePage />} />
              <Route path="sessions" element={<AgentSessionsRedirect />} />
              <Route path="sessions/:key" element={<AgentSessionDetailRedirect />} />
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

            {/* Teams workspace */}
            <Route path="/teams" element={<TeamsOverviewPage />} />
            <Route path="/teams/list" element={<TeamsHubPage />} />
            <Route path="/teams/new" element={<TeamCreatePage />} />
            <Route path="/teams/templates" element={<TeamsTemplatesPage />} />
            <Route path="/teams/:teamName" element={<TeamDetailPage />} />

            <Route path="*" element={<Navigate to="/agents" replace />} />
          </Route>
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);
