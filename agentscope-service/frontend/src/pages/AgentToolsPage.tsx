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
import type { AgentDefinition } from '../api/agents';

const helpStyle: React.CSSProperties = {
  padding: '8px 24px',
  fontSize: '0.78rem',
  color: '#64748b',
  background: '#f8fafc',
  borderBottom: '1px solid #e2e8f0',
};

const modalOverlayStyle: React.CSSProperties = {
  position: 'fixed',
  inset: 0,
  background: 'rgba(15,23,42,0.55)',
  display: 'flex',
  alignItems: 'center',
  justifyContent: 'center',
  zIndex: 60,
};
const modalShellStyle: React.CSSProperties = {
  background: '#ffffff',
  borderRadius: 12,
  width: 'min(820px, 92vw)',
  height: 'min(640px, 86vh)',
  display: 'flex',
  flexDirection: 'column',
  boxShadow: '0 24px 80px rgba(15,23,42,0.3)',
  overflow: 'hidden',
  position: 'relative',
};
const modalHeaderStyle: React.CSSProperties = {
  padding: '14px 20px',
  borderBottom: '1px solid #e2e8f0',
  display: 'flex',
  alignItems: 'center',
  gap: 12,
};
const closeButtonStyle: React.CSSProperties = {
  padding: '6px 14px',
  borderRadius: 8,
  border: '1px solid #cbd5e1',
  background: '#ffffff',
  color: '#475569',
  fontSize: '0.85rem',
  fontWeight: 600,
  cursor: 'pointer',
};

export default function AgentToolsPage() {
  const { agentId, agent } = useOutletContext<{ agentId: string; agent: AgentDefinition | null }>();
  const [refreshKey, setRefreshKey] = useState(0);
  const [browseOpen, setBrowseOpen] = useState(false);
  const linked = agent?.workspaceId;

  const bumpRefresh = () => setRefreshKey(k => k + 1);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {linked ? (
        <LinkedWorkspaceBanner workspaceId={linked} resource="tools" />
      ) : (
        <div style={helpStyle}>
          Tools and MCP servers are stored on the Agent definition (<code>tools</code> /{' '}
          <code>mcpServers</code>) and create a new agent version on save. Changes apply to
          the next Session. Use <b>Ask</b> on a built-in tool to pause for confirmation
          before that tool runs (HITL). Link a Workspace to author a shared toolset.
        </div>
      )}
      <div style={{ flex: 1, minHeight: 0 }}>
        <ToolsActivePanel
          agentId={agentId}
          refreshKey={refreshKey}
          onChange={bumpRefresh}
          onRequestBrowse={() => setBrowseOpen(true)}
          readOnly={!!linked}
        />
      </div>
      {browseOpen && !linked && (
        <div style={modalOverlayStyle} onClick={() => setBrowseOpen(false)}>
          <div style={modalShellStyle} onClick={e => e.stopPropagation()}>
            <div style={modalHeaderStyle}>
              <div style={{ flex: 1 }}>
                <div style={{ fontSize: '1rem', fontWeight: 600, color: '#0f172a' }}>
                  Configure tools
                </div>
                <div style={{ fontSize: '0.78rem', color: '#64748b', marginTop: 2 }}>
                  Enable / disable built-in tools or add an MCP server.
                </div>
              </div>
              <button onClick={() => setBrowseOpen(false)} style={closeButtonStyle}>
                Close
              </button>
            </div>
            <div style={{ flex: 1, minHeight: 0, overflow: 'hidden', position: 'relative' }}>
              <ToolsCatalogPanel agentId={agentId} onSaved={bumpRefresh} />
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
