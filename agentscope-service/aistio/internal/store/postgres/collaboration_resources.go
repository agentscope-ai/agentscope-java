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
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func (r *collaborationRepo) CreateTeam(ctx context.Context, team *controlmodel.CollaborationTeam) (*controlmodel.CollaborationTeam, error) {
	if team == nil || team.Name == "" || team.LeaderAgentRef == "" {
		return nil, fmt.Errorf("team name and leaderAgentRef are required")
	}
	team.ID = nonNilUUIDPG(team.ID)
	if team.Tenant == "" {
		team.Tenant = "default"
	}
	if team.Namespace == "" {
		team.Namespace = "default"
	}
	if team.Status == "" {
		team.Status = controlmodel.TeamActive
	}
	policy, err := json.Marshal(team.Policy)
	if err != nil {
		return nil, err
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, teamResourceError(err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	created, err := scanCollaborationTeam(tx.QueryRow(ctx, `INSERT INTO teams
		(id,tenant,namespace,name,description,instructions,status,leader_agent_id,policy,version)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,1) RETURNING `+collaborationTeamColumns,
		team.ID, team.Tenant, team.Namespace, team.Name, nullStr(team.Description),
		nullStr(team.Instructions), team.Status, team.LeaderAgentRef, policy))
	if err != nil {
		return nil, teamResourceError(err)
	}
	created.Members = make([]controlmodel.CollaborationTeamMember, 0, len(team.Members))
	for index := range team.Members {
		member := team.Members[index]
		if member.AgentRef == "" || strings.TrimSpace(member.Role) == "" || member.AgentRef == team.LeaderAgentRef {
			return nil, store.ErrConflict
		}
		member.ID = nonNilUUIDPG(member.ID)
		member.TeamID = created.ID
		member.Tenant, member.Namespace = created.Tenant, created.Namespace
		stored, memberErr := scanCollaborationMember(tx.QueryRow(ctx, `INSERT INTO team_members
			(id,tenant,namespace,team_id,agent_id,role,instructions,
			 capability_requirements,runtime_binding_policy)
			VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING `+collaborationMemberColumns,
			member.ID, member.Tenant, member.Namespace, member.TeamID, member.AgentRef,
			member.Role, nullStr(member.Instructions), nullJSON(member.CapabilityRequirements),
			nullJSON(member.RuntimeBindingPolicy)))
		if memberErr != nil {
			return nil, teamResourceError(memberErr)
		}
		created.Members = append(created.Members, *stored)
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, teamResourceError(err)
	}
	return created, nil
}

func (r *collaborationRepo) GetTeam(ctx context.Context, id uuid.UUID) (*controlmodel.CollaborationTeam, error) {
	team, err := scanCollaborationTeam(r.pool.QueryRow(ctx, `SELECT `+collaborationTeamColumns+`
		FROM teams WHERE id=$1`, id))
	if err != nil {
		return nil, teamResourceError(err)
	}
	members, err := r.listTeamMembers(ctx, id)
	if err != nil {
		return nil, teamResourceError(err)
	}
	team.Members = members
	return team, nil
}

func teamResourceError(err error) error {
	var pgErr *pgconn.PgError
	if errors.As(err, &pgErr) && pgErr.Code == "23505" {
		return store.ErrConflict
	}
	return err
}

func (r *collaborationRepo) ListTeams(ctx context.Context, tenant, namespace string) ([]*controlmodel.CollaborationTeam, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+collaborationTeamColumns+`
		FROM teams WHERE ($1='' OR tenant=$1) AND ($2='' OR namespace=$2)
		AND archived_at IS NULL ORDER BY name,id`, tenant, namespace)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.CollaborationTeam, 0)
	for rows.Next() {
		team, err := scanCollaborationTeam(rows)
		if err != nil {
			return nil, err
		}
		team.Members, err = r.listTeamMembers(ctx, team.ID)
		if err != nil {
			return nil, err
		}
		out = append(out, team)
	}
	return out, rows.Err()
}

