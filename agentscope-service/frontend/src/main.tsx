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
  Outlet,
  Route,
  Routes,
  useParams,
  useSearchParams,
} from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import './index.css';

import AppShell from './app/AppShell';
import { PrivateRoute } from './app/PrivateRoute';
import { ScopeProvider } from './app/ScopeContext';
import { getRoles } from './api/auth';

const LoginPage = React.lazy(() => import('./pages/LoginPage'));
const ProfilePage = React.lazy(() => import('./pages/ProfilePage'));
const AgentsHubPage = React.lazy(() => import('./pages/AgentsHubPage'));
const AgentCreatePage = React.lazy(() => import('./pages/AgentCreatePage'));
const WorkspacesHubPage = React.lazy(() => import('./pages/WorkspacesHubPage'));
const WorkspaceDetailPage = React.lazy(() => import('./pages/WorkspaceDetailPage'));
const SessionsHubPage = React.lazy(() => import('./pages/SessionsHubPage'));
const SessionCreatePage = React.lazy(() => import('./pages/SessionCreatePage'));
const SessionDetailPage = React.lazy(() => import('./pages/SessionDetailPage'));
const AgentWorkspacePage = React.lazy(() => import('./pages/AgentWorkspacePage'));
const AgentChannelsPage = React.lazy(() => import('./pages/AgentChannelsPage'));
const AgentSettingsPage = React.lazy(() => import('./pages/AgentSettingsPage'));
const AgentSkillsPage = React.lazy(() => import('./pages/AgentSkillsPage'));
const AgentToolsPage = React.lazy(() => import('./pages/AgentToolsPage'));
const AgentSubagentsPage = React.lazy(() => import('./pages/AgentSubagentsPage'));
const AdminUsersPage = React.lazy(() => import('./pages/AdminUsersPage'));
const ChannelsHubPage = React.lazy(() => import('./pages/ChannelsHubPage'));
const ChannelDetailPage = React.lazy(() => import('./pages/ChannelDetailPage'));
const EnvironmentsHubPage = React.lazy(() => import('./pages/EnvironmentsHubPage'));
const MemoryStoresPage = React.lazy(() => import('./pages/MemoryStoresPage'));
const VaultsPage = React.lazy(() => import('./pages/VaultsPage'));
const DeploymentsPage = React.lazy(() => import('./features/build/deployments/DeploymentsPage'));
const AgentLayout = React.lazy(() => import('./components/AgentLayout'));
const OperateAgentsPage = React.lazy(() => import('./features/operate/OperateAgentsPage'));
const OperateAgentDetailPage = React.lazy(() => import('./features/operate/OperateAgentDetailPage'));
const OperateSessionsPage = React.lazy(() => import('./features/operate/OperateSessionsPage'));
const OperateSessionDetailPage = React.lazy(() => import('./features/operate/OperateSessionDetailPage'));
const GovernancePage = React.lazy(() => import('./features/operate/GovernancePage'));
const TeamsOverviewPage = React.lazy(() => import('./features/teams/TeamsOverviewPage'));
const IssuesPage = React.lazy(() => import('./features/issues/IssuesPage'));
const IssueDetailPage = React.lazy(() => import('./features/issues/IssueDetailPage'));
const ControlCenterPage = React.lazy(() => import('./features/control/ControlCenterPage'));
const TasksPage = React.lazy(() => import('./features/tasks/TasksPage'));
const TaskDetailPage = React.lazy(() => import('./features/tasks/TaskDetailPage'));
const AgentInstancesPage = React.lazy(() => import('./features/agents/AgentInstancesPage'));
const ApprovalsPage = React.lazy(() => import('./features/approvals/ApprovalsPage'));
const AutomationsPage = React.lazy(() => import('./features/operate/AutomationsPage'));
const RuntimeHostsPage = React.lazy(() => import('./features/runtime/RuntimePages').then((module) => ({ default: module.RuntimeHostsPage })));
const RuntimeHostDetailPage = React.lazy(() => import('./features/runtime/RuntimePages').then((module) => ({ default: module.RuntimeHostDetailPage })));
const RuntimeProfilesPage = React.lazy(() => import('./features/runtime/RuntimePages').then((module) => ({ default: module.RuntimeProfilesPage })));
const RuntimePoolsPage = React.lazy(() => import('./features/runtime/RuntimePages').then((module) => ({ default: module.RuntimePoolsPage })));
const RuntimePolicyPage = React.lazy(() => import('./features/runtime/RuntimePolicyPage'));
const ManagedOverviewPage = React.lazy(() => import('./features/managed/ManagedOverviewPage'));
const DefinitionsPage = React.lazy(() => import('./features/orchestration/DefinitionsPage'));
const RunsPage = React.lazy(() => import('./features/orchestration/RunsPage'));

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

