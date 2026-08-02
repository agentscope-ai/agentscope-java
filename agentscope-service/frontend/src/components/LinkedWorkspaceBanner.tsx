import React from 'react';
import { Link } from 'react-router-dom';

const TAB_FOR: Record<string, string> = {
  skills: 'skills',
  tools: 'tools',
  subagents: 'subagents',
  files: 'agentsmd',
  settings: 'agentsmd',
};

export default function LinkedWorkspaceBanner({
  workspaceId,
  workspaceName,
  resource,
}: {
  workspaceId: string;
  workspaceName?: string;
  resource: 'skills' | 'tools' | 'subagents' | 'files' | 'settings';
}) {
  const label = workspaceName || workspaceId;
  const tab = TAB_FOR[resource] || 'agentsmd';
  const href = `/workspaces/${encodeURIComponent(workspaceId)}?tab=${encodeURIComponent(tab)}`;
  return (
    <div
      style={{
        padding: '10px 24px',
        fontSize: '0.82rem',
        color: '#3730a3',
        background: '#eef2ff',
        borderBottom: '1px solid #c7d2fe',
        display: 'flex',
        gap: 12,
        alignItems: 'center',
        flexWrap: 'wrap',
      }}
    >
      <span>
        Linked to workspace <strong>{label}</strong>. This page shows the agent snapshot
        (read-only). Edit shared {resource} in the Workspace so all linked agents stay consistent.
      </span>
      <Link
        to={href}
        style={{
          color: '#4338ca',
          fontWeight: 700,
          textDecoration: 'none',
          padding: '4px 10px',
          borderRadius: 999,
          border: '1px solid #c7d2fe',
          background: '#ffffff',
        }}
      >
        Edit in Workspace →
      </Link>
    </div>
  );
}