func (r *collaborationRepo) UpdateTeam(ctx context.Context, team *controlmodel.CollaborationTeam, expectedVersion int64) (*controlmodel.CollaborationTeam, error) {
	var duplicatesLeader bool
	if err := r.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM team_members
		WHERE team_id=$1 AND agent_id=$2 AND archived_at IS NULL)`, team.ID, team.LeaderAgentRef).Scan(&duplicatesLeader); err != nil {
		return nil, err
	}
	if duplicatesLeader {
		return nil, store.ErrConflict
	}
	policy, err := json.Marshal(team.Policy)
	if err != nil {
		return nil, err
	}
	updated, err := scanCollaborationTeam(r.pool.QueryRow(ctx, `UPDATE teams SET
		name=$2,description=$3,instructions=$4,status=$5,leader_agent_id=$6,policy=$7,version=version+1,
		updated_at=now() WHERE id=$1 AND ($8<=0 OR version=$8)
		RETURNING `+collaborationTeamColumns, team.ID, team.Name,
		nullStr(team.Description), nullStr(team.Instructions), team.Status, team.LeaderAgentRef, policy, expectedVersion))
	if err == store.ErrNotFound {
		return nil, store.ErrConflict
	}
	if err != nil {
		return nil, teamResourceError(err)
	}
	if err == nil {
		updated.Members, err = r.listTeamMembers(ctx, updated.ID)
	}
	return updated, err
}

func (r *collaborationRepo) AddTeamMember(ctx context.Context, member *controlmodel.CollaborationTeamMember) (*controlmodel.CollaborationTeamMember, error) {
	if member == nil || member.TeamID == uuid.Nil || member.AgentRef == "" || member.Role == "" {
		return nil, fmt.Errorf("teamId, agentRef, and role are required")
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, teamResourceError(err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	team, err := scanCollaborationTeam(tx.QueryRow(ctx, `SELECT `+collaborationTeamColumns+` FROM teams WHERE id=$1 FOR UPDATE`, member.TeamID))
	if err != nil {
		return nil, teamResourceError(err)
	}
	if team.LeaderAgentRef == member.AgentRef {
		return nil, store.ErrConflict
	}
	member.ID = nonNilUUIDPG(member.ID)
	member.Tenant, member.Namespace = team.Tenant, team.Namespace
	created, err := scanCollaborationMember(tx.QueryRow(ctx, `INSERT INTO team_members
		(id,tenant,namespace,team_id,agent_id,role,instructions,
			 capability_requirements,runtime_binding_policy)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9) RETURNING `+collaborationMemberColumns,
		member.ID, member.Tenant, member.Namespace, member.TeamID, member.AgentRef,
		member.Role, nullStr(member.Instructions), nullJSON(member.CapabilityRequirements),
		nullJSON(member.RuntimeBindingPolicy)))
	if err != nil {
		return nil, teamResourceError(err)
	}
	if _, err = tx.Exec(ctx, `UPDATE teams SET version=version+1,updated_at=now() WHERE id=$1`, member.TeamID); err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return created, nil
}

func (r *collaborationRepo) UpdateTeamMember(ctx context.Context, member *controlmodel.CollaborationTeamMember, expectedTeamVersion int64) (*controlmodel.CollaborationTeamMember, error) {
	if member == nil || member.TeamID == uuid.Nil || member.ID == uuid.Nil || strings.TrimSpace(member.Role) == "" {
		return nil, store.ErrConflict
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var teamVersion int64
	if err = tx.QueryRow(ctx, `SELECT version FROM teams WHERE id=$1 AND archived_at IS NULL FOR UPDATE`, member.TeamID).Scan(&teamVersion); err != nil {
		if err == pgx.ErrNoRows {
			return nil, store.ErrNotFound
		}
		return nil, err
	}
	if expectedTeamVersion > 0 && teamVersion != expectedTeamVersion {
		return nil, store.ErrConflict
	}
	updated, err := scanCollaborationMember(tx.QueryRow(ctx, `UPDATE team_members SET
		role=$3,instructions=$4,capability_requirements=$5,runtime_binding_policy=$6
		WHERE id=$1 AND team_id=$2 AND archived_at IS NULL RETURNING `+collaborationMemberColumns,
		member.ID, member.TeamID, member.Role, nullStr(member.Instructions),
		nullJSON(member.CapabilityRequirements), nullJSON(member.RuntimeBindingPolicy)))
	if err != nil {
		return nil, teamResourceError(err)
	}
	if _, err = tx.Exec(ctx, `UPDATE teams SET version=version+1,updated_at=now() WHERE id=$1`, member.TeamID); err != nil {
		return nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, err
	}
	return updated, nil
}

func (r *collaborationRepo) RemoveTeamMember(ctx context.Context, teamID, memberID uuid.UUID) error {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var lockedTeamID uuid.UUID
	if err = tx.QueryRow(ctx, `SELECT id FROM teams WHERE id=$1 FOR UPDATE`, teamID).Scan(&lockedTeamID); err != nil {
		if err == pgx.ErrNoRows {
			return store.ErrNotFound
		}
		return err
	}
	tag, err := tx.Exec(ctx, `UPDATE team_members SET archived_at=now()
		WHERE id=$1 AND team_id=$2 AND archived_at IS NULL`, memberID, teamID)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	if _, err = tx.Exec(ctx, `UPDATE teams SET version=version+1,updated_at=now() WHERE id=$1`, teamID); err != nil {
		return err
	}
	return tx.Commit(ctx)
}

func (r *collaborationRepo) listTeamMembers(ctx context.Context, teamID uuid.UUID) ([]controlmodel.CollaborationTeamMember, error) {
	rows, err := r.pool.Query(ctx, `SELECT `+collaborationMemberColumns+`
		FROM team_members WHERE team_id=$1 AND archived_at IS NULL
		ORDER BY role,id`, teamID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]controlmodel.CollaborationTeamMember, 0)
	for rows.Next() {
		member, err := scanCollaborationMember(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, *member)
	}
	return out, rows.Err()
}

func (r *collaborationRepo) SweepOverdueIssues(ctx context.Context, now time.Time, limit int) (int, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `WITH overdue AS (
		SELECT id,tenant,namespace,title,
			CASE WHEN creator_type=$2 AND creator_ref IS NOT NULL THEN creator_ref
				 WHEN assignee_type=$3 AND assignee_ref IS NOT NULL THEN assignee_ref ELSE 'admin' END AS recipient
		FROM issues WHERE due_at<=$1 AND archived_at IS NULL AND status NOT IN ($4,$5)
		ORDER BY due_at,id LIMIT $6
	)
	INSERT INTO inbox_items
		(id,tenant,namespace,recipient_type,recipient_ref,type,severity,issue_id,
		 actor_type,actor_ref,title,body,dedupe_key)
	SELECT gen_random_uuid(),tenant,namespace,$3,recipient,'issue_sla_breached','warning',id,
		$7,'governance','Issue SLA breached',title,'issue-sla-breached:'||id::text
	FROM overdue
	ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING
	RETURNING id`, now, controlmodel.ActorHuman, controlmodel.AssigneeHuman,
		controlmodel.IssueDone, controlmodel.IssueCancelled, limit, controlmodel.ActorSystem)
	if err != nil {
		return 0, err
	}
	defer rows.Close()
	count := 0
	for rows.Next() {
		count++
	}
	return count, rows.Err()
}

func (r *collaborationRepo) SweepTimedOutAgentTasks(ctx context.Context, now time.Time, limit int) (int, error) {
	if limit <= 0 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `SELECT t.id,t.version FROM agent_tasks t
		JOIN orchestration_run_team_snapshots ts ON ts.run_id=t.orchestration_run_id AND ts.team_id=t.team_id
		WHERE t.status IN ($1,$2)
		AND COALESCE((ts.snapshot->'policy'->>'taskTimeoutSeconds')::bigint,0)>0
		AND COALESCE(t.started_at,t.dispatched_at,t.created_at)
			+ COALESCE((ts.snapshot->'policy'->>'taskTimeoutSeconds')::bigint,0) * interval '1 second' <= $3
		ORDER BY COALESCE(t.started_at,t.dispatched_at,t.created_at),t.id LIMIT $4`,
		controlmodel.AgentTaskRunning, controlmodel.AgentTaskDispatched, now, limit)
	if err != nil {
		return 0, err
	}
	type candidate struct {
		id      uuid.UUID
		version int64
	}
	items := make([]candidate, 0)
	for rows.Next() {
		var item candidate
		if err := rows.Scan(&item.id, &item.version); err != nil {
			rows.Close()
			return 0, err
		}
		items = append(items, item)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return 0, err
	}
	rows.Close()
	count := 0
	for _, item := range items {
		if _, err := r.FailAgentTask(ctx, item.id, item.version, "task_timeout", "Team AgentTask timeout exceeded"); err == nil {
			count++
		} else if err != store.ErrConflict {
			return count, err
		}
	}
	return count, nil
}

func (r *collaborationRepo) CreateArtifact(ctx context.Context, artifact *controlmodel.Artifact, links []controlmodel.ArtifactLink) (*controlmodel.Artifact, error) {
	if artifact == nil || artifact.StorageProvider == "" || artifact.StorageKey == "" || artifact.Filename == "" || artifact.SizeBytes < 0 || artifact.Checksum == "" {
		return nil, fmt.Errorf("artifact storageProvider, storageKey, and filename are required")
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if artifact.SourceTaskID != nil {
		var valid bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM agent_tasks WHERE id=$1 AND tenant=$2 AND namespace=$3)`,
			*artifact.SourceTaskID, artifact.Tenant, artifact.Namespace).Scan(&valid); err != nil {
			return nil, err
		}
		if !valid {
			return nil, store.ErrNotFound
		}
	}
	for _, link := range links {
		ref, parseErr := uuid.Parse(link.TargetRef)
		if parseErr != nil {
			return nil, store.ErrNotFound
		}
		var table string
		switch link.TargetType {
		case "issue":
			table = "issues"
		case "comment":
			table = "comments"
		case "agent-task":
			table = "agent_tasks"
		case "execution":
			table = "execution_attempts"
		default:
			return nil, store.ErrNotFound
		}
		var valid bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM `+table+` WHERE id=$1 AND tenant=$2 AND namespace=$3)`,
			ref, artifact.Tenant, artifact.Namespace).Scan(&valid); err != nil {
			return nil, err
		}
		if !valid {
			return nil, store.ErrNotFound
		}
	}
	artifact.ID = nonNilUUIDPG(artifact.ID)
	created, err := scanArtifact(tx.QueryRow(ctx, `INSERT INTO artifacts
		(id,tenant,namespace,storage_provider,storage_key,filename,content_type,
		 size_bytes,checksum,uploader_type,uploader_ref,source_task_id,
		 source_attempt_id,metadata,expires_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15)
		RETURNING `+artifactColumns, artifact.ID, artifact.Tenant, artifact.Namespace,
		artifact.StorageProvider, artifact.StorageKey, artifact.Filename,
		artifact.ContentType, artifact.SizeBytes, artifact.Checksum,
		artifact.Uploader.Type, nullStr(artifact.Uploader.Ref), artifact.SourceTaskID,
		artifact.SourceAttemptID, nullJSON(artifact.Metadata), artifact.ExpiresAt))
	if err != nil {
		return nil, err
	}
	for _, link := range links {
		if _, err := tx.Exec(ctx, `INSERT INTO artifact_links
			(artifact_id,target_type,target_ref,relation) VALUES ($1,$2,$3,$4)`,
			created.ID, link.TargetType, link.TargetRef, link.Relation); err != nil {
			return nil, err
		}
	}
	if err := enqueueCollaborationEventTx(ctx, tx, created.Tenant, "artifact", created.ID,
		"artifact.created.v1", created, "artifact-created:"+created.ID.String()); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return created, nil
}

func (r *collaborationRepo) GetArtifact(ctx context.Context, id uuid.UUID) (*controlmodel.Artifact, []controlmodel.ArtifactLink, error) {
	artifact, err := scanArtifact(r.pool.QueryRow(ctx, `SELECT `+artifactColumns+` FROM artifacts WHERE id=$1`, id))
	if err != nil {
		return nil, nil, err
	}
	rows, err := r.pool.Query(ctx, `SELECT artifact_id,target_type,target_ref,relation,
		created_at FROM artifact_links WHERE artifact_id=$1 ORDER BY created_at`, id)
	if err != nil {
		return nil, nil, err
	}
	defer rows.Close()
	links := make([]controlmodel.ArtifactLink, 0)
	for rows.Next() {
		var link controlmodel.ArtifactLink
		if err := rows.Scan(&link.ArtifactID, &link.TargetType, &link.TargetRef,
			&link.Relation, &link.CreatedAt); err != nil {
			return nil, nil, err
		}
		links = append(links, link)
	}
	return artifact, links, rows.Err()
}

func (r *collaborationRepo) ListArtifacts(ctx context.Context, tenant, namespace, targetType, targetRef string) ([]*controlmodel.Artifact, error) {
	rows, err := r.pool.Query(ctx, `SELECT DISTINCT `+artifactColumnsWithAlias("a")+`
		FROM artifacts a LEFT JOIN artifact_links l ON l.artifact_id=a.id
		WHERE ($1='' OR a.tenant=$1) AND ($2='' OR a.namespace=$2)
		AND (($3='' AND $4='') OR (($3='' OR l.target_type=$3) AND ($4='' OR l.target_ref=$4)))
		ORDER BY a.created_at,a.id`, tenant, namespace, targetType, targetRef)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.Artifact, 0)
	for rows.Next() {
		artifact, err := scanArtifact(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, artifact)
	}
	return out, rows.Err()
}

func artifactColumnsWithAlias(alias string) string {
	parts := strings.Split(artifactColumns, ",")
	for i := range parts {
		parts[i] = alias + "." + strings.TrimSpace(parts[i])
	}
	return strings.Join(parts, ",")
}

func (r *collaborationRepo) SubscribeIssue(ctx context.Context, subscriber *controlmodel.IssueSubscriber) (*controlmodel.IssueSubscriber, error) {
	if subscriber == nil || subscriber.IssueID == uuid.Nil || subscriber.SubscriberType == "" || subscriber.SubscriberRef == "" {
		return nil, store.ErrConflict
	}
	var valid bool
	if err := r.pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM issues WHERE id=$1 AND tenant=$2 AND namespace=$3)`, subscriber.IssueID, subscriber.Tenant, subscriber.Namespace).Scan(&valid); err != nil {
		return nil, err
	}
	if !valid {
		return nil, store.ErrNotFound
	}
	result := &controlmodel.IssueSubscriber{}
	err := r.pool.QueryRow(ctx, `INSERT INTO issue_subscribers
		(issue_id,tenant,namespace,subscriber_type,subscriber_ref)
		VALUES ($1,$2,$3,$4,$5)
		ON CONFLICT (issue_id,subscriber_type,subscriber_ref) DO UPDATE SET subscriber_ref=EXCLUDED.subscriber_ref
		RETURNING issue_id,tenant,namespace,subscriber_type,subscriber_ref,created_at`,
		subscriber.IssueID, subscriber.Tenant, subscriber.Namespace, subscriber.SubscriberType,
		subscriber.SubscriberRef).Scan(&result.IssueID, &result.Tenant, &result.Namespace,
		&result.SubscriberType, &result.SubscriberRef, &result.CreatedAt)
	return result, err
}

