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

import { Badge } from '@/components/ui/badge';
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card';
import { PressureGauge } from '@/components/PressureGauge';
import { phaseHint, phaseTone, type RuntimeSession } from '../api';

const number = (value?: number) => value == null ? 'Not reported' : value.toLocaleString();
const time = (value?: string) => value ? new Date(value).toLocaleString() : 'Not reported';
function Metric({ title, children }: { title: string; children: React.ReactNode }) {
  return <Card><CardHeader className="pb-3"><CardTitle className="text-sm font-medium text-muted-foreground">{title}</CardTitle></CardHeader><CardContent className="space-y-1.5 text-sm">{children}</CardContent></Card>;
}
export function StatusStrip({ session }: { session?: RuntimeSession }) {
  const runtime = session?.runtime;
  const hosted = runtime?.kind === 'hosted-runtime';
  const hasInstance = !!session?.instanceRef || !!session?.agentInstanceId && session.agentInstanceId !== '00000000-0000-0000-0000-000000000000';
  const snapshot = session?.snapshot;
  return <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-3">
    <Metric title="Status"><Badge tone={phaseTone(session?.phase)}>{session?.phase || 'Loading'}</Badge><p className="text-xs text-muted-foreground">{phaseHint(session?.phase)}</p></Metric>
    <Metric title="Model"><p>{session?.model || 'Not reported'}</p><p className="text-xs text-muted-foreground">Last active {time(session?.lastActiveAt)}</p></Metric>
    <Metric title="Execution runtime">
      <p className="font-medium">{runtime?.kind === 'managed' ? 'Managed Agent' : hosted ? `Hosted · ${runtime?.provider || 'Provider not reported'}` : runtime?.kind === 'external-application' ? 'External application' : 'Runtime not reported'}</p>
      {hosted ? <>
        {(runtime?.profile || runtime?.pool) && <p className="text-xs text-muted-foreground">{[runtime.profile, runtime.pool].filter(Boolean).join(' · ')}</p>}
        {runtime?.hostId && <p className="break-all text-xs">Host {runtime.hostId}</p>}
        <p className="text-xs text-muted-foreground">{runtime?.source === 'execution_attempt' ? 'From this execution attempt' : 'Awaiting an execution attempt'}</p>
      </> : <>
        {(runtime?.framework || session?.framework) && <p className="text-xs">{runtime?.framework || session?.framework} {runtime?.frameworkVersion || session?.frameworkVersion}</p>}
        {hasInstance ? <div className="text-xs"><Badge tone={session?.instanceHealthy === true ? 'success' : session?.instanceHealthy === false ? 'danger' : 'default'}>{session?.instanceHealthy === true ? 'healthy' : session?.instanceHealthy === false ? 'unhealthy' : 'Health not reported'}</Badge><p className="mt-1 truncate" title={session?.instanceRef || session?.agentInstanceId}>{session?.instanceRef || session?.agentInstanceId}</p></div> : <p className="text-xs text-muted-foreground">No instance recorded for this session</p>}
      </>}
    </Metric>
    <Metric title="Context window"><PressureGauge value={snapshot?.contextPressure ?? (snapshot?.contextPressureReported ? 0 : undefined)} /><p className="text-xs text-muted-foreground">Occupancy of the current model context.</p>{snapshot?.isCompacted && <Badge>Compacted</Badge>}</Metric>
    <Metric title="Session token usage"><p className="font-mono tabular-nums">{number(snapshot?.totalTokens ?? (snapshot?.tokenUsageReported ? 0 : undefined))}</p><p className="text-xs text-muted-foreground">Input {number(snapshot?.promptTokens ?? (snapshot?.tokenUsageReported ? 0 : undefined))} · output {number(snapshot?.completionTokens ?? (snapshot?.tokenUsageReported ? 0 : undefined))}</p><p className="text-xs text-muted-foreground">Reported cumulative usage across turns.</p></Metric>
    <Metric title="Telemetry snapshot"><p>{time(snapshot?.capturedAt)}</p><p className="text-xs text-muted-foreground">{snapshot ? 'Values reflect this collection time; they may lag behind current execution.' : 'This runtime has not supplied a telemetry snapshot. Conversation events remain available below.'}</p></Metric>
  </div>;
}
