import { api, apiFetch } from '@/lib/apiClient';

export interface Actor { type: 'human'|'agent'|'system'|'automation'; ref?: string }
export interface Issue { id:string; tenant:string; namespace:string; title:string; description?:string; status:string; priority:string; assigneeType?:string; assigneeRef?:string; creator:Actor; parentIssueId?:string; acceptanceCriteria?:unknown; contextRefs?:unknown; dueAt?:string; version:number; createdAt:string; updatedAt:string; resolvedAt?:string; archivedAt?:string }
export interface IssueSummary { issueId:string; title:string; status:string; commentCount:number; unresolvedThreads:number; activeTasks:number; terminalTasks:number; childCount:number; latestResult?:string; updatedAt:string }
export interface Comment { id:string; issueId:string; parentId?:string; threadRootId:string; author:Actor; content:string; type:string; sourceTaskId?:string; version:number; createdAt:string; updatedAt:string; resolvedAt?:string; deletedAt?:string; mentions?:Array<{targetType:string;targetRef:string}>; routes?:Array<{targetType:string;targetRef:string;outcome:string;reasonCode?:string;taskId?:string}> }
export interface TaskInput { id:string; commentId:string; commentVersion:number; sequence:number; state:string; attempts:number }
export interface AgentTask { id:string; tenant:string; namespace:string; issueId:string; orchestrationRunId:string; runNodeId:string; currentAttemptId?:string; agentId:string; status:string; priority:number; triggerType:string; teamId?:string; teamRole?:string; leaderTask?:boolean; originator:Actor; runtimeBinding?:unknown; sessionId?:string; result?:unknown; errorCode?:string; errorMessage?:string; version:number; createdAt:string; dispatchedAt?:string; startedAt?:string; completedAt?:string; inputs?:TaskInput[] }
export interface RuntimeBinding { agentId:string; bindingId:string; kind:'managed'|'external-application'|'hosted-runtime'; ownerRef?:string; managedDefinitionRef?:string; instanceSelector?:Record<string,string>; runtimeProfileId?:string; runtimePoolId?:string }
export interface RuntimeBindingCandidate { binding:RuntimeBinding;requiredCapabilities?:Record<string,unknown>;securityConstraints?:Record<string,unknown> }
export interface RuntimeBindingPolicy { candidates:RuntimeBindingCandidate[];selectionMode:'ordered';fallbackMode:'disabled'|'fresh';retryPolicy?:Record<string,unknown> }
export interface TeamMember { id:string; teamId:string; agentId:string; role:string; instructions?:string; runtimeBindingPolicy?:RuntimeBindingPolicy }
export interface Team { id:string; tenant:string; namespace:string; name:string; description?:string; leaderAgentId:string; policy?:unknown; version:number; createdAt:string; updatedAt:string; members?:TeamMember[] }
export interface InboxItem { id:string; type:string; severity:string; issueId?:string; commentId?:string; approvalId?:string; actor:Actor; title:string; body?:string; read:boolean; archived:boolean; createdAt:string }
export interface Approval { id:string; targetType:string; targetRef:string; issueId?:string; requestedBy:Actor; approverRef:string; status:string; reason?:string; request?:unknown; decision?:unknown; version:number; createdAt:string; updatedAt:string }
export interface Automation { id:string; tenant:string; namespace:string; name:string; description?:string; enabled:boolean; triggerType:string; triggerConfig?:unknown; actionType:string; actionConfig:unknown; nextRunAt?:string; lastRunAt?:string; version:number; createdAt:string; updatedAt:string }