func (r *collaborationRepo) UnsubscribeIssue(ctx context.Context, issueID uuid.UUID, subscriberType controlmodel.AssigneeType, subscriberRef string) error {
	tag, err := r.pool.Exec(ctx, `DELETE FROM issue_subscribers WHERE issue_id=$1 AND subscriber_type=$2 AND subscriber_ref=$3`, issueID, subscriberType, subscriberRef)
	if err != nil {
		return err
	}
	if tag.RowsAffected() == 0 {
		return store.ErrNotFound
	}
	return nil
}

func (r *collaborationRepo) ListIssueSubscribers(ctx context.Context, issueID uuid.UUID) ([]*controlmodel.IssueSubscriber, error) {
	rows, err := r.pool.Query(ctx, `SELECT issue_id,tenant,namespace,subscriber_type,subscriber_ref,created_at
		FROM issue_subscribers WHERE issue_id=$1 ORDER BY created_at,subscriber_ref`, issueID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.IssueSubscriber, 0)
	for rows.Next() {
		item := &controlmodel.IssueSubscriber{}
		if err := rows.Scan(&item.IssueID, &item.Tenant, &item.Namespace, &item.SubscriberType, &item.SubscriberRef, &item.CreatedAt); err != nil {
			return nil, err
		}
		out = append(out, item)
	}
	return out, rows.Err()
}

