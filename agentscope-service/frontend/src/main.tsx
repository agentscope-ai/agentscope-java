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
  useLocation,
  useParams,
  useSearchParams,
} from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import './index.css';

import AppShell from './app/AppShell';
import { PrivateRoute } from './app/PrivateRoute';
import { ScopeProvider, useControlPlaneScope } from './app/ScopeContext';
import { namespaceCan } from './lib/namespaceScope';
import { getRoles } from './api/auth';

const LoginPage = React.lazy(() => import('./pages/LoginPage'));
const ProfilePage = React.lazy(() => import('./pages/ProfilePage'));
const AgentsHubPage = React.lazy(() => import('./pages/AgentsHubPage'));
const AgentCatalogDetailPage = React.lazy(() => import('./features/build/agents/AgentCatalogDetailPage'));
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
const PermissionsPage = React.lazy(() => import('./features/work/PermissionsPage'));
const ResourceAccessPage = React.lazy(() => import('./features/settings/ResourceAccessPage'));
const ManagementLayout = React.lazy(() => import('./features/settings/ManagementLayout'));
const IntegrationsPage = React.lazy(() => import('./features/settings/IntegrationsPage'));
const NamespacesPage = React.lazy(() => import('./features/settings/NamespacesPage'));
const NamespaceDetailPage = React.lazy(() => import('./features/settings/NamespaceDetailPage'));
const AccessLogPage = React.lazy(() => import('./features/settings/AccessLogPage'));
const AdminUsersPage = React.lazy(() => import('./pages/AdminUsersPage'));
const ChannelsHubPage = React.lazy(() => import('./pages/ChannelsHubPage'));
const ChannelDetailPage = React.lazy(() => import('./pages/ChannelDetailPage'));
const EnvironmentsHubPage = React.lazy(() => import('./pages/EnvironmentsHubPage'));
const MemoryStoresPage = React.lazy(() => import('./pages/MemoryStoresPage'));
const VaultsPage = React.lazy(() => import('./pages/VaultsPage'));
const EndpointDetailPage = React.lazy(() => import('./features/build/deployments/EndpointDetailPage'));
const ChatPage = React.lazy(() => import('./features/chat/ChatPage'));
const AgentLayout = React.lazy(() => import('./components/AgentLayout'));
const ExecutionsPage = React.lazy(() => import('./features/operate/ExecutionsPage'));
const OperateSessionsPage = React.lazy(() => import('./features/operate/OperateSessionsPage'));
const OperateSessionDetailPage = React.lazy(() => import('./features/operate/OperateSessionDetailPage'));
const TeamsOverviewPage = React.lazy(() => import('./features/teams/TeamsOverviewPage'));
const TeamDetailPage = React.lazy(() => import('./features/teams/TeamDetailPage'));
const IssuesPage = React.lazy(() => import('./features/issues/IssuesPage'));
const IssueDetailPage = React.lazy(() => import('./features/issues/IssueDetailPage'));
const WorkOverviewPage = React.lazy(() => import('./features/work/WorkOverviewPage'));
const WorkActivityPage = React.lazy(() => import('./features/work/WorkActivityPage'));
const TasksPage = React.lazy(() => import('./features/tasks/TasksPage'));
const TaskDetailPage = React.lazy(() => import('./features/tasks/TaskDetailPage'));
const ApprovalsPage = React.lazy(() => import('./features/approvals/ApprovalsPage'));
const AutomationsPage = React.lazy(() => import('./features/operate/AutomationsPage'));
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

function RedirectWithSearch({ to, param }: { to: string; param?: string }) {
  const { search } = useLocation();
  const params = useParams();
  const suffix = param && params[param] ? `/${encodeURIComponent(params[param]!)}` : '';
  return <Navigate to={`${to}${suffix}${search}`} replace />;
}

function LegacyEndpointCatalogRedirect() {
  const [searchParams] = useSearchParams();
  const targetRef = searchParams.get('targetRef');
  const next = new URLSearchParams(searchParams);
  next.delete('targetRef');
  if (targetRef) next.set('tab', 'entrypoints');
  const target = targetRef
    ? `/agent-center/agents/${encodeURIComponent(targetRef)}`
    : '/agent-center/agents';
  return <Navigate to={`${target}?${next.toString()}`} replace />;
}

function LegacyOperationsRedirect() {
  const { pathname, search } = useLocation();
  let target = '/work/overview';
  const execution = pathname.match(/^\/operations\/(?:executions|runs)(?:\/(.+))?$/);
  const session = pathname.match(/^\/operations\/sessions(?:\/(.+))?$/);
  const task = pathname.match(/^\/operations\/tasks(?:\/(.+))?$/);
  if (pathname === '/operations' || pathname === '/operations/' || pathname === '/operations/overview') target = '/work/executions';
  else if (execution) target = `/work/executions${execution[1] ? `/${execution[1]}` : ''}`;
  else if (session) target = `/work/sessions${session[1] ? `/${session[1]}` : ''}`;
  else if (task) target = `/work/executions/tasks${task[1] ? `/${task[1]}` : ''}`;
  else if (pathname === '/operations/governance') target = '/work/activity';
  return <Navigate to={`${target}${search}`} replace />;
}