function query(values:Record<string,string|number|undefined>){const p=new URLSearchParams();Object.entries(values).forEach(([k,v])=>{if(v!==undefined&&v!=='')p.set(k,String(v))});const s=p.toString();return s?`?${s}`:''}
export const listIssues=(tenant:string,namespace:string,status='',search='',archived=false)=>api.get<{items:Issue[];nextCursor?:string}>(`/api/v1/issues${query({tenant,namespace,status,search,archived:archived?'true':undefined})}`);
export const getIssue=(id:string)=>api.get<{issue:Issue}>(`/api/v1/issues/${encodeURIComponent(id)}`);
export const createIssue=(body:unknown)=>api.post<{issue:Issue;agentTask?:AgentTask}>('/api/v1/issues',body);
export const updateIssue=(id:string,body:unknown)=>api.patch<{issue:Issue}>(`/api/v1/issues/${encodeURIComponent(id)}`,body);
export const transitionIssue=(id:string,status:string,expectedVersion:number,reason='')=>api.post<{issue:Issue}>(`/api/v1/issues/${encodeURIComponent(id)}/transition`,{status,expectedVersion,reason});
export const acceptIssue=(id:string,expectedVersion:number)=>api.post<{issue:Issue}>(`/api/v1/issues/${encodeURIComponent(id)}/accept`,{expectedVersion});
export const rejectIssue=(id:string,expectedVersion:number,reason:string)=>api.post<{issue:Issue}>(`/api/v1/issues/${encodeURIComponent(id)}/reject`,{expectedVersion,reason});
export const reopenIssue=(id:string,expectedVersion:number,reason='')=>api.post<{issue:Issue}>(`/api/v1/issues/${encodeURIComponent(id)}/reopen`,{expectedVersion,reason});
export const archiveIssue=(id:string,expectedVersion:number)=>api.post<{issue:Issue}>(`/api/v1/issues/${encodeURIComponent(id)}/archive`,{expectedVersion});
export const getIssueSummary=(id:string)=>api.get<{summary:IssueSummary}>(`/api/v1/issues/${encodeURIComponent(id)}/summary`);
export const exportIssue=(id:string)=>api.get<Record<string,unknown>>(`/api/v1/issues/${encodeURIComponent(id)}/export`);
export const listComments=(issueId:string)=>api.get<{items:Comment[]}>(`/api/v1/issues/${encodeURIComponent(issueId)}/comments`);
export const addComment=(issueId:string,content:string,parentId?:string,mentions:Array<{type:string;ref:string}>=[])=>api.post<Comment>(`/api/v1/issues/${encodeURIComponent(issueId)}/comments`,{content,parentId,mentions});
export const resolveComment=(issueId:string,commentId:string,expectedVersion:number,resolved=true)=>api.post<{comment:Comment}>(`/api/v1/issues/${encodeURIComponent(issueId)}/comments/${encodeURIComponent(commentId)}/resolve`,{expectedVersion,resolved});
export const assignIssue=(id:string,assigneeType:string,assigneeRef:string,expectedVersion:number)=>api.post<{issue:Issue;agentTask?:AgentTask}>(`/api/v1/issues/${encodeURIComponent(id)}/assign`,{assigneeType,assigneeRef,expectedVersion});
export const createChildIssue=(id:string,body:unknown)=>api.post<{issue:Issue;agentTask?:AgentTask}>(`/api/v1/issues/${encodeURIComponent(id)}/children`,body);
export const listChildIssues=(tenant:string,namespace:string,parentIssueId:string)=>api.get<{items:Issue[]}>(`/api/v1/issues${query({tenant,namespace,parentIssueId})}`);
export const uploadIssueArtifact=async(tenant:string,namespace:string,issueId:string,file:File)=>{const form=new FormData();form.set('tenant',tenant);form.set('namespace',namespace);form.set('targetType','issue');form.set('targetRef',issueId);form.set('relation','attachment');form.set('file',file);return apiFetch<{artifact:{id:string;filename:string;sizeBytes:number;contentType:string}}>('/api/v1/artifacts/uploads',{method:'POST',body:form})};
export const listTasks=(tenant:string,namespace:string,status='')=>api.get<{items:AgentTask[]}>(`/api/v1/agent-tasks${query({tenant,namespace,status})}`);
export const getTask=(id:string)=>api.get<{task:AgentTask}>(`/api/v1/agent-tasks/${encodeURIComponent(id)}`);
export const cancelTask=(id:string,expectedVersion:number)=>api.post<{task:AgentTask}>(`/api/v1/agent-tasks/${encodeURIComponent(id)}/cancel`,{expectedVersion});
export const retryTask=(id:string)=>api.post<{task:AgentTask}>(`/api/v1/agent-tasks/${encodeURIComponent(id)}/retry`,{});
export const listTeams=(tenant:string,namespace:string)=>api.get<{items:Team[]}>(`/api/v1/teams${query({tenant,namespace})}`);
export const getTeam=(id:string)=>api.get<{team:Team}>(`/api/v1/teams/${encodeURIComponent(id)}`);
export const createTeam=(body:unknown)=>api.post<{team:Team}>('/api/v1/teams',body);
export const addTeamMember=(teamId:string,body:{agentId:string;role:string;instructions?:string;runtimeBindingPolicy?:RuntimeBindingPolicy})=>api.post<{member:TeamMember}>(`/api/v1/teams/${encodeURIComponent(teamId)}/members`,body);
export const removeTeamMember=(teamId:string,memberId:string)=>api.delete<void>(`/api/v1/teams/${encodeURIComponent(teamId)}/members/${encodeURIComponent(memberId)}`);
export const listInbox=(tenant:string,namespace:string)=>api.get<{items:InboxItem[]}>(`/api/v1/inbox${query({tenant,namespace})}`);
export const readInbox=(id:string)=>api.post<{item:InboxItem}>(`/api/v1/inbox/${encodeURIComponent(id)}/read`,{});
export const listApprovals=(tenant:string,namespace:string,status='pending')=>api.get<{items:Approval[]}>(`/api/v1/approvals${query({tenant,namespace,status})}`);
export const decideApproval=(id:string,status:string,expectedVersion:number,decision:unknown)=>api.post<{approval:Approval}>(`/api/v1/approvals/${encodeURIComponent(id)}/decide`,{status,expectedVersion,decision});
export const listAutomations=(tenant:string,namespace:string)=>api.get<{items:Automation[]}>(`/api/v1/automations${query({tenant,namespace})}`);
export const createAutomation=(body:unknown)=>api.post<{automation:Automation}>('/api/v1/automations',body);