func (r *collaborationRepo) CreateApproval(ctx context.Context, approval *controlmodel.Approval) (*controlmodel.Approval, error) {
	if approval == nil || approval.TargetType == "" || approval.TargetRef == "" || approval.ApproverRef == "" {
		return nil, store.ErrConflict
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	approval.ID = nonNilUUIDPG(approval.ID)
	if approval.Status == "" {
		approval.Status = controlmodel.ApprovalPending
	}
	created, err := scanApproval(tx.QueryRow(ctx, `INSERT INTO approvals
		(id,tenant,namespace,target_type,target_ref,issue_id,run_id,run_node_id,requested_by_type,
		 requested_by_ref,approver_ref,status,reason,request,version)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,1) RETURNING `+approvalColumns,
		approval.ID, approval.Tenant, approval.Namespace, approval.TargetType,
		approval.TargetRef, approval.IssueID, approval.RunID, approval.RunNodeID, approval.RequestedBy.Type,
		nullStr(approval.RequestedBy.Ref), approval.ApproverRef, approval.Status,
		nullStr(approval.Reason), nullJSON(approval.Request)))
	if err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `INSERT INTO inbox_items
		(id,tenant,namespace,recipient_type,recipient_ref,type,severity,issue_id,
		 approval_id,actor_type,actor_ref,title,body,dedupe_key)
		VALUES ($1,$2,$3,$4,$5,'approval','attention',$6,$7,$8,$9,'Approval requested',$10,$11)`,
		uuid.New(), created.Tenant, created.Namespace, controlmodel.AssigneeHuman,
		created.ApproverRef, created.IssueID, created.ID, created.RequestedBy.Type,
		nullStr(created.RequestedBy.Ref), nullStr(created.Reason), "approval:"+created.ID.String()); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `UPDATE inbox_items SET needs_action=true WHERE approval_id=$1`, created.ID); err != nil {
		return nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, created.Tenant, "approval", created.ID,
		"approval.requested.v1", created, "approval-requested:"+created.ID.String()); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return created, nil
}

