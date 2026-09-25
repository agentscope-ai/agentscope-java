import ResolvedDefinitionFiles from '../components/ResolvedDefinitionFiles';
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
import { useOutletContext } from 'react-router-dom';
import SubagentPanel from '../components/SubagentPanel';
import LinkedWorkspaceBanner from '../components/LinkedWorkspaceBanner';
import type { AgentDefinition } from '../api/agents';

const helpStyle: React.CSSProperties = {
  padding: '8px 24px',
  fontSize: '0.78rem',
  color: '#64748b',
  background: '#f8fafc',
  borderBottom: '1px solid #e2e8f0',
};

export default function AgentSubagentsPage() {
  const { agentId, agent, canEdit = false, refreshAgent } = useOutletContext<{ agentId: string; agent: AgentDefinition | null; canEdit?: boolean; refreshAgent?: () => Promise<unknown> }>();
  const linked = agent?.workspaceId;

  if (agent?.workspaceBinding) return <ResolvedDefinitionFiles agent={agent} prefix="subagents/" />;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {linked ? (
        <LinkedWorkspaceBanner workspaceId={linked} resource="subagents" />
      ) : (
        <div style={helpStyle}>
          Subagents are stored as <code>subagents/&lt;name&gt;.md</code> with YAML frontmatter. Link a
          Workspace under Definition → Workspace to share them across agents.
        </div>
      )}
      <div style={{ flex: 1, minHeight: 0 }}>
        <SubagentPanel onChanged={() => { void refreshAgent?.(); }} agentId={agentId} readOnly={!!linked || !canEdit} />
      </div>
    </div>
  );
}
