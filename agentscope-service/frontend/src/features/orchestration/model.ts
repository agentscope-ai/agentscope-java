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

import type { DefinitionSpec,RunGraph,RunState } from '@/api/orchestration';

export function validateDefinitionShape(spec:DefinitionSpec):string[]{
  const errors:string[]=[];const keys=new Set<string>();
  if(!spec.nodes.length)errors.push('at least one node is required');
  for(const node of spec.nodes){if(!node.key)errors.push('node key is required');else if(keys.has(node.key))errors.push(`duplicate node key: ${node.key}`);else keys.add(node.key)}
  for(const edge of spec.edges||[]){if(!keys.has(edge.from)||!keys.has(edge.to))errors.push(`unknown edge endpoint: ${edge.from} -> ${edge.to}`)}
  return errors;
}

export function allowedRunControls(state:RunState):Array<'pause'|'resume'|'cancel'>{
  if(['succeeded','partial_succeeded','failed','cancelled'].includes(state))return [];
  if(state==='planned')return ['cancel'];
  if(state==='paused')return ['resume','cancel'];
  if(state==='cancelling')return [];
  return ['pause','cancel'];
}

export function projectGraph(graph:RunGraph){
  return graph.nodes.map(node=>({id:node.id,key:node.nodeKey,state:node.state,
    incoming:graph.edges.filter(edge=>edge.toNodeId===node.id).map(edge=>edge.fromNodeId),
    outgoing:graph.edges.filter(edge=>edge.fromNodeId===node.id).map(edge=>edge.toNodeId),
    tasks:graph.tasks.filter(task=>task.runNodeId===node.id).map(task=>task.id),
    attempts:graph.attempts.filter(attempt=>attempt.nodeId===node.id).map(attempt=>attempt.id),
  }));
}