func (r *collaborationRepo) CreateManagedToolApproval(ctx context.Context, req store.ManagedToolApprovalRequest) (*controlmodel.Approval, *controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	if req.Approval == nil {
		return nil, nil, nil, store.ErrConflict
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, nil, nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	task, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+` FROM agent_tasks WHERE id=$1 FOR UPDATE`, req.Fence.TaskID))
	if err != nil {
		return nil, nil, nil, err
	}
	attempt, err := scanExecutionAttempt(tx.QueryRow(ctx, `SELECT `+executionAttemptColumns+` FROM execution_attempts WHERE id=$1 FOR UPDATE`, req.Fence.AttemptID))
	if err != nil {
		return nil, nil, nil, err
	}
	if err = store.ValidateManagedToolApprovalFence(task, attempt, req.Fence); err != nil {
		return nil, nil, nil, err
	}
	if err = store.ValidateManagedToolApproval(req.Approval, task, req.Fence); err != nil {
		return nil, nil, nil, err
	}
	sameToolUse, sameToolUseErr := scanApproval(tx.QueryRow(ctx, `SELECT `+approvalColumns+` FROM approvals
		WHERE tenant=$1 AND namespace=$2 AND target_type=$3 AND target_ref=$4
		  AND request->>'kind'=$5 AND request->>'sessionId'=$6
		  AND request->>'agentTaskId'=$7 AND request->>'attemptId'=$8
		  AND request->>'dispatchGeneration'=$9 AND request->>'turnId'=$10
		  AND request->>'toolUseId'=$11 LIMIT 1 FOR UPDATE`,
		req.Approval.Tenant, req.Approval.Namespace, controlmodel.ApprovalTargetExecutionAttempt,
		req.Fence.AttemptID.String(), store.RuntimeToolApprovalRequestKind(req.Fence.RuntimeKind()),
		req.Fence.SessionID, req.Fence.TaskID.String(), req.Fence.AttemptID.String(),
		fmt.Sprint(req.Fence.DispatchGeneration), req.Fence.TurnID, req.Fence.ToolUseID))
	if sameToolUseErr == nil {
		if sameToolUse.ID != req.Approval.ID {
			return nil, nil, nil, store.ErrConflict
		}
		if err = store.ValidateManagedToolApproval(sameToolUse, task, req.Fence); err != nil {
			return nil, nil, nil, err
		}
		return sameToolUse, task, attempt, nil
	}
	if !errors.Is(sameToolUseErr, store.ErrNotFound) {
		return nil, nil, nil, sameToolUseErr
	}
	current, loadErr := scanApproval(tx.QueryRow(ctx, `SELECT `+approvalColumns+` FROM approvals WHERE id=$1 FOR UPDATE`, req.Approval.ID))
	if loadErr == nil {
		if current.TargetType != req.Approval.TargetType || current.TargetRef != req.Approval.TargetRef ||
			current.Tenant != req.Approval.Tenant || current.Namespace != req.Approval.Namespace {
			return nil, nil, nil, store.ErrConflict
		}
		if err = store.ValidateManagedToolApproval(current, task, req.Fence); err != nil {
			return nil, nil, nil, err
		}
		return current, task, attempt, nil
	}
	if !errors.Is(loadErr, store.ErrNotFound) {
		return nil, nil, nil, loadErr
	}
	if task.Status != controlmodel.AgentTaskRunning || attempt.State != controlmodel.ExecutionRunning {
		return nil, nil, nil, store.ErrConflict
	}
	approval := req.Approval
	approval.Status = controlmodel.ApprovalPending
	created, err := scanApproval(tx.QueryRow(ctx, `INSERT INTO approvals
		(id,tenant,namespace,target_type,target_ref,issue_id,run_id,run_node_id,requested_by_type,
		 requested_by_ref,approver_ref,status,reason,request,version)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,1) RETURNING `+approvalColumns,
		approval.ID, approval.Tenant, approval.Namespace, approval.TargetType,
		approval.TargetRef, approval.IssueID, approval.RunID, approval.RunNodeID, approval.RequestedBy.Type,
		nullStr(approval.RequestedBy.Ref), approval.ApproverRef, approval.Status,
		nullStr(approval.Reason), nullJSON(approval.Request)))
	if err != nil {
		return nil, nil, nil, err
	}
	if _, err = tx.Exec(ctx, `INSERT INTO inbox_items
		(id,tenant,namespace,recipient_type,recipient_ref,type,severity,issue_id,
		 approval_id,actor_type,actor_ref,title,body,details,dedupe_key)
		VALUES ($1,$2,$3,$4,$5,'approval','attention',$6,$7,$8,$9,'Tool approval requested',$10,$11,$12)`,
		uuid.New(), created.Tenant, created.Namespace, controlmodel.AssigneeHuman,
		created.ApproverRef, created.IssueID, created.ID, created.RequestedBy.Type,
		nullStr(created.RequestedBy.Ref), nullStr(created.Reason), nullJSON(created.Request), "approval:"+created.ID.String()); err != nil {
		return nil, nil, nil, err
	}
	task, err = scanAgentTask(tx.QueryRow(ctx, `UPDATE agent_tasks SET status=$2,wait_reason=$3,
		version=version+1 WHERE id=$1 AND status=$4 RETURNING `+agentTaskColumns,
		task.ID, controlmodel.AgentTaskWaiting, "approval:"+created.ID.String(), controlmodel.AgentTaskRunning))
	if err != nil {
		return nil, nil, nil, err
	}
	attempt, err = scanExecutionAttempt(tx.QueryRow(ctx, `UPDATE execution_attempts SET state=$2,
		version=version+1,updated_at=now() WHERE id=$1 AND state=$3
		RETURNING `+executionAttemptColumns, attempt.ID, controlmodel.ExecutionWaiting, controlmodel.ExecutionRunning))
	if err != nil {
		return nil, nil, nil, err
	}
	payload, _ := json.Marshal(map[string]any{"approvalId": created.ID, "toolUseId": req.Fence.ToolUseID})
	if err = appendRunEventTx(ctx, tx, &controlmodel.RunEvent{RunID: task.OrchestrationRunID,
		Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &task.RunNodeID,
		AgentTaskID: &task.ID, AttemptID: &attempt.ID, Type: "attempt.waiting_for_approval",
		Actor: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: task.AgentRef}, Payload: payload,
		IdempotencyKey: "attempt-waiting-approval:" + created.ID.String()}); err != nil {
		return nil, nil, nil, err
	}
	if _, err = tx.Exec(ctx, `UPDATE inbox_items SET needs_action=true WHERE approval_id=$1`, created.ID); err != nil {
		return nil, nil, nil, err
	}
	if err = enqueueCollaborationEventTx(ctx, tx, created.Tenant, "approval", created.ID,
		"approval.requested.v1", created, "approval-requested:"+created.ID.String()); err != nil {
		return nil, nil, nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, nil, nil, err
	}
	return created, task, attempt, nil
}