/** Managed Agent Chat → the durable conversation area. */
function AgentChatRedirect() {
  const { id = '' } = useParams();
  const [searchParams] = useSearchParams();
  const managed = searchParams.get('managed');
  if (managed) {
    return <Navigate to={`/managed/sessions/${encodeURIComponent(managed)}`} replace />;
  }
  return <Navigate to={`/managed/sessions?agentId=${encodeURIComponent(id)}`} replace />;
}

function AgentSessionsRedirect() {
  const { id = '' } = useParams();
  return <Navigate to={`/managed/sessions?agentId=${encodeURIComponent(id)}`} replace />;
}

function AgentSessionDetailRedirect() {
  const { id = '', key = '' } = useParams();
  const [searchParams] = useSearchParams();
  const managed = searchParams.get('managed');
  if (key === '_managed' && managed) {
    return <Navigate to={`/managed/sessions/${encodeURIComponent(managed)}?tab=details`} replace />;
  }
  return <Navigate to={`/managed/sessions?agentId=${encodeURIComponent(id)}`} replace />;
}

type WorkspaceArea = 'work' | 'agent-center' | 'operations';

function defaultWorkspace(): string {
  const roles = getRoles().map((role) => role.toLowerCase());
  if (roles.includes('admin')) return '/work/overview';
  if (roles.includes('operator')) return '/operations/overview';
  if (roles.includes('agent_developer')) return '/agent-center/agents';
  return '/work/overview';
}

function DefaultWorkspaceRedirect() {
  return <Navigate to={defaultWorkspace()} replace />;
}

