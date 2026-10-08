// Copyright 2024-2026 the original author or authors.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package postgres

import (
	"encoding/json"
	"errors"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type collaborationRepo struct{ pool *pgxpool.Pool }

const issueColumns = `id,tenant,namespace,identifier,title,description,status,priority,
	kind,visibility,completion_policy,assignee_type,assignee_ref,execution_target_type,execution_target_ref,creator_type,creator_ref,parent_issue_id,
	acceptance_criteria,context_refs,source_type,source_ref,due_at,version,
	created_at,updated_at,resolved_at,archived_at,access_policy`

func scanIssue(row scannable) (*controlmodel.Issue, error) {
	issue := &controlmodel.Issue{}
	var identifier, description, assigneeType, assigneeRef, executionTargetType, executionTargetRef, creatorRef, sourceType, sourceRef *string
	var acceptance, refs, access []byte
	err := row.Scan(&issue.ID, &issue.Tenant, &issue.Namespace, &identifier, &issue.Title,
		&description, &issue.Status, &issue.Priority, &issue.Kind, &issue.Visibility, &issue.CompletionPolicy,
		&assigneeType, &assigneeRef,
		&executionTargetType, &executionTargetRef, &issue.Creator.Type, &creatorRef, &issue.ParentIssueID, &acceptance, &refs,
		&sourceType, &sourceRef, &issue.DueAt, &issue.Version, &issue.CreatedAt,
		&issue.UpdatedAt, &issue.ResolvedAt, &issue.ArchivedAt, &access)
	if err != nil {
		return nil, collaborationScanError(err)
	}
	issue.Identifier, issue.Description = deref(identifier), deref(description)
	issue.AssigneeType, issue.AssigneeRef = controlmodel.AssigneeType(deref(assigneeType)), deref(assigneeRef)
	issue.ExecutionTargetType, issue.ExecutionTargetRef = deref(executionTargetType), deref(executionTargetRef)
	issue.Creator.Ref, issue.SourceType, issue.SourceRef = deref(creatorRef), deref(sourceType), deref(sourceRef)
	issue.AcceptanceCriteria, issue.ContextRefs = acceptance, refs
	if err := json.Unmarshal(access, &issue.Access); err != nil {
		return nil, err
	}
	return issue, nil
}

const commentColumns = `id,tenant,namespace,issue_id,parent_id,thread_root_id,
	author_type,author_ref,content,type,source_task_id,source_attempt_id,version,
	created_at,updated_at,resolved_at,resolved_by_type,resolved_by_ref,deleted_at`

func scanComment(row scannable) (*controlmodel.Comment, error) {
	comment := &controlmodel.Comment{}
	var authorRef, resolvedType, resolvedRef *string
	err := row.Scan(&comment.ID, &comment.Tenant, &comment.Namespace, &comment.IssueID,
		&comment.ParentID, &comment.ThreadRootID, &comment.Author.Type, &authorRef,
		&comment.Content, &comment.Type, &comment.SourceTaskID, &comment.SourceAttemptID,
		&comment.Version, &comment.CreatedAt, &comment.UpdatedAt, &comment.ResolvedAt,
		&resolvedType, &resolvedRef, &comment.DeletedAt)
	if err != nil {
		return nil, collaborationScanError(err)
	}
	comment.Author.Ref = deref(authorRef)
	if resolvedType != nil {
		comment.ResolvedBy = &controlmodel.Actor{Type: controlmodel.ActorType(*resolvedType), Ref: deref(resolvedRef)}
	}
	return comment, nil
}

const agentTaskColumns = `id,tenant,namespace,issue_id,orchestration_run_id,run_node_id,current_attempt_id,agent_id,status,priority,
	trigger_type,trigger_comment_id,team_id,team_role,is_leader_task,parent_task_id,
	delegated_from_task_id,retry_of_task_id,rerun_of_task_id,originator_type,
	originator_ref,accountable_human_ref,causation_id,correlation_id,hop_count,team_depth,runtime_binding,session_id,result,error_code,
	error_message,wait_reason,version,created_at,dispatched_at,started_at,completed_at`

func scanAgentTask(row scannable) (*controlmodel.AgentTask, error) {
	task := &controlmodel.AgentTask{}
	var teamRole, originatorRef, accountable, causation, correlation, sessionID, errorCode, errorMessage, waitReason *string
	var binding, result []byte
	err := row.Scan(&task.ID, &task.Tenant, &task.Namespace, &task.IssueID,
		&task.OrchestrationRunID, &task.RunNodeID, &task.CurrentAttemptID,
		&task.AgentRef, &task.Status, &task.Priority, &task.TriggerType,
		&task.TriggerCommentID, &task.TeamID, &teamRole, &task.LeaderTask,
		&task.ParentTaskID, &task.DelegatedFromTaskID, &task.RetryOfTaskID,
		&task.RerunOfTaskID, &task.Originator.Type, &originatorRef, &accountable, &causation, &correlation, &task.HopCount, &task.TeamDepth,
		&binding, &sessionID, &result, &errorCode, &errorMessage, &waitReason,
		&task.Version, &task.CreatedAt, &task.DispatchedAt, &task.StartedAt,
		&task.CompletedAt)
	if err != nil {
		return nil, collaborationScanError(err)
	}
	task.TeamRole, task.Originator.Ref = deref(teamRole), deref(originatorRef)
	task.AccountableHumanRef, task.SessionID = deref(accountable), deref(sessionID)
	task.CausationID, task.CorrelationID = deref(causation), deref(correlation)
	task.RuntimeBinding, task.Result = binding, result
	task.ErrorCode, task.ErrorMessage, task.WaitReason = deref(errorCode), deref(errorMessage), deref(waitReason)
	return task, nil
}

const taskInputColumns = `id,tenant,namespace,task_id,comment_id,comment_version,
	sequence,state,delivered_at,acknowledged_at,processed_at,response_comment_id,attempts,last_error,next_attempt_at,created_at`

func scanTaskInput(row scannable) (*controlmodel.AgentTaskInput, error) {
	input := &controlmodel.AgentTaskInput{}
	var lastError *string
	err := row.Scan(&input.ID, &input.Tenant, &input.Namespace, &input.TaskID,
		&input.CommentID, &input.CommentVersion, &input.Sequence, &input.State,
		&input.DeliveredAt, &input.AcknowledgedAt, &input.ProcessedAt,
		&input.ResponseCommentID, &input.Attempts, &lastError, &input.NextAttemptAt, &input.CreatedAt)
	if err != nil {
		return nil, collaborationScanError(err)
	}
	input.LastError = deref(lastError)
	return input, nil
}

const collaborationTeamColumns = `id,tenant,namespace,name,description,
	instructions,status,leader_agent_id,policy,version,created_at,updated_at,archived_at`

func scanCollaborationTeam(row scannable) (*controlmodel.CollaborationTeam, error) {
	team := &controlmodel.CollaborationTeam{}
	var description, instructions *string
	var policy []byte
	err := row.Scan(&team.ID, &team.Tenant, &team.Namespace, &team.Name,
		&description, &instructions, &team.Status, &team.LeaderAgentRef, &policy, &team.Version, &team.CreatedAt,
		&team.UpdatedAt, &team.ArchivedAt)
	if err != nil {
		return nil, collaborationScanError(err)
	}
	team.Description, team.Instructions = deref(description), deref(instructions)
	if len(policy) > 0 {
		if err := jsonUnmarshal(policy, &team.Policy); err != nil {
			return nil, err
		}
	}
	return team, nil
}

const collaborationMemberColumns = `id,tenant,namespace,team_id,agent_id,role,
	instructions,capability_requirements,runtime_binding_policy,created_at,archived_at`

func scanCollaborationMember(row scannable) (*controlmodel.CollaborationTeamMember, error) {
	member := &controlmodel.CollaborationTeamMember{}
	var instructions *string
	var capabilities, binding []byte
	err := row.Scan(&member.ID, &member.Tenant, &member.Namespace, &member.TeamID,
		&member.AgentRef, &member.Role, &instructions, &capabilities, &binding,
		&member.CreatedAt, &member.ArchivedAt)
	if err != nil {
		return nil, collaborationScanError(err)
	}
	member.Instructions, member.CapabilityRequirements, member.RuntimeBindingPolicy = deref(instructions), capabilities, binding
	return member, nil
}

const artifactColumns = `id,tenant,namespace,storage_provider,storage_key,filename,
	content_type,size_bytes,checksum,uploader_type,uploader_ref,source_task_id,
	source_attempt_id,metadata,created_at,expires_at`

func scanArtifact(row scannable) (*controlmodel.Artifact, error) {
	artifact := &controlmodel.Artifact{}
	var uploaderRef *string
	var metadata []byte
	err := row.Scan(&artifact.ID, &artifact.Tenant, &artifact.Namespace,
		&artifact.StorageProvider, &artifact.StorageKey, &artifact.Filename,
		&artifact.ContentType, &artifact.SizeBytes, &artifact.Checksum,
		&artifact.Uploader.Type, &uploaderRef, &artifact.SourceTaskID,
		&artifact.SourceAttemptID, &metadata, &artifact.CreatedAt, &artifact.ExpiresAt)
	if err != nil {
		return nil, collaborationScanError(err)
	}
	artifact.Uploader.Ref, artifact.Metadata = deref(uploaderRef), metadata
	return artifact, nil
}

const approvalColumns = `id,tenant,namespace,target_type,target_ref,issue_id,run_id,run_node_id,
	requested_by_type,requested_by_ref,approver_ref,status,reason,request,decision,
	decided_by_type,decided_by_ref,version,created_at,updated_at,decided_at`

func scanApproval(row scannable) (*controlmodel.Approval, error) {
	approval := &controlmodel.Approval{}
	var requestedRef, reason, decidedType, decidedRef *string
	var request, decision []byte
	err := row.Scan(&approval.ID, &approval.Tenant, &approval.Namespace,
		&approval.TargetType, &approval.TargetRef, &approval.IssueID, &approval.RunID, &approval.RunNodeID,
		&approval.RequestedBy.Type, &requestedRef, &approval.ApproverRef,
		&approval.Status, &reason, &request, &decision, &decidedType, &decidedRef,
		&approval.Version, &approval.CreatedAt, &approval.UpdatedAt, &approval.DecidedAt)
	if err != nil {
		return nil, collaborationScanError(err)
	}
	approval.RequestedBy.Ref, approval.Reason = deref(requestedRef), deref(reason)
	approval.Request, approval.Decision = request, decision
	if decidedType != nil {
		approval.DecidedBy = &controlmodel.Actor{Type: controlmodel.ActorType(*decidedType), Ref: deref(decidedRef)}
	}
	return approval, nil
}

const inboxColumns = `id,tenant,namespace,recipient_type,recipient_ref,type,severity,issue_id,
	comment_id,approval_id,actor_type,actor_ref,title,body,details,read,archived,
	dedupe_key,created_at,needs_action,read_at,resolved_at`

func scanInbox(row scannable) (*controlmodel.InboxItem, error) {
	item := &controlmodel.InboxItem{}
	var actorRef, body, dedupe *string
	var details []byte
	err := row.Scan(&item.ID, &item.Tenant, &item.Namespace, &item.RecipientType, &item.RecipientRef,
		&item.Type, &item.Severity, &item.IssueID, &item.CommentID, &item.ApprovalID,
		&item.Actor.Type, &actorRef, &item.Title, &body, &details, &item.Read,
		&item.Archived, &dedupe, &item.CreatedAt, &item.NeedsAction, &item.ReadAt, &item.ResolvedAt)
	if err != nil {
		return nil, collaborationScanError(err)
	}
	item.Actor.Ref, item.Body, item.DedupeKey, item.Details = deref(actorRef), deref(body), deref(dedupe), details
	return item, nil
}

const activityColumns = `id,tenant,namespace,issue_id,actor_type,actor_ref,
	action,object_type,object_ref,causation_id,correlation_id,details,created_at`

func scanActivity(row scannable) (*controlmodel.Activity, error) {
	activity := &controlmodel.Activity{}
	var actorRef, causation, correlation *string
	var details []byte
	err := row.Scan(&activity.ID, &activity.Tenant, &activity.Namespace,
		&activity.IssueID, &activity.Actor.Type, &actorRef, &activity.Action,
		&activity.ObjectType, &activity.ObjectRef, &causation, &correlation,
		&details, &activity.CreatedAt)
	if err != nil {
		return nil, collaborationScanError(err)
	}
	activity.Actor.Ref, activity.CausationID, activity.CorrelationID = deref(actorRef), deref(causation), deref(correlation)
	activity.Details = details
	return activity, nil
}

func collaborationScanError(err error) error {
	if errors.Is(err, pgx.ErrNoRows) {
		return store.ErrNotFound
	}
	return err
}

func jsonUnmarshal(data []byte, value any) error {
	return json.Unmarshal(data, value)
}
