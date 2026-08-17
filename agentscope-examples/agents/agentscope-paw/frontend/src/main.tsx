import React from 'react';
import ReactDOM from 'react-dom/client';
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom';
import AgentsHubPage from './pages/AgentsHubPage';
import AgentCreatePage from './pages/AgentCreatePage';
import AgentChatPage from './pages/AgentChatPage';
import AgentWorkspacePage from './pages/AgentWorkspacePage';
import AgentSkillsPage from './pages/AgentSkillsPage';
import AgentSubagentsPage from './pages/AgentSubagentsPage';
import AgentToolsPage from './pages/AgentToolsPage';
import AgentSessionsPage from './pages/AgentSessionsPage';
import AgentSessionDetailPage from './pages/AgentSessionDetailPage';
import AgentChannelsPage from './pages/AgentChannelsPage';
import AgentSettingsPage from './pages/AgentSettingsPage';
import ChannelsHubPage from './pages/ChannelsHubPage';
import ChannelDetailPage from './pages/ChannelDetailPage';
import AppShell from './components/AppShell';
import AgentLayout from './components/AgentLayout';

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <BrowserRouter>
      <Routes>
        <Route element={<AppShell />}>
          <Route path="/" element={<Navigate to="/agents" replace />} />
          <Route path="/agents" element={<AgentsHubPage />} />
          <Route path="/agents/new" element={<AgentCreatePage />} />

          <Route path="/channels" element={<ChannelsHubPage />} />
          <Route path="/channels/:channelId" element={<ChannelDetailPage />} />

          <Route path="/agents/:id" element={<AgentLayout />}>
            <Route index element={<Navigate to="chat" replace />} />
            <Route path="chat" element={<AgentChatPage />} />
            <Route path="workspace" element={<AgentWorkspacePage />} />
            <Route path="skills" element={<AgentSkillsPage />} />
            <Route path="subagents" element={<AgentSubagentsPage />} />
            <Route path="tools" element={<AgentToolsPage />} />
            <Route path="sessions" element={<AgentSessionsPage />} />
            <Route path="sessions/:key" element={<AgentSessionDetailPage />} />
            <Route path="channels" element={<AgentChannelsPage />} />
            <Route path="settings" element={<AgentSettingsPage />} />
          </Route>

          <Route path="*" element={<Navigate to="/agents" replace />} />
        </Route>
      </Routes>
    </BrowserRouter>
  </React.StrictMode>,
);
