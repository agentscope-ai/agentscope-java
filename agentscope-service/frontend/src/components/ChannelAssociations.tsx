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

/* Copyright 2024-2026 the original author or authors. Licensed under Apache-2.0. */
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { listChannels } from '@/api/channels';
import { useControlPlaneScope } from '@/app/ScopeContext';

export default function ChannelAssociations({ targetType, targetRef }: { targetType: 'agent' | 'team'; targetRef: string }) {
  const scope = useControlPlaneScope();
  const channels = useQuery({ queryKey: ['channel-associations', scope.tenant, scope.namespace], queryFn: listChannels });
  const matches = channels.data?.filter(c => c.workTargets?.some(t => t.targetType === targetType && t.targetRef === targetRef)) || [];
  return <section className="rounded-xl border bg-white p-4 space-y-2">
    <h2 className="font-medium">Channel 工作接待</h2>
    <p className="text-sm text-muted-foreground">工作通过以下连接接入；回传范围由具体工作关联决定。</p>
    {channels.isError && <p className="text-sm text-red-700">无法读取 Channel 关联。</p>}
    {matches.map(c => <Link className="block text-sm text-primary" key={c.channelId} to={scope.scopedPath(`/agent-center/entrypoints/${encodeURIComponent(c.channelId)}`)}>{c.channelId} · {c.workEnabled ? '已启用' : '已停用'}</Link>)}
    {!channels.isLoading && !channels.isError && matches.length === 0 && <p className="text-sm text-muted-foreground">尚未配置工作接待连接。</p>}
  </section>;
}