function WorkspaceAccess({ area }: { area: WorkspaceArea }) {
  const roles = getRoles().map((role) => role.toLowerCase());
  const admin = roles.includes('admin');
  const allowed = area === 'work' || admin ||
    area === 'agent-center' && (roles.includes('agent_developer') || roles.includes('operator')) ||
    area === 'operations' && roles.includes('operator');
  return allowed ? <Outlet /> : <DefaultWorkspaceRedirect />;
}

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <ScopeProvider><React.Suspense fallback={<div className="flex h-full items-center justify-center text-sm text-muted-foreground">Loading console…</div>}><Routes>
          <Route path="/login" element={<LoginPage />} />

          <Route
            element={
              <PrivateRoute>
                <AppShell />
              </PrivateRoute>
            }
          >
            <Route path="/" element={<DefaultWorkspaceRedirect />} />

            {/* v5 product workspaces */}
            <Route path="/work" element={<WorkspaceAccess area="work" />}>
              <Route index element={<Navigate to="overview" replace />} />
              <Route path="overview" element={<ControlCenterPage />} />
              <Route path="issues" element={<IssuesPage />} />
              <Route path="issues/:issueId" element={<IssueDetailPage />} />
              <Route path="approvals" element={<ApprovalsPage />} />
              <Route path="automations" element={<AutomationsPage />} />
              <Route path="activity" element={<ControlCenterPage />} />
            </Route>

            <Route path="/agent-center" element={<WorkspaceAccess area="agent-center" />}>
              <Route index element={<Navigate to="agents" replace />} />
              <Route path="agents" element={<OperateAgentsPage />} />
              <Route path="agents/:name" element={<OperateAgentDetailRoute />} />
              <Route path="teams" element={<TeamsOverviewPage />} />
              <Route path="workflows" element={<DefinitionsPage />} />
              <Route path="workflows/:definitionId" element={<DefinitionsPage />} />
              <Route path="endpoints" element={<DeploymentsPage />} />
              <Route path="entrypoints" element={<ChannelsHubPage />} />
              <Route path="entrypoints/:channelId" element={<ChannelDetailPage />} />
              <Route path="workspaces" element={<WorkspacesHubPage />} />
              <Route path="workspaces/:id" element={<WorkspaceDetailPage />} />
              <Route path="environments" element={<EnvironmentsHubPage />} />
              <Route path="memory" element={<MemoryStoresPage />} />
              <Route path="vaults" element={<VaultsPage />} />
            </Route>

            <Route path="/operations" element={<WorkspaceAccess area="operations" />}>
              <Route index element={<Navigate to="overview" replace />} />
              <Route path="overview" element={<ControlCenterPage />} />
              <Route path="instances" element={<AgentInstancesPage />} />
              <Route path="runtime/hosts" element={<RuntimeHostsPage />} />
              <Route path="runtime/hosts/:hostId" element={<RuntimeHostDetailPage />} />
              <Route path="runtime/profiles" element={<RuntimeProfilesPage />} />
              <Route path="runtime/pools" element={<RuntimePoolsPage />} />
              <Route path="runtime/policy" element={<RuntimePolicyPage />} />
              <Route path="sessions" element={<OperateSessionsPage />} />
              <Route path="sessions/:sessionId" element={<OperateSessionDetailPage />} />
              <Route path="runs" element={<RunsPage />} />
              <Route path="runs/:runId" element={<RunsPage />} />
              <Route path="tasks" element={<TasksPage />} />
              <Route path="tasks/:taskId" element={<TaskDetailPage />} />
              <Route path="governance" element={<GovernancePage />} />
            </Route>

            {/* Control Plane product area */}
            <Route path="/control" element={<Navigate to="/control/overview" replace />} />
            <Route path="/control/overview" element={<ControlCenterPage />} />
            <Route path="/control/issues" element={<IssuesPage />} />
            <Route path="/control/issues/:issueId" element={<IssueDetailPage />} />
            <Route path="/control/tasks" element={<TasksPage />} />
            <Route path="/control/tasks/:taskId" element={<TaskDetailPage />} />
            <Route path="/control/sessions" element={<OperateSessionsPage />} />
            <Route path="/control/sessions/:sessionId" element={<OperateSessionDetailPage />} />
            <Route path="/control/runtime/hosts" element={<RuntimeHostsPage />} />
            <Route path="/control/runtime/hosts/:hostId" element={<RuntimeHostDetailPage />} />
            <Route path="/control/runtime/profiles" element={<RuntimeProfilesPage />} />
            <Route path="/control/runtime/pools" element={<RuntimePoolsPage />} />
            <Route path="/control/runtime/policy" element={<RuntimePolicyPage />} />
            <Route path="/control/approvals" element={<ApprovalsPage />} />
            <Route path="/control/automations" element={<AutomationsPage />} />
            <Route path="/control/governance" element={<GovernancePage />} />
            <Route path="/control/teams" element={<TeamsOverviewPage />} />
            <Route path="/control/orchestration/definitions" element={<DefinitionsPage />} />
            <Route path="/control/orchestration/definitions/:definitionId" element={<DefinitionsPage />} />
            <Route path="/control/orchestration/runs" element={<RunsPage />} />
            <Route path="/control/orchestration/runs/:runId" element={<RunsPage />} />

            {/* Managed Agents product area */}
            <Route path="/managed" element={<Navigate to="/managed/overview" replace />} />
            <Route path="/managed/overview" element={<ManagedOverviewPage />} />
            <Route path="/managed/registered-agents" element={<OperateAgentsPage />} />
            <Route path="/managed/registered-agents/:name" element={<OperateAgentDetailRoute />} />
            <Route path="/managed/agent-instances" element={<AgentInstancesPage />} />
            <Route path="/managed/agents" element={<AgentsHubPage />} />
            <Route path="/managed/agents/new" element={<AgentCreatePage />} />
            <Route path="/managed/sessions" element={<SessionsHubPage />} />
            <Route path="/managed/sessions/new" element={<SessionCreatePage />} />
            <Route path="/managed/sessions/:sessionId" element={<SessionDetailPage />} />
            <Route path="/managed/workspaces" element={<WorkspacesHubPage />} />
            <Route path="/managed/workspaces/:id" element={<WorkspaceDetailPage />} />
            <Route path="/managed/profile" element={<ProfilePage />} />
            <Route path="/managed/admin/users" element={<AdminUsersPage />} />
            <Route path="/managed/environments" element={<EnvironmentsHubPage />} />
            <Route path="/managed/memory" element={<MemoryStoresPage />} />
            <Route path="/managed/vaults" element={<VaultsPage />} />
            <Route path="/managed/entrypoints" element={<DeploymentsPage />} />
            <Route path="/managed/channels" element={<ChannelsHubPage />} />
            <Route path="/managed/channels/:channelId" element={<ChannelDetailPage />} />

            <Route path="/managed/agents/:id" element={<AgentLayout />}>
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

            <Route path="*" element={<DefaultWorkspaceRedirect />} />
          </Route>
        </Routes></React.Suspense></ScopeProvider>
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>,
);
