import React, { useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import ToolsActivePanel from '../components/ToolsActivePanel';
import ToolsCatalogPanel from '../components/ToolsCatalogPanel';

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
  const { agentId } = useOutletContext<{ agentId: string }>();
  const [refreshKey, setRefreshKey] = useState(0);
  const [browseOpen, setBrowseOpen] = useState(false);

  const bumpRefresh = () => setRefreshKey(k => k + 1);

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      <div style={helpStyle}>
        Tools the agent can call this session. Built-in tools come from the harness;
        MCP servers are wired through <code>workspace/tools.json</code>. Toggling here
        edits that file — the change applies to the next session boot.
      </div>
      <div style={{ flex: 1, minHeight: 0 }}>
        <ToolsActivePanel
          agentId={agentId}
          refreshKey={refreshKey}
          onChange={bumpRefresh}
          onRequestBrowse={() => setBrowseOpen(true)}
        />
      </div>
      {browseOpen && (
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