/** Legacy Agent-scoped chat entry → the Work Hub conversation area. */
function AgentChatRedirect() {
  const { id = '' } = useParams();
  const [searchParams] = useSearchParams();
  const next = new URLSearchParams(searchParams);
  next.delete('managed');
  next.delete('chat');
  next.set('agent', id);
  return <Navigate to={`/work/chat?${next.toString()}`} replace />;
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

type WorkspaceArea = 'work' | 'agent-center';

function defaultWorkspace(): string {
  const roles = getRoles().map((role) => role.toLowerCase());
  if (roles.includes('admin')) return '/work/overview';
  if (roles.includes('operator')) return '/work/overview';
  if (roles.includes('agent_developer')) return '/agent-center/agents';
  return '/work/overview';
}

function DefaultWorkspaceRedirect() {
  return <Navigate to={defaultWorkspace()} replace />;
}

function WorkspaceAccess({ area }: { area: WorkspaceArea }) {
  const scope = useControlPlaneScope();
  return area === 'work' || scope.roles.length > 0 ? <Outlet /> : <DefaultWorkspaceRedirect />;
}

function OperatorAccess() {
  const scope = useControlPlaneScope();
  return scope.roles.length > 0 ? <Outlet /> : <Navigate to="/work/overview" replace />;
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
              <Route path="overview" element={<WorkOverviewPage />} />
              <Route path="chat" element={<ChatPage />} />
              <Route path="issues" element={<IssuesPage />} />
              <Route path="issues/:issueId" element={<IssueDetailPage />} />
              <Route path="inbox" element={<ApprovalsPage />} />
              <Route path="approvals" element={<RedirectWithSearch to="/work/inbox" />} />
              <Route path="automations" element={<AutomationsPage />} />
              <Route path="activity" element={<WorkActivityPage />} />
              <Route element={<OperatorAccess />}>
                <Route path="executions" element={<ExecutionsPage />} />
                <Route path="executions/:runId" element={<RunsPage />} />
                <Route path="executions/tasks" element={<TasksPage />} />
                <Route path="executions/tasks/:taskId" element={<TaskDetailPage />} />
                <Route path="sessions" element={<OperateSessionsPage />} />
                <Route path="sessions/:sessionId" element={<OperateSessionDetailPage />} />
              </Route>
            </Route>

            <Route path="/agent-center" element={<WorkspaceAccess area="agent-center" />}>
              <Route index element={<Navigate to="agents" replace />} />
              <Route path="agents" element={<AgentsHubPage />} />
              <Route path="agents/new" element={<AgentCreatePage />} />
              <Route path="agents/:agentId" element={<AgentCatalogDetailPage />}>
                <Route path="definition" element={<Navigate to="behavior" replace />} />
                <Route path="definition/behavior" element={<AgentSettingsPage />} />
                <Route path="definition/workspace" element={<AgentWorkspacePage />} />
                <Route path="definition/skills" element={<AgentSkillsPage />} />
                <Route path="definition/tools" element={<AgentToolsPage />} />
                <Route path="definition/subagents" element={<AgentSubagentsPage />} />
                <Route path="definition/versions" element={<AgentSettingsPage section="versions" />} />
                <Route path="connections/channels" element={<AgentChannelsPage />} />
              </Route>
              <Route path="agents/:agentId/sessions/:sessionId" element={<OperateSessionDetailPage />} />
              <Route path="agents/:id/manage" element={<AgentLayout />}>
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
              <Route path="teams" element={<TeamsOverviewPage />} />
              <Route path="teams/:teamId" element={<TeamDetailPage />} />
              <Route path="workflows" element={<DefinitionsPage />} />
              <Route path="workflows/:definitionId" element={<DefinitionsPage />} />
              <Route path="endpoints" element={<LegacyEndpointCatalogRedirect />} />
              <Route path="endpoints/:endpointId" element={<EndpointDetailPage />} />
              <Route path="entrypoints" element={<ChannelsHubPage />} />
              <Route path="entrypoints/:channelId" element={<ChannelDetailPage />} />
              <Route path="workspaces" element={<WorkspacesHubPage />} />
              <Route path="workspaces/:id" element={<WorkspaceDetailPage />} />
              <Route path="environments" element={<EnvironmentsHubPage />} />
              <Route path="memory" element={<MemoryStoresPage />} />
              <Route path="vaults" element={<VaultsPage />} />
              <Route path="activity" element={<RedirectWithSearch to="/work/executions" />} />
              <Route path="activity/executions" element={<RedirectWithSearch to="/work/executions" />} />
              <Route path="activity/executions/:runId" element={<RedirectWithSearch to="/work/executions" param="runId" />} />
              <Route path="activity/sessions" element={<RedirectWithSearch to="/work/sessions" />} />
              <Route path="activity/sessions/:sessionId" element={<RedirectWithSearch to="/work/sessions" param="sessionId" />} />
              <Route path="activity/tasks" element={<RedirectWithSearch to="/work/executions/tasks" />} />
              <Route path="activity/tasks/:taskId" element={<RedirectWithSearch to="/work/executions/tasks" param="taskId" />} />
            </Route>

            <Route path="/operations/*" element={<LegacyOperationsRedirect />} />

            {/* Legacy control-plane routes */}
            <Route path="/control" element={<RedirectWithSearch to="/work/overview" />} />
            <Route path="/control/overview" element={<RedirectWithSearch to="/work/overview" />} />
            <Route path="/control/issues" element={<RedirectWithSearch to="/work/issues" />} />
            <Route path="/control/issues/:issueId" element={<RedirectWithSearch to="/work/issues" param="issueId" />} />
            <Route path="/control/tasks" element={<RedirectWithSearch to="/work/executions/tasks" />} />
            <Route path="/control/tasks/:taskId" element={<RedirectWithSearch to="/work/executions/tasks" param="taskId" />} />
            <Route path="/control/sessions" element={<RedirectWithSearch to="/work/sessions" />} />
            <Route path="/control/sessions/:sessionId" element={<RedirectWithSearch to="/work/sessions" param="sessionId" />} />
            <Route path="/control/runtime/*" element={<RedirectWithSearch to="/agent-center/agents" />} />
            <Route path="/control/approvals" element={<RedirectWithSearch to="/work/inbox" />} />
            <Route path="/control/automations" element={<RedirectWithSearch to="/work/automations" />} />
            <Route path="/control/governance" element={<RedirectWithSearch to="/work/activity" />} />
            <Route path="/control/teams" element={<RedirectWithSearch to="/agent-center/teams" />} />
            <Route path="/control/orchestration/definitions" element={<RedirectWithSearch to="/agent-center/workflows" />} />
            <Route path="/control/orchestration/definitions/:definitionId" element={<RedirectWithSearch to="/agent-center/workflows" param="definitionId" />} />
            <Route path="/control/orchestration/runs" element={<RedirectWithSearch to="/work/executions" />} />
            <Route path="/control/orchestration/runs/:runId" element={<RedirectWithSearch to="/work/executions" param="runId" />} />

            {/* Managed Agents product area */}
            <Route path="/managed" element={<RedirectWithSearch to="/agent-center/agents" />} />
            <Route path="/managed/overview" element={<RedirectWithSearch to="/agent-center/agents" />} />
            <Route path="/managed/registered-agents" element={<RedirectWithSearch to="/agent-center/agents" />} />
            <Route path="/managed/registered-agents/:name" element={<RedirectWithSearch to="/agent-center/agents" />} />
            <Route path="/managed/agent-instances" element={<RedirectWithSearch to="/agent-center/agents" />} />
            <Route path="/managed/agents" element={<AgentsHubPage />} />
            <Route path="/managed/agents/new" element={<AgentCreatePage />} />
            <Route path="/managed/sessions" element={<SessionsHubPage />} />
            <Route path="/managed/sessions/new" element={<SessionCreatePage />} />
            <Route path="/managed/sessions/:sessionId" element={<SessionDetailPage />} />
            <Route path="/managed/workspaces" element={<WorkspacesHubPage />} />
            <Route path="/managed/workspaces/:id" element={<WorkspaceDetailPage />} />
            <Route path="/settings/profile" element={<ProfilePage />} />
            <Route path="/settings" element={<ManagementLayout />}>
              <Route index element={<Navigate to="/settings/namespaces" replace />} />
              <Route path="namespaces" element={<NamespacesPage />} />
              <Route path="namespaces/:namespaceName" element={<NamespaceDetailPage />} />
              <Route path="namespaces/:namespaceName/resources/:kind/:resourceId" element={<ResourceAccessPage />} />
              <Route path="users" element={<AdminUsersPage />} />
              <Route path="access-log" element={<AccessLogPage />} />
              <Route path="integrations" element={<IntegrationsPage />} />
            </Route>
            <Route path="/managed/profile" element={<RedirectWithSearch to="/settings/profile" />} />
            <Route path="/managed/admin/users" element={<RedirectWithSearch to="/settings/users" />} />
            <Route path="/work/permissions" element={<PermissionsPage />} />
            <Route path="/managed/environments" element={<EnvironmentsHubPage />} />
            <Route path="/managed/memory" element={<MemoryStoresPage />} />
            <Route path="/managed/vaults" element={<VaultsPage />} />
            <Route path="/managed/entrypoints" element={<LegacyEndpointCatalogRedirect />} />
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
