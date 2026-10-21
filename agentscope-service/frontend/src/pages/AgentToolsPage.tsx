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

import React, { useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import ToolsActivePanel from '../components/ToolsActivePanel';
import ToolsCatalogPanel from '../components/ToolsCatalogPanel';
import LinkedWorkspaceBanner from '../components/LinkedWorkspaceBanner';
import McpConnectionsEditor from '../components/McpConnectionsEditor';
import { getAgent, updateAgent } from '../api/agents';
import type { AgentDefinition } from '../api/agents';
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription } from '../components/ui/dialog';

const helpStyle: React.CSSProperties = {
  padding: '8px 24px',
  fontSize: '0.78rem',
  color: '#64748b',
  background: '#f8fafc',
  borderBottom: '1px solid #e2e8f0',
};

export default function AgentToolsPage() {
  const { agentId, agent, canEdit = false, refreshAgent } = useOutletContext<{ agentId: string; agent: AgentDefinition | null; canEdit?: boolean; refreshAgent?: () => Promise<unknown> }>();
  const [refreshKey, setRefreshKey] = useState(0);
  const [browseOpen, setBrowseOpen] = useState(false);
  const linked = agent?.workspaceId;
  const toolsEditable = canEdit && (!linked || !!agent?.workspaceBinding?.overrides.includes('tools'));
  const mcpEditable = toolsEditable && (!linked || !!agent?.workspaceBinding?.overrides.includes('mcpServers'));

  const bumpRefresh = () => { setRefreshKey(k => k + 1); void refreshAgent?.(); };

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {linked ? (
        <LinkedWorkspaceBanner workspaceId={linked} resource="tools" hasOverrides={!!agent?.workspaceBinding?.overrides.includes('tools')} />
      ) : (
        <div style={helpStyle}>
          Choose the tools this Agent can use in new sessions, and select <b>Ask</b> when
          a tool needs your approval before running.
        </div>
      )}
      {agent?.runtimeKind === 'hosted-runtime' && <div role="note" style={helpStyle}>
        Built-in tools in this catalog belong to Managed Agents. Hosted runtimes use their native tools;
        supported mappings depend on the provider. Codex does not apply these built-in policies.
        Use its Runtime Profile for sandbox and approval settings. MCP connections can be configured below.
      </div>}
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>
        {agent && <McpConnectionsEditor servers={agent.mcpServers ?? []} tools={agent.tools ?? []} readOnly={!mcpEditable} canConnect={canEdit} onOAuthConnected={async vaultId => {
          const latest = await getAgent(agentId);
          if (!(latest.defaultVaultIds ?? []).includes(vaultId)) {
            await updateAgent(agentId, { name: latest.name, version: latest.version, defaultVaultIds: [...(latest.defaultVaultIds ?? []), vaultId] });
          }
          await refreshAgent?.();
        }} onSave={async (servers, tools) => {
          await updateAgent(agentId, { name: agent.name, version: agent.version, mcpServers: servers, tools });
          bumpRefresh();
        }} />}
        <ToolsActivePanel
          agentId={agentId}
          refreshKey={refreshKey}
          onChange={bumpRefresh}
          onRequestBrowse={() => setBrowseOpen(true)}
          readOnly={!toolsEditable}
          mcpReadOnly={!mcpEditable}
        />
      </div>
      <Dialog open={browseOpen && toolsEditable} onOpenChange={setBrowseOpen}>
        <DialogContent className="h-[min(640px,86vh)] max-w-[820px]">
          <DialogHeader>
            <DialogTitle>Configure tools</DialogTitle>
            <DialogDescription>Choose built-in tools and add MCP connections for this Agent.</DialogDescription>
          </DialogHeader>
          <div className="relative min-h-0 flex-1 overflow-hidden">
            <ToolsCatalogPanel agentId={agentId} onSaved={bumpRefresh} allowMcp={mcpEditable} />
          </div>
        </DialogContent>
      </Dialog>
    </div>
  );
}
