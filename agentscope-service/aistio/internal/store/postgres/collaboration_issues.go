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
	"fmt"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func (r *collaborationRepo) CreateIssue(ctx context.Context, issue *controlmodel.Issue) (*controlmodel.Issue, error) {
	if issue == nil || issue.Title == "" {
		return nil, fmt.Errorf("issue title is required")
	}
	if issue.Access.Mode == "" {
		issue.Access.Mode = "private"
	}
	if err := issue.Access.Validate(); err != nil {
		return nil, err
	}
	access, _ := json.Marshal(issue.Access)
	if issue.ID == uuid.Nil {
		issue.ID = uuid.New()
	}
	if issue.Tenant == "" {
		issue.Tenant = "default"
	}
	if issue.Namespace == "" {
		issue.Namespace = "default"
	}
	if issue.Status == "" {
		issue.Status = controlmodel.IssueBacklog
	}
	if issue.Priority == "" {
		issue.Priority = "none"
	}
	if issue.Kind == "" {
		issue.Kind = controlmodel.IssueKindUserWork
	}
	if issue.Visibility == "" {
		issue.Visibility = controlmodel.IssueVisibilityWorkHub
	}
	if issue.CompletionPolicy == "" {
		issue.CompletionPolicy = controlmodel.IssueCompletionReview
	}
	if len(issue.AcceptanceCriteria) == 0 {
		issue.AcceptanceCriteria = json.RawMessage(`[]`)
	}
	if len(issue.ContextRefs) == 0 {
		issue.ContextRefs = json.RawMessage(`[]`)
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	if issue.ParentIssueID != nil {
		var valid bool
		if err := tx.QueryRow(ctx, `SELECT EXISTS (SELECT 1 FROM issues
			WHERE id=$1 AND tenant=$2 AND namespace=$3)`, *issue.ParentIssueID,
			issue.Tenant, issue.Namespace).Scan(&valid); err != nil {
			return nil, err
		}
		if !valid {
			return nil, store.ErrNotFound
		}
		var cycle bool
		if err := tx.QueryRow(ctx, `WITH RECURSIVE ancestors AS (
			SELECT id,parent_issue_id FROM issues WHERE id=$1
			UNION ALL SELECT i.id,i.parent_issue_id FROM issues i
			JOIN ancestors a ON i.id=a.parent_issue_id)
			SELECT EXISTS(SELECT 1 FROM ancestors WHERE id=$2)`, *issue.ParentIssueID, issue.ID).Scan(&cycle); err != nil {
			return nil, err
		}
		if cycle {
			return nil, store.ErrConflict
		}
	}
	created, err := scanIssue(tx.QueryRow(ctx, `INSERT INTO issues
		(id,tenant,namespace,identifier,title,description,status,priority,kind,visibility,completion_policy,
		 assignee_type,assignee_ref,execution_target_type,execution_target_ref,creator_type,creator_ref,parent_issue_id,
		 acceptance_criteria,context_refs,source_type,source_ref,due_at,version,access_policy)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,$17,$18,$19,$20,$21,$22,$23,1,$24)
		RETURNING `+issueColumns, issue.ID, issue.Tenant, issue.Namespace,
		nullStr(issue.Identifier), issue.Title, nullStr(issue.Description), issue.Status,
		issue.Priority, issue.Kind, issue.Visibility, issue.CompletionPolicy,
		nullStr(string(issue.AssigneeType)), nullStr(issue.AssigneeRef),
		nullStr(issue.ExecutionTargetType), nullStr(issue.ExecutionTargetRef), issue.Creator.Type, nullStr(issue.Creator.Ref), issue.ParentIssueID,
		issue.AcceptanceCriteria, issue.ContextRefs, nullStr(issue.SourceType),
		nullStr(issue.SourceRef), issue.DueAt, access))
	if err != nil {
		return nil, err
	}
	var parentTaskID *uuid.UUID
	var sourceTeam *controlmodel.CollaborationTeam
	if created.SourceType == "agent-task" {
		parsed, parseErr := uuid.Parse(created.SourceRef)
		if parseErr != nil {
			return nil, store.ErrNotFound
		}
		var runID uuid.UUID
		var sourceTeamID *uuid.UUID
		if err := tx.QueryRow(ctx, `SELECT orchestration_run_id,team_id FROM agent_tasks
			WHERE id=$1 AND tenant=$2 AND namespace=$3`, parsed, created.Tenant, created.Namespace).
			Scan(&runID, &sourceTeamID); err != nil {
			if err == pgx.ErrNoRows {
				return nil, store.ErrNotFound
			}
			return nil, err
		}
		if sourceTeamID != nil {
			var snapshot json.RawMessage
			if err := tx.QueryRow(ctx, `SELECT snapshot FROM orchestration_run_team_snapshots
				WHERE run_id=$1 AND team_id=$2`, runID, *sourceTeamID).Scan(&snapshot); err != nil {
				return nil, err
			}
			if err := json.Unmarshal(snapshot, &sourceTeam); err != nil {
				return nil, err
			}
		}
		parentTaskID = &parsed
	}
	var initialTask *controlmodel.AgentTask
	if created.AssigneeType == controlmodel.AssigneeAgent {
		if created.AssigneeRef == "" {
			return nil, store.ErrConflict
		}
		var teamID *uuid.UUID
		teamRole := ""
		leader := false
		if sourceTeam != nil {
			role, member := snapshotTeamAgentRolePG(sourceTeam, created.AssigneeRef)
			if !member && !sourceTeam.Policy.AllowExternalDelegation {
				return nil, store.ErrConflict
			}
			if !member {
				role = "external"
			}
			teamID, teamRole, leader = &sourceTeam.ID, role, role == "leader"
		}
		initialTask, err = createAgentTaskTx(ctx, tx, created, created.AssigneeRef,
			"assignment", nil, teamID, teamRole, leader, created.Creator, parentTaskID, nil)
	} else if created.AssigneeType == controlmodel.AssigneeTeam {
		teamID, parseErr := uuid.Parse(created.AssigneeRef)
		if parseErr != nil {
			return nil, store.ErrNotFound
		}
		team := sourceTeam
		if team == nil || team.ID != teamID {
			var loadErr error
			team, loadErr = scanCollaborationTeam(tx.QueryRow(ctx, `SELECT `+collaborationTeamColumns+`
				FROM teams WHERE id=$1 AND tenant=$2 AND namespace=$3 AND status=$4 AND archived_at IS NULL`,
				teamID, created.Tenant, created.Namespace, controlmodel.TeamActive))
			if loadErr != nil {
				return nil, loadErr
			}
		}
		initialTask, err = createAgentTaskTx(ctx, tx, created, team.LeaderAgentRef,
			"assignment", nil, &teamID, "leader", true, created.Creator, parentTaskID, nil)
	}
	if err != nil {
		return nil, err
	}
	activity := &controlmodel.Activity{Tenant: created.Tenant, Namespace: created.Namespace,
		IssueID: &created.ID, Actor: created.Creator, Action: "issue.created",
		ObjectType: "issue", ObjectRef: created.ID.String()}
	if err := insertActivityTx(ctx, tx, activity); err != nil {
		return nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, created.Tenant, "issue", created.ID,
		"issue.created.v1", map[string]any{"issue": created, "task": initialTask}, "issue-created:"+created.ID.String()); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return created, nil
}

func snapshotTeamAgentRolePG(team *controlmodel.CollaborationTeam, agentRef string) (string, bool) {
	if team == nil {
		return "", false
	}
	if team.LeaderAgentRef == agentRef {
		return "leader", true
	}
	for _, member := range team.Members {
		if member.ArchivedAt == nil && member.AgentRef == agentRef {
			return member.Role, true
		}
	}
	return "", false
}

func (r *collaborationRepo) GetIssue(ctx context.Context, id uuid.UUID) (*controlmodel.Issue, error) {
	return scanIssue(r.pool.QueryRow(ctx, `SELECT `+issueColumns+` FROM issues WHERE id=$1`, id))
}

func (r *collaborationRepo) ListIssues(ctx context.Context, filter store.IssueFilter) ([]*controlmodel.Issue, error) {
	limit := filter.Limit
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	rows, err := r.pool.Query(ctx, `SELECT `+issueColumns+` FROM issues WHERE
		($1='' OR tenant=$1) AND ($2='' OR namespace=$2)
		AND ($3='' OR status=$3) AND ($4='' OR assignee_type=$4)
		AND ($5='' OR assignee_ref=$5)
		AND ($13='' OR kind=$13) AND ($14='' OR visibility=$14)
		AND ($13='conversation_turn' OR kind <> 'conversation_turn')
		AND (NOT $15 OR issue_access_allowed(id,$16::text[]))
		AND ($6::uuid IS NULL OR parent_issue_id=$6)
		AND ($9::timestamptz IS NULL OR (updated_at,id) < ($9,$10))
		AND (($11 AND archived_at IS NOT NULL) OR (NOT $11 AND archived_at IS NULL))
		AND ($12='' OR to_tsvector('simple',COALESCE(identifier,'')||' '||title||' '||COALESCE(description,''))
			@@ websearch_to_tsquery('simple',$12) OR lower(COALESCE(identifier,''))=lower($12))
		ORDER BY updated_at DESC,id DESC LIMIT $7 OFFSET $8`, filter.Tenant,
		filter.Namespace, filter.Status, filter.AssigneeType, filter.AssigneeRef,
		filter.ParentID, limit, maxInt(filter.Offset, 0), filter.CursorTime, filter.CursorID,
		filter.Archived, strings.TrimSpace(filter.Search), filter.Kind, filter.Visibility, store.WorkAccessFrom(ctx).Restricted, store.WorkAccessFrom(ctx).Refs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.Issue, 0)
	for rows.Next() {
		issue, err := scanIssue(rows)
		if err != nil {
			return nil, err
		}
		out = append(out, issue)
	}
	return out, rows.Err()
}

func (r *collaborationRepo) ArchiveIssue(ctx context.Context, id uuid.UUID, expectedVersion int64, actor controlmodel.Actor) (*controlmodel.Issue, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	archived, err := scanIssue(tx.QueryRow(ctx, `UPDATE issues SET archived_at=now(),updated_at=now(),version=version+1
		WHERE id=$1 AND archived_at IS NULL AND status IN ($2,$3) AND ($4<=0 OR version=$4)
		RETURNING `+issueColumns, id, controlmodel.IssueDone, controlmodel.IssueCancelled, expectedVersion))
	if err != nil {
		if err == store.ErrNotFound {
			return nil, store.ErrConflict
		}
		return nil, err
	}
	if err := insertActivityTx(ctx, tx, &controlmodel.Activity{Tenant: archived.Tenant,
		Namespace: archived.Namespace, IssueID: &archived.ID, Actor: actor, Action: "issue.archived",
		ObjectType: "issue", ObjectRef: archived.ID.String()}); err != nil {
		return nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, archived.Tenant, "issue", archived.ID,
		"issue.archived.v1", archived, fmt.Sprintf("issue-archived:%s:%d", archived.ID, archived.Version)); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return archived, nil
}

func (r *collaborationRepo) UpdateIssue(ctx context.Context, issue *controlmodel.Issue, expectedVersion int64, actor controlmodel.Actor) (*controlmodel.Issue, error) {
	if issue == nil {
		return nil, store.ErrNotFound
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	access, _ := json.Marshal(issue.Access)
	updated, err := scanIssue(tx.QueryRow(ctx, `UPDATE issues SET
		title=$2,description=$3,priority=$4,acceptance_criteria=$5,context_refs=$6,
		source_type=$7,source_ref=$8,due_at=$9,assignee_type=$10,assignee_ref=$11,
		execution_target_type=$12,execution_target_ref=$13,kind=$14,visibility=$15,completion_policy=$16,access_policy=$18,
		version=version+1,updated_at=now()
		WHERE id=$1 AND ($17<=0 OR version=$17) RETURNING `+issueColumns,
		issue.ID, issue.Title, nullStr(issue.Description), issue.Priority,
		issue.AcceptanceCriteria, issue.ContextRefs, nullStr(issue.SourceType),
		nullStr(issue.SourceRef), issue.DueAt, nullStr(string(issue.AssigneeType)), nullStr(issue.AssigneeRef),
		nullStr(issue.ExecutionTargetType), nullStr(issue.ExecutionTargetRef), issue.Kind, issue.Visibility,
		issue.CompletionPolicy, expectedVersion, access))
	if err != nil {
		if err == store.ErrNotFound {
			return nil, store.ErrConflict
		}
		return nil, err
	}
	if err := insertActivityTx(ctx, tx, &controlmodel.Activity{Tenant: updated.Tenant,
		Namespace: updated.Namespace, IssueID: &updated.ID, Actor: actor,
		Action: "issue.updated", ObjectType: "issue", ObjectRef: updated.ID.String(), Details: access}); err != nil {
		return nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, updated.Tenant, "issue", updated.ID,
		"issue.updated.v1", updated, fmt.Sprintf("issue-updated:%s:%d", updated.ID, updated.Version)); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return updated, nil
}

func (r *collaborationRepo) TransitionIssue(ctx context.Context, id uuid.UUID, expectedVersion int64, status controlmodel.IssueStatus, actor controlmodel.Actor, reason string) (*controlmodel.Issue, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	current, err := scanIssue(tx.QueryRow(ctx, `SELECT `+issueColumns+` FROM issues WHERE id=$1 FOR UPDATE`, id))
	if err != nil {
		return nil, err
	}
	if expectedVersion > 0 && current.Version != expectedVersion || !controlmodel.CanTransitionIssue(current.Status, status) {
		return nil, store.ErrConflict
	}
	var resolvedAt *time.Time
	if status == controlmodel.IssueDone {
		now := time.Now().UTC()
		resolvedAt = &now
	}
	updated, err := scanIssue(tx.QueryRow(ctx, `UPDATE issues SET status=$2,
		resolved_at=$3,version=version+1,updated_at=now() WHERE id=$1 RETURNING `+issueColumns,
		id, status, resolvedAt))
	if err != nil {
		return nil, err
	}
	details, _ := json.Marshal(map[string]string{"from": string(current.Status), "to": string(status), "reason": reason})
	if err := insertActivityTx(ctx, tx, &controlmodel.Activity{Tenant: updated.Tenant,
		Namespace: updated.Namespace, IssueID: &id, Actor: actor,
		Action: "issue.status_changed", ObjectType: "issue", ObjectRef: id.String(),
		Details: details}); err != nil {
		return nil, err
	}
	if err := notifyIssueInboxTx(ctx, tx, updated, current.Status, actor, reason, nil); err != nil {
		return nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, updated.Tenant, "issue", id,
		"issue.status-changed.v1", map[string]any{"issue": updated, "previousStatus": current.Status},
		fmt.Sprintf("issue-status:%s:%d", id, updated.Version)); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return updated, nil
}

func (r *collaborationRepo) AssignIssue(ctx context.Context, id uuid.UUID, expectedVersion int64, assigneeType controlmodel.AssigneeType, assigneeRef string, actor controlmodel.Actor) (*controlmodel.Issue, *controlmodel.AgentTask, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	current, err := scanIssue(tx.QueryRow(ctx, `SELECT `+issueColumns+` FROM issues WHERE id=$1 FOR UPDATE`, id))
	if err != nil {
		return nil, nil, err
	}
	if expectedVersion > 0 && current.Version != expectedVersion {
		return nil, nil, store.ErrConflict
	}
	if current.ArchivedAt != nil ||
		((assigneeType == controlmodel.AssigneeAgent || assigneeType == controlmodel.AssigneeTeam) &&
			(current.Status == controlmodel.IssueDone || current.Status == controlmodel.IssueCancelled)) {
		return nil, nil, store.ErrConflict
	}

	agentRef, teamRole, leader := assigneeRef, "", false
	var teamID *uuid.UUID
	if assigneeType == controlmodel.AssigneeTeam {
		parsed, parseErr := uuid.Parse(assigneeRef)
		if parseErr != nil {
			return nil, nil, store.ErrNotFound
		}
		team, loadErr := scanCollaborationTeam(tx.QueryRow(ctx, `SELECT `+collaborationTeamColumns+`
			FROM teams WHERE id=$1 AND tenant=$2 AND namespace=$3 AND status=$4 AND archived_at IS NULL`,
			parsed, current.Tenant, current.Namespace, controlmodel.TeamActive))
		if loadErr != nil {
			return nil, nil, loadErr
		}
		agentRef, teamRole, leader, teamID = team.LeaderAgentRef, "leader", true, &parsed
	}
	updated, err := scanIssue(tx.QueryRow(ctx, `UPDATE issues SET assignee_type=$2,
		assignee_ref=$3,version=version+1,updated_at=now() WHERE id=$1 RETURNING `+issueColumns,
		id, nullStr(string(assigneeType)), nullStr(assigneeRef)))
	if err != nil {
		return nil, nil, err
	}
	var task *controlmodel.AgentTask
	if assigneeType == controlmodel.AssigneeAgent || assigneeType == controlmodel.AssigneeTeam {
		task, err = createAgentTaskTx(ctx, tx, updated, agentRef, "assignment", nil,
			teamID, teamRole, leader, actor, nil, nil)
		if err != nil {
			return nil, nil, err
		}
	}
	if err := insertActivityTx(ctx, tx, &controlmodel.Activity{Tenant: updated.Tenant,
		Namespace: updated.Namespace, IssueID: &id, Actor: actor, Action: "issue.assigned",
		ObjectType: string(assigneeType), ObjectRef: assigneeRef}); err != nil {
		return nil, nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, updated.Tenant, "issue", id,
		"issue.assigned.v1", map[string]any{"issue": updated, "task": task},
		fmt.Sprintf("issue-assigned:%s:%d", id, updated.Version)); err != nil {
		return nil, nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, nil, err
	}
	return updated, task, nil
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