func (r *collaborationRepo) ResumeManagedToolApproval(ctx context.Context, approvalID uuid.UUID, fence store.ManagedToolApprovalFence, leaseTTL time.Duration) (*controlmodel.AgentTask, *controlmodel.ExecutionAttempt, error) {
	if leaseTTL <= 0 {
		return nil, nil, store.ErrConflict
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	// Match CreateManagedToolApproval's lock order (Task -> Attempt -> Approval)
	// so a replay racing with decision delivery cannot deadlock the two transactions.
	task, err := scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+` FROM agent_tasks WHERE id=$1 FOR UPDATE`, fence.TaskID))
	if err != nil {
		return nil, nil, err
	}
	attempt, err := scanExecutionAttempt(tx.QueryRow(ctx, `SELECT `+executionAttemptColumns+` FROM execution_attempts WHERE id=$1 FOR UPDATE`, fence.AttemptID))
	if err != nil {
		return nil, nil, err
	}
	approval, err := scanApproval(tx.QueryRow(ctx, `SELECT `+approvalColumns+` FROM approvals WHERE id=$1 FOR UPDATE`, approvalID))
	if err != nil {
		return nil, nil, err
	}
	if approval.Status == controlmodel.ApprovalPending {
		return nil, nil, store.ErrConflict
	}
	if err = store.ValidateManagedToolApprovalFence(task, attempt, fence); err != nil {
		return nil, nil, err
	}
	if err = store.ValidateManagedToolApproval(approval, task, fence); err != nil {
		return nil, nil, err
	}
	if task.Status == controlmodel.AgentTaskRunning && attempt.State == controlmodel.ExecutionRunning {
		if err = tx.Commit(ctx); err != nil {
			return nil, nil, err
		}
		return task, attempt, nil
	}
	if task.Status != controlmodel.AgentTaskWaiting || attempt.State != controlmodel.ExecutionWaiting ||
		task.WaitReason != "approval:"+approval.ID.String() {
		return nil, nil, store.ErrConflict
	}
	task, err = scanAgentTask(tx.QueryRow(ctx, `UPDATE agent_tasks SET status=$2,wait_reason=NULL,
		version=version+1 WHERE id=$1 RETURNING `+agentTaskColumns, task.ID, controlmodel.AgentTaskRunning))
	if err != nil {
		return nil, nil, err
	}
	attempt, err = scanExecutionAttempt(tx.QueryRow(ctx, `UPDATE execution_attempts SET state=$2,
		lease_expires_at=now()+$3::interval,heartbeat_at=now(),version=version+1,updated_at=now()
		WHERE id=$1 RETURNING `+executionAttemptColumns, attempt.ID, controlmodel.ExecutionRunning, intervalSeconds(leaseTTL)))
	if err != nil {
		return nil, nil, err
	}
	payload, _ := json.Marshal(map[string]any{"approvalId": approval.ID, "status": approval.Status})
	if err = appendRunEventTx(ctx, tx, &controlmodel.RunEvent{RunID: task.OrchestrationRunID,
		Tenant: task.Tenant, Namespace: task.Namespace, NodeID: &task.RunNodeID,
		AgentTaskID: &task.ID, AttemptID: &attempt.ID, Type: "attempt.resumed_after_approval",
		Actor: controlmodel.Actor{Type: controlmodel.ActorSystem, Ref: "approval-dispatcher"}, Payload: payload,
		IdempotencyKey: "attempt-resumed-approval:" + approval.ID.String()}); err != nil {
		return nil, nil, err
	}
	if err = tx.Commit(ctx); err != nil {
		return nil, nil, err
	}
	return task, attempt, nil
}

