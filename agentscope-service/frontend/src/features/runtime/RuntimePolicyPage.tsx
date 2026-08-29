import { useEffect, useState, type FormEvent } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { getRuntimePolicy, listAttempts, putRuntimePolicy, type AgentRuntimePolicy } from '@/api/orchestration';
import { useControlPlaneScope } from '@/app/ScopeContext';
import { Page, PageHeader } from '@/components/Page';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import { Input, Textarea } from '@/components/ui/input';
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card';
import { formatRelative } from '@/lib/format';

const example = (tenant:string,namespace:string,agentId:string):AgentRuntimePolicy => ({tenant,namespace,agentId,selectionMode:'ordered',fallbackMode:'disabled',maxConcurrency:4,queueTimeoutSeconds:300,attemptTimeoutSeconds:3600,candidates:[{binding:{agentId,bindingId:'REPLACE_WITH_BINDING_ID',kind:'external-application'},requiredCapabilities:{},securityConstraints:{}}],retryPolicy:{infrastructure:{maxAttempts:3,backoffSeconds:5}}});

export default function RuntimePolicyPage(){
  const scope=useControlPlaneScope();const qc=useQueryClient();const [agentId,setAgentId]=useState('');const [selected,setSelected]=useState('');const [body,setBody]=useState('');const [error,setError]=useState('');
  const policy=useQuery({queryKey:['agent-runtime-policy',scope.tenant,scope.namespace,selected],queryFn:()=>getRuntimePolicy(scope.tenant,scope.namespace,selected),enabled:!!selected,retry:false});
  const attempts=useQuery({queryKey:['execution-attempt-fleet',scope.tenant,scope.namespace],queryFn:()=>listAttempts(scope.tenant,scope.namespace),refetchInterval:5000});
  useEffect(()=>{if(policy.data?.policy)setBody(JSON.stringify(policy.data.policy,null,2));},[policy.data]);
  const save=useMutation({mutationFn:(value:AgentRuntimePolicy)=>putRuntimePolicy(selected,value),onSuccess:(result)=>{setBody(JSON.stringify(result.policy,null,2));setError('');void qc.invalidateQueries({queryKey:['agent-runtime-policy']});},onError:(cause)=>setError(cause instanceof Error?cause.message:'Save failed')});
  function load(event:FormEvent){event.preventDefault();const next=agentId.trim();if(!next)return;setSelected(next);setBody(JSON.stringify(example(scope.tenant,scope.namespace,next),null,2));setError('');}
  function submit(){try{const value=JSON.parse(body) as AgentRuntimePolicy;value.tenant=scope.tenant;value.namespace=scope.namespace;value.agentId=selected;save.mutate(value);}catch{setError('Policy must be valid JSON.');}}
  const items=attempts.data?.attempts||[];
  return <Page className="max-w-[1440px]"><PageHeader title="Runtime policy & fleet" description="Ordered backend selection, fresh fallback, capability/security constraints, concurrency, and unified ExecutionAttempts across all three runtimes."/>
    <Card><CardHeader><CardTitle>Agent Runtime Policy</CardTitle><CardDescription>RunNode and Team member overrides take precedence over this durable Agent policy.</CardDescription></CardHeader><CardContent><form onSubmit={load} className="flex gap-2"><Input value={agentId} onChange={e=>setAgentId(e.target.value)} placeholder="Agent reference"/><Button type="submit">Load policy</Button></form>{selected&&<div className="mt-4"><Textarea className="min-h-72 font-mono text-xs" value={body} onChange={e=>setBody(e.target.value)}/><div className="mt-3 flex items-center gap-3"><Button onClick={submit} disabled={save.isPending}>{save.isPending?'Saving…':'Save policy'}</Button><span className="text-xs text-muted-foreground">{policy.isError?'No saved policy; edit the template and save.':''}</span></div>{error&&<p role="alert" className="mt-2 text-sm text-destructive">{error}</p>}</div>}</CardContent></Card>
    <Card><CardHeader><CardTitle>Runtime fleet</CardTitle><CardDescription>Backend-neutral attempts; provider sessions and workspaces remain scoped to their Hosted attempt.</CardDescription></CardHeader><CardContent><div className="overflow-auto"><table className="w-full min-w-[900px] text-left text-sm"><thead><tr className="border-b"><th className="py-2">Attempt</th><th>Backend</th><th>State</th><th>Task / node</th><th>Target</th><th>Updated</th></tr></thead><tbody className="divide-y">{items.map(a=><tr key={a.id}><td className="py-3 font-mono">{a.id.slice(0,10)} · #{a.attempt}</td><td>{a.backendKind}</td><td><Badge tone={['failed','cancelled'].includes(a.state)?'danger':['succeeded'].includes(a.state)?'success':'info'}>{a.state}</Badge></td><td className="font-mono text-xs">{a.agentTaskId.slice(0,8)} / {a.nodeId.slice(0,8)}</td><td className="font-mono text-xs">{a.hostId||a.agentInstanceId||a.bindingId||'queued'}</td><td>{formatRelative(a.completedAt||a.startedAt||a.createdAt)}</td></tr>)}</tbody></table></div>{!items.length&&<p className="py-6 text-center text-sm text-muted-foreground">No execution attempts in this scope.</p>}</CardContent></Card>
  </Page>;
}
