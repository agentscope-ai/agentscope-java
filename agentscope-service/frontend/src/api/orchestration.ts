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

import { api } from '@/lib/apiClient';

export type RunState = 'planned'|'running'|'waiting'|'paused'|'cancelling'|'cancelled'|'succeeded'|'partial_succeeded'|'failed';
export interface OrchestrationDefinition { id:string;tenant:string;namespace:string;name:string;description?:string;draftSpec:DefinitionSpec;draftVersion:number;version:number;createdAt:string;updatedAt:string;archivedAt?:string }
export interface DefinitionSpec { nodes:Array<Record<string,unknown>&{key:string;type:string}>;edges?:Array<{from:string;to:string;on?:string[];condition?:string;ordinal?:number}> }
export interface OrchestrationRevision { id:string;definitionId:string;revision:number;spec:DefinitionSpec;checksum:string;publishedAt:string }
export interface OrchestrationRun { id:string;tenant:string;namespace:string;rootIssueId:string;mode:string;definitionRevisionId?:string;parentRunId?:string;parentNodeId?:string;rerunOfRunId?:string;triggerType:string;input?:unknown;variables?:unknown;output?:unknown;policySnapshot?:unknown;usage?:unknown;state:RunState;waitReason?:string;failureCode?:string;failureMessage?:string;version:number;createdAt:string;startedAt?:string;completedAt?:string }
export interface RunNode { id:string;runId:string;nodeKey:string;definitionNodeKey?:string;type:string;role?:string;issueId?:string;state:string;config?:unknown;input?:unknown;output?:unknown;waitReason?:string;failureCode?:string;failureMessage?:string;version:number;startedAt?:string;completedAt?:string }
export interface RunEdge { id:string;runId:string;fromNodeId:string;toNodeId:string;onStates:string[];condition?:string;ordinal:number }
export interface ExecutionAttempt { id:string;agentTaskId:string;runId:string;nodeId:string;agentId?:string;bindingId?:string;attempt:number;dispatchGeneration:number;backendKind:string;state:string;runtimeBinding?:unknown;hostId?:string;agentInstanceId?:string;sessionId?:string;sessionRef?:string;turnId?:string;providerSessionId?:string;workspaceKey?:string;checkpoint?:unknown;result?:unknown;failureCode?:string;failureMessage?:string;usage?:unknown;createdAt:string;startedAt?:string;completedAt?:string }
export interface RunEvent { id:string;runId:string;sequence:number;nodeId?:string;agentTaskId?:string;attemptId?:string;type:string;actor:{type:string;ref?:string};payload?:unknown;occurredAt:string }
export interface RunGraph {childRuns?:OrchestrationRun[];definition?:OrchestrationDefinition;revision?:OrchestrationRevision;run:OrchestrationRun;nodes:RunNode[];edges:RunEdge[];tasks:Array<Record<string,unknown>&{id:string;agentId:string;status:string;runNodeId:string}>;attempts:ExecutionAttempt[]}

const q=(values:Record<string,string|undefined>)=>{const p=new URLSearchParams();Object.entries(values).forEach(([k,v])=>v&&p.set(k,v));const s=p.toString();return s?`?${s}`:''};
export const listDefinitions=(tenant:string,namespace:string)=>api.get<{definitions:OrchestrationDefinition[]}>(`/api/v1/orchestration-definitions${q({tenant,namespace})}`);
export const getDefinition=(id:string)=>api.get<{definition:OrchestrationDefinition}>(`/api/v1/orchestration-definitions/${id}`);
export const createDefinition=(body:unknown)=>api.post<{definition:OrchestrationDefinition}>('/api/v1/orchestration-definitions',body);
export const updateDefinition=(id:string,body:unknown)=>api.patch<{definition:OrchestrationDefinition}>(`/api/v1/orchestration-definitions/${id}`,body);
export const validateDefinition=(id:string,spec:DefinitionSpec)=>api.post<{valid:boolean;spec?:DefinitionSpec;error?:string}>(`/api/v1/orchestration-definitions/${id}/validate`,{spec});
export const publishDefinition=(id:string,expectedVersion?:number)=>api.post<{revision:OrchestrationRevision}>(`/api/v1/orchestration-definitions/${id}/publish`,{expectedVersion});
export const listRevisions=(id:string)=>api.get<{revisions:OrchestrationRevision[]}>(`/api/v1/orchestration-definitions/${id}/revisions`);
export const startRun=(id:string,body:unknown)=>api.post<{run:OrchestrationRun}>(`/api/v1/orchestration-definitions/${id}/runs`,body);
export const listRuns=(tenant:string,namespace:string,issueId?:string)=>api.get<{runs:OrchestrationRun[]}>(`/api/v1/orchestration-runs${q({tenant,namespace,issueId})}`);
export function normalizeRunGraph(graph: RunGraph): RunGraph { return {...graph, nodes:graph.nodes || [], edges:graph.edges || [], tasks:graph.tasks || [], attempts:graph.attempts || [], childRuns:graph.childRuns || []}; }
export const getRunGraph=async(id:string)=>normalizeRunGraph(await api.get<RunGraph>(`/api/v1/orchestration-runs/${id}/graph`));
export const listRunEvents=(id:string)=>api.get<{events:RunEvent[]}>(`/api/v1/orchestration-runs/${id}/events?limit=500`);
export const mutateRun=(id:string,action:'pause'|'resume'|'cancel')=>api.post<{run:OrchestrationRun}>(`/api/v1/orchestration-runs/${id}/${action}`,{});
export const rerunRun=(id:string)=>api.post<{run:OrchestrationRun}>(`/api/v1/orchestration-runs/${id}/rerun`,{idempotencyKey:crypto.randomUUID()});
export const signalRun=(id:string,name:string,payload:unknown)=>api.post<void>(`/api/v1/orchestration-runs/${id}/signals/${encodeURIComponent(name)}`,{idempotencyKey:crypto.randomUUID(),payload});
export const listAttempts=(tenant:string,namespace:string,taskId?:string)=>api.get<{attempts:ExecutionAttempt[]}>(`/api/v1/execution-attempts${q({tenant,namespace,taskId})}`);
export const listWorkflowRuns=(tenant:string,namespace:string,definitionId:string,offset=0)=>api.get<{runs:OrchestrationRun[]}>(`/api/v1/orchestration-runs${q({tenant,namespace,definitionId,offset:String(offset),limit:'100'})}`);
export const listWorkflowPage=(tenant:string,namespace:string,offset=0,archived=false)=>api.get<{definitions:OrchestrationDefinition[]}>(`/api/v1/orchestration-definitions${q({tenant,namespace,offset:String(offset),limit:'21',archived:String(archived)})}`);
