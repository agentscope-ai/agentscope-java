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

import ResolvedDefinitionFiles from '../components/ResolvedDefinitionFiles';
import { updateAgent } from '../api/agents';
import { Button } from '../components/ui/button';
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

import { useState } from 'react';
import { useOutletContext } from 'react-router-dom';
import WorkspaceFileTree from '../components/WorkspaceFileTree';
import WorkspaceEditor from '../components/WorkspaceEditor';
import LinkedWorkspaceBanner from '../components/LinkedWorkspaceBanner';
import type { AgentDefinition } from '../api/agents';

export default function AgentWorkspacePage() {
  const { agentId, agent, canEdit = false, refreshAgent } = useOutletContext<{ agentId: string; agent: AgentDefinition | null; canEdit?: boolean; refreshAgent?: () => Promise<unknown> }>();
 const [view, setView] = useState('published');
 const [publishError, setPublishError] = useState('');
 const [publishing, setPublishing] = useState(false);
  const [selected, setSelected] = useState<string | null>(null);
  const linked = agent?.workspaceId;

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0 }}>
      {linked && <LinkedWorkspaceBanner workspaceId={linked} resource="files" />}
      <div className="flex flex-wrap items-center gap-3 border-b p-3 text-sm">
        <Button size="sm" variant={view === 'published' ? 'secondary' : 'ghost'} onClick={() => setView('published')}>Published files</Button>
        {!linked && <Button size="sm" variant={view === 'draft' ? 'secondary' : 'ghost'} onClick={() => setView('draft')}>Private definition draft</Button>}
        {!linked && canEdit && <Button size="sm" disabled={publishing} onClick={async () => {
          if (!agent) return; setPublishError(''); setPublishing(true);
          try { await updateAgent(agentId, { name: agent.name, version: agent.version }); await refreshAgent?.(); setView('published'); }
          catch (e) { setPublishError(String(e)); } finally { setPublishing(false); }
        }}>{publishing ? 'Publishing…' : 'Publish private files'}</Button>}
        {publishError && <p role="alert" className="text-red-600">{publishError}</p>}
      </div>
      {(linked || view === 'published') && agent ? <ResolvedDefinitionFiles agent={agent} /> :
      <div style={{ display: 'flex', flex: 1, minHeight: 0 }}>
        <WorkspaceFileTree
          agentId={agentId}
          selectedPath={selected}
          onSelect={p => setSelected(p || null)}
        />
        <WorkspaceEditor agentId={agentId} path={selected} canEdit={canEdit} />
      </div>}
    </div>
  );
}