func (r *collaborationRepo) GetApproval(ctx context.Context, id uuid.UUID) (*controlmodel.Approval, error) {
	return scanApproval(r.pool.QueryRow(ctx, `SELECT `+approvalColumns+` FROM approvals WHERE id=$1`, id))
}

func (r *collaborationRepo) ListApprovals(ctx context.Context, filter store.ApprovalFilter) ([]*controlmodel.Approval, error) {
	limit := filter.Limit
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `SELECT `+approvalColumns+` FROM approvals WHERE
		($1='' OR tenant=$1) AND ($2='' OR namespace=$2) AND ($3='' OR approver_ref=$3)
		AND ($4='' OR target_type=$4) AND ($5='' OR target_ref=$5) AND ($6='' OR status=$6)
		AND (NOT $9 OR target_work_access_allowed(target_type,target_ref,$10::text[])) ORDER BY created_at DESC,id LIMIT $7 OFFSET $8`, filter.Tenant, filter.Namespace,
		filter.ApproverRef, filter.TargetType, filter.TargetRef, filter.Status, limit, maxInt(filter.Offset, 0), store.WorkAccessFrom(ctx).Restricted, store.WorkAccessFrom(ctx).Refs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.Approval, 0)
	for rows.Next() {
		approval, err := scanApproval(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, approval)
	}
	return out, rows.Err()
}

