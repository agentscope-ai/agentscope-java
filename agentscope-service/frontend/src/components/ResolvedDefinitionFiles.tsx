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
import { useQuery } from '@tanstack/react-query';
import { getVersion, type AgentDefinition } from '@/api/agents';

export default function ResolvedDefinitionFiles({ agent, prefix = '' }: { agent: AgentDefinition; prefix?: string }) {
  const [selected, setSelected] = useState('');
  const snapshot = useQuery({ queryKey: ['agent-definition-version', agent.id, agent.version], queryFn: () => getVersion(agent.id, agent.version!), enabled: agent.version != null });
  const files = (snapshot.data?.snapshot?.definitionFiles || {}) as Record<string, string>;
  const paths = Object.keys(files).filter(p => p.startsWith(prefix)).sort();
  const path = paths.includes(selected) ? selected : paths[0];
  return <section className="flex h-full min-h-64 flex-col"><p className="border-b bg-slate-50 px-4 py-3 text-sm text-muted-foreground">These are the published files used by this Agent. Open a session to view its messages, private working notes and task inputs.</p>{snapshot.isLoading ? <p className="p-4">Loading definition…</p> : snapshot.error ? <p role="alert" className="p-4 text-red-600">Unable to load the published definition.</p> : <div className="grid min-h-0 flex-1 grid-cols-1 sm:grid-cols-[minmax(120px,28%)_1fr]"><nav aria-label="Definition files" className="overflow-auto border-r p-2">{paths.map(p => <button key={p} className={`block w-full break-all rounded p-2 text-left text-xs ${path === p ? 'bg-indigo-50 text-indigo-700' : ''}`} onClick={() => setSelected(p)}>{p}</button>)}{!paths.length && <p className="p-2 text-sm text-muted-foreground">This Agent has no published files in this section.</p>}</nav><pre className="overflow-auto whitespace-pre-wrap break-words p-4 text-xs">{path ? files[path] : 'Choose a file to view its published content.'}</pre></div>}</section>;
}