func (r *collaborationRepo) DecideApproval(ctx context.Context, id uuid.UUID, expectedVersion int64, status controlmodel.ApprovalStatus, actor controlmodel.Actor, decision json.RawMessage) (*controlmodel.Approval, error) {
	if status != controlmodel.ApprovalApproved && status != controlmodel.ApprovalRejected && status != controlmodel.ApprovalCancelled {
		return nil, store.ErrConflict
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	current, err := scanApproval(tx.QueryRow(ctx, `SELECT `+approvalColumns+` FROM approvals WHERE id=$1 FOR UPDATE`, id))
	if err != nil {
		return nil, err
	}
	actorAllowed := actor.Type == controlmodel.ActorHuman && actor.Ref == current.ApproverRef ||
		status == controlmodel.ApprovalCancelled && actor.Type == controlmodel.ActorSystem
	if current.Status != controlmodel.ApprovalPending || expectedVersion > 0 && current.Version != expectedVersion || !actorAllowed {
		return nil, store.ErrConflict
	}
	updated, err := scanApproval(tx.QueryRow(ctx, `UPDATE approvals SET status=$2,decision=$3,
		decided_by_type=$4,decided_by_ref=$5,version=version+1,updated_at=now(),decided_at=now()
		WHERE id=$1 RETURNING `+approvalColumns, id, status, nullJSON(decision), actor.Type, nullStr(actor.Ref)))
	if err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `UPDATE inbox_items SET archived=true,needs_action=false,resolved_at=now() WHERE approval_id=$1`, id); err != nil {
		return nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, updated.Tenant, "approval", updated.ID,
		"approval.decided.v1", updated, fmt.Sprintf("approval-decided:%s:%d", updated.ID, updated.Version)); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return updated, nil
}

func (r *collaborationRepo) ListActivities(ctx context.Context, issueID uuid.UUID, limit, offset int) ([]*controlmodel.Activity, error) {
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `SELECT `+activityColumns+` FROM activity_log
		WHERE issue_id=$1 ORDER BY created_at,id LIMIT $2 OFFSET $3`, issueID, limit,
		maxInt(offset, 0))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.Activity, 0)
	for rows.Next() {
		activity, err := scanActivity(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, activity)
	}
	return out, rows.Err()
}
