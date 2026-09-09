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
	"fmt"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func (r *collaborationRepo) CreateComment(ctx context.Context, req store.CreateCommentRequest) (*store.CreateCommentResult, error) {
	if req.Comment == nil || req.Comment.Content == "" {
		return nil, fmt.Errorf("comment content is required")
	}
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, fmt.Errorf("insert comment: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()
	issue, err := scanIssue(tx.QueryRow(ctx, `SELECT `+issueColumns+` FROM issues
		WHERE id=$1 FOR UPDATE`, req.Comment.IssueID))
	if err != nil {
		return nil, err
	}
	comment := req.Comment
	comment.ID = nonNilUUIDPG(comment.ID)
	comment.Tenant, comment.Namespace = issue.Tenant, issue.Namespace
	if comment.Type == "" {
		comment.Type = controlmodel.CommentGeneral
	}
	if comment.ParentID != nil {
		parent, err := scanComment(tx.QueryRow(ctx, `SELECT `+commentColumns+`
			FROM comments WHERE id=$1`, *comment.ParentID))
		if err != nil {
			return nil, err
		}
		if parent.IssueID != issue.ID {
			return nil, store.ErrNotFound
		}
		comment.ThreadRootID = parent.ThreadRootID
	} else {
		comment.ThreadRootID = comment.ID
	}
	created, err := scanComment(tx.QueryRow(ctx, `INSERT INTO comments
		(id,tenant,namespace,issue_id,parent_id,thread_root_id,author_type,
		 author_ref,content,type,source_task_id,source_attempt_id,version)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,1)
		RETURNING `+commentColumns, comment.ID, issue.Tenant, issue.Namespace,
		issue.ID, comment.ParentID, comment.ThreadRootID, comment.Author.Type,
		nullStr(comment.Author.Ref), comment.Content, comment.Type, comment.SourceTaskID,
		comment.SourceAttemptID))
	if err != nil {
		return nil, err
	}
	mentions := make([]controlmodel.Mention, 0, len(req.Mentions))
	for _, mention := range req.Mentions {
		mention.ID = nonNilUUIDPG(mention.ID)
		mention.Tenant, mention.Namespace, mention.IssueID, mention.CommentID = issue.Tenant, issue.Namespace, issue.ID, created.ID
		if err := tx.QueryRow(ctx, `INSERT INTO comment_mentions
			(id,tenant,namespace,issue_id,comment_id,target_type,target_ref)
			VALUES ($1,$2,$3,$4,$5,$6,$7) RETURNING created_at`, mention.ID,
			mention.Tenant, mention.Namespace, mention.IssueID, mention.CommentID,
			mention.TargetType, mention.TargetRef).Scan(&mention.CreatedAt); err != nil {
			return nil, err
		}
		mentions = append(mentions, mention)
	}
	routes := make([]controlmodel.CommentRoute, 0, len(req.Targets))
	tasks := make([]controlmodel.AgentTask, 0, len(req.Targets))
	seen := map[string]bool{}
	var sourceTask *controlmodel.AgentTask
	if created.SourceTaskID != nil {
		sourceTask, err = scanAgentTask(tx.QueryRow(ctx, `SELECT `+agentTaskColumns+` FROM agent_tasks WHERE id=$1`, *created.SourceTaskID))
		if err != nil {
			return nil, err
		}
	}
	for _, target := range req.Targets {
		key := string(target.TargetType) + "\x00" + target.TargetRef + "\x00" + target.TeamRole
		if seen[key] {
			continue
		}
		seen[key] = true
		route := controlmodel.CommentRoute{ID: uuid.New(), Tenant: issue.Tenant,
			Namespace: issue.Namespace, IssueID: issue.ID, CommentID: created.ID,
			CommentVersion: created.Version,
			TargetType:     target.TargetType, TargetRef: target.TargetRef,
			RouteType: target.RouteType}
		switch {
		case target.Blocked:
			route.Outcome, route.ReasonCode = controlmodel.RouteBlocked, target.ReasonCode
			recipient := ""
			if sourceTask != nil {
				recipient = sourceTask.AccountableHumanRef
			}
			if recipient == "" && issue.Creator.Type == controlmodel.ActorHuman {
				recipient = issue.Creator.Ref
			}
			if recipient != "" {
				if _, err := tx.Exec(ctx, `INSERT INTO inbox_items
					(id,tenant,namespace,recipient_type,recipient_ref,type,severity,issue_id,
					 comment_id,actor_type,actor_ref,title,body,dedupe_key)
					VALUES ($1,$2,$3,'human',$4,'routing_blocked','warning',$5,$6,$7,$8,
					 'Agent collaboration blocked',$9,$10)
					ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING`,
					uuid.New(), issue.Tenant, issue.Namespace, recipient, issue.ID, created.ID,
					created.Author.Type, nullStr(created.Author.Ref), target.ReasonCode,
					"route-blocked:"+created.ID.String()+":"+target.TargetRef); err != nil {
					return nil, err
				}
			}
		case target.TargetType == controlmodel.AssigneeHuman:
			route.Outcome = controlmodel.RouteQueued
			itemType, title := store.CommentInboxType(target.RouteType, false), issue.Title
			if target.RouteType == controlmodel.RouteReviewRequest {
				itemType, title = "review_request", "Review requested: "+issue.Title
			}
			if _, err := tx.Exec(ctx, `INSERT INTO inbox_items
				(id,tenant,namespace,recipient_ref,type,severity,issue_id,comment_id,
				 actor_type,actor_ref,title,body,read,archived,dedupe_key,created_at)
				VALUES ($1,$2,$3,$4,$5,'attention',$6,$7,$8,$9,$10,$11,false,false,$12,now())
				ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING`,
				uuid.New(), issue.Tenant, issue.Namespace, target.TargetRef, itemType,
				issue.ID, created.ID, created.Author.Type, nullStr(created.Author.Ref), title,
				created.Content, "comment:"+created.ID.String()+":"+target.TargetRef); err != nil {
				return nil, err
			}
		case target.AgentRef == created.Author.Ref && created.Author.Type == controlmodel.ActorAgent:
			route.Outcome, route.ReasonCode = controlmodel.RouteSuppressed, "self_trigger"
		default:
			task, _, coalesced, err := routeAgentTaskInputTx(ctx, tx, issue, created, target)
			if err != nil {
				return nil, fmt.Errorf("route comment to AgentTask: %w", err)
			}
			route.TaskID = &task.ID
			if coalesced {
				route.Outcome = controlmodel.RouteCoalesced
			} else {
				route.Outcome = controlmodel.RouteQueued
			}
			tasks = append(tasks, *task)
		}
		if err := tx.QueryRow(ctx, `INSERT INTO comment_routes
			(id,tenant,namespace,issue_id,comment_id,comment_version,target_type,target_ref,route_type,
			 outcome,task_id,reason_code) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12)
			RETURNING created_at`, route.ID, route.Tenant, route.Namespace, route.IssueID,
			route.CommentID, route.CommentVersion, route.TargetType, route.TargetRef, route.RouteType,
			route.Outcome, route.TaskID, nullStr(route.ReasonCode)).Scan(&route.CreatedAt); err != nil {
			return nil, err

		}
		routes = append(routes, route)
	}
	if _, err := tx.Exec(ctx, `INSERT INTO inbox_items
		(id,tenant,namespace,recipient_type,recipient_ref,type,severity,issue_id,
		 comment_id,actor_type,actor_ref,title,body,dedupe_key)
		SELECT gen_random_uuid(),s.tenant,s.namespace,'human',s.subscriber_ref,
			'issue_update','info',$1::uuid,$2::uuid,$3::text,$4::text,$5::text,$6::text,
			'comment:'||$2::text||':'||s.subscriber_ref
		FROM issue_subscribers s WHERE s.issue_id=$1 AND s.subscriber_type='human'
		AND $7::boolean AND NOT EXISTS (SELECT 1 FROM inbox_items d WHERE d.comment_id=$2 AND d.recipient_ref=s.subscriber_ref)
		AND NOT ($3::text='human' AND s.subscriber_ref=COALESCE($4::text,''))
		ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING`,
		issue.ID, created.ID, created.Author.Type, nullStr(created.Author.Ref), issue.Title,
		created.Content, store.NotifyCommentSubscribers(created)); err != nil {
		return nil, fmt.Errorf("notify Issue subscribers: %w", err)
	}
	if _, err := tx.Exec(ctx, `UPDATE issues SET updated_at=now(),version=version+1 WHERE id=$1`, issue.ID); err != nil {
		return nil, fmt.Errorf("touch Issue after comment: %w", err)
	}
	activity := req.Activity
	if activity == nil {
		activity = &controlmodel.Activity{Actor: created.Author, Action: "comment.created"}
	}
	activity.Tenant, activity.Namespace, activity.IssueID = issue.Tenant, issue.Namespace, &issue.ID
	activity.ObjectType, activity.ObjectRef = "comment", created.ID.String()
	if err := insertActivityTx(ctx, tx, activity); err != nil {
		return nil, fmt.Errorf("record comment activity: %w", err)
	}
	if err := enqueueCollaborationEventTx(ctx, tx, issue.Tenant, "comment", created.ID,
		"comment.created.v1", map[string]any{"comment": created, "routes": routes},
		"comment-created:"+created.ID.String()); err != nil {
		return nil, fmt.Errorf("enqueue comment event: %w", err)
	}
	for _, event := range req.Outbox {
		if event == nil {
			continue
		}
		if _, err := tx.Exec(ctx, `INSERT INTO control_outbox
			(id,tenant,aggregate_type,aggregate_id,event_type,payload,dedupe_key,
			 attempts,available_at,created_at) VALUES ($1,$2,$3,$4,$5,$6,$7,0,
			 COALESCE($8,now()),COALESCE($9,now()))
			ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING`,
			nonNilUUIDPG(event.ID), issue.Tenant, event.AggregateType, event.AggregateID,
			event.EventType, event.Payload, nullStr(event.DedupeKey), nullTime(event.AvailableAt),
			nullTime(event.CreatedAt)); err != nil {
			return nil, err
		}
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	created.Mentions, created.Routes = mentions, routes
	return &store.CreateCommentResult{Comment: created, Routes: routes, Tasks: tasks}, nil
}

func (r *collaborationRepo) GetComment(ctx context.Context, id uuid.UUID) (*controlmodel.Comment, error) {
	comment, err := scanComment(r.pool.QueryRow(ctx, `SELECT `+commentColumns+` FROM comments WHERE id=$1`, id))
	if err != nil {
		return nil, err
	}
	if err := loadCommentRelations(ctx, r.pool, comment); err != nil {
		return nil, err
	}
	return comment, nil
}

func (r *collaborationRepo) ListComments(ctx context.Context, issueID uuid.UUID, opts store.CommentListOptions) ([]*controlmodel.Comment, error) {
	limit := opts.Limit
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	if opts.Tail > 0 && opts.Tail < limit {
		limit = opts.Tail
	}
	order := " ORDER BY created_at,id"
	if opts.Tail > 0 {
		order = " ORDER BY created_at DESC,id DESC"
	}
	rows, err := r.pool.Query(ctx, `SELECT `+commentColumns+` FROM comments WHERE
		issue_id=$1 AND ($2=false OR parent_id IS NULL)
		AND ($3::uuid IS NULL OR thread_root_id=$3 OR id=$3)
		AND ($6::timestamptz IS NULL OR (created_at,id) > ($6,$7))
		`+order+` LIMIT $4 OFFSET $5`, issueID, opts.RootsOnly,
		opts.ThreadID, limit, maxInt(opts.Offset, 0), opts.CursorTime, opts.CursorID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := make([]*controlmodel.Comment, 0)
	for rows.Next() {
		comment, err := scanComment(rows)
		if err != nil {
			return nil, err
		}
		if err := loadCommentRelations(ctx, r.pool, comment); err != nil {
			return nil, err
		}
		out = append(out, comment)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	if opts.Tail > 0 {
		for i, j := 0, len(out)-1; i < j; i, j = i+1, j-1 {
			out[i], out[j] = out[j], out[i]
		}
	}
	return out, nil
}

func (r *collaborationRepo) UpdateComment(ctx context.Context, comment *controlmodel.Comment, expectedVersion int64) (*controlmodel.Comment, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	current, err := scanComment(tx.QueryRow(ctx, `SELECT `+commentColumns+` FROM comments WHERE id=$1 FOR UPDATE`, comment.ID))
	if err != nil {
		return nil, err
	}
	if expectedVersion > 0 && current.Version != expectedVersion {
		return nil, store.ErrConflict
	}
	updated, err := scanComment(tx.QueryRow(ctx, `UPDATE comments SET content=$2,
		type=$3,version=version+1,updated_at=now() WHERE id=$1 RETURNING `+commentColumns,
		comment.ID, comment.Content, comment.Type))
	if err != nil {
		return nil, err
	}
	issue, err := scanIssue(tx.QueryRow(ctx, `SELECT `+issueColumns+` FROM issues WHERE id=$1 FOR UPDATE`, updated.IssueID))
	if err != nil {
		return nil, err
	}
	rows, err := tx.Query(ctx, `SELECT DISTINCT t.agent_id,t.team_id,COALESCE(t.team_role,'')
		FROM agent_tasks t JOIN agent_task_inputs i ON i.task_id=t.id
		WHERE i.comment_id=$1 AND i.comment_version=$2`, current.ID, current.Version)
	if err != nil {
		return nil, err
	}
	targets := make([]store.CommentTarget, 0)
	for rows.Next() {
		var target store.CommentTarget
		if err := rows.Scan(&target.AgentRef, &target.TeamID, &target.TeamRole); err != nil {
			rows.Close()
			return nil, err
		}
		target.TargetType, target.TargetRef, target.RouteType = controlmodel.AssigneeAgent, target.AgentRef, controlmodel.RouteFollowUp
		if target.TeamID != nil {
			target.TargetType, target.TargetRef = controlmodel.AssigneeTeam, target.TeamID.String()
		}
		targets = append(targets, target)
	}
	rows.Close()
	for _, target := range targets {
		task, _, coalesced, err := routeAgentTaskInputTx(ctx, tx, issue, updated, target)
		if err != nil {
			return nil, err
		}
		outcome := controlmodel.RouteQueued
		if coalesced {
			outcome = controlmodel.RouteCoalesced
		}
		if _, err := tx.Exec(ctx, `INSERT INTO comment_routes
			(id,tenant,namespace,issue_id,comment_id,comment_version,target_type,target_ref,
			 route_type,outcome,task_id,reason_code)
			VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,'comment_correction')`,
			uuid.New(), updated.Tenant, updated.Namespace, updated.IssueID, updated.ID,
			updated.Version, target.TargetType, target.TargetRef, controlmodel.RouteFollowUp,
			outcome, task.ID); err != nil {
			return nil, err
		}
	}
	if _, err := tx.Exec(ctx, `UPDATE issues SET version=version+1,updated_at=now() WHERE id=$1`, issue.ID); err != nil {
		return nil, err
	}
	if err := insertActivityTx(ctx, tx, &controlmodel.Activity{Tenant: issue.Tenant, Namespace: issue.Namespace,
		IssueID: &issue.ID, Actor: updated.Author, Action: "comment.updated", ObjectType: "comment", ObjectRef: updated.ID.String()}); err != nil {
		return nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, issue.Tenant, "comment", updated.ID,
		"comment.updated.v1", updated, fmt.Sprintf("comment-updated:%s:%d", updated.ID, updated.Version)); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return r.GetComment(ctx, updated.ID)
}

func (r *collaborationRepo) DeleteComment(ctx context.Context, id uuid.UUID, expectedVersion int64, actor controlmodel.Actor) (*controlmodel.Comment, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	comment, err := scanComment(tx.QueryRow(ctx, `UPDATE comments SET content='[deleted]',deleted_at=COALESCE(deleted_at,now()),
		version=CASE WHEN deleted_at IS NULL THEN version+1 ELSE version END,updated_at=CASE WHEN deleted_at IS NULL THEN now() ELSE updated_at END
		WHERE id=$1 AND ($2<=0 OR version=$2) RETURNING `+commentColumns, id, expectedVersion))
	if err == store.ErrNotFound {
		return nil, store.ErrConflict
	}
	if err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `UPDATE issues SET version=version+1,updated_at=now() WHERE id=$1`, comment.IssueID); err != nil {
		return nil, err
	}
	if err := insertActivityTx(ctx, tx, &controlmodel.Activity{Tenant: comment.Tenant, Namespace: comment.Namespace,
		IssueID: &comment.IssueID, Actor: actor, Action: "comment.deleted", ObjectType: "comment", ObjectRef: comment.ID.String()}); err != nil {
		return nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, comment.Tenant, "comment", comment.ID,
		"comment.deleted.v1", comment, fmt.Sprintf("comment-deleted:%s:%d", comment.ID, comment.Version)); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return comment, nil
}

func (r *collaborationRepo) ResolveComment(ctx context.Context, id uuid.UUID, expectedVersion int64, actor controlmodel.Actor, resolved bool) (*controlmodel.Comment, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	var resolvedAt *time.Time
	var resolvedType, resolvedRef any
	if resolved {
		now := time.Now().UTC()
		resolvedAt, resolvedType, resolvedRef = &now, actor.Type, nullStr(actor.Ref)
	}
	comment, err := scanComment(tx.QueryRow(ctx, `UPDATE comments SET resolved_at=$2,
		resolved_by_type=$3,resolved_by_ref=$4,version=version+1,updated_at=now()
		WHERE id=$1 AND ($5<=0 OR version=$5) RETURNING `+commentColumns, id,
		resolvedAt, resolvedType, resolvedRef, expectedVersion))
	if err == store.ErrNotFound {
		return nil, store.ErrConflict
	}
	if err != nil {
		return nil, err
	}
	action := "comment.unresolved"
	if resolved {
		action = "comment.resolved"
	}
	if err := insertActivityTx(ctx, tx, &controlmodel.Activity{Tenant: comment.Tenant,
		Namespace: comment.Namespace, IssueID: &comment.IssueID, Actor: actor, Action: action,
		ObjectType: "comment", ObjectRef: comment.ID.String()}); err != nil {
		return nil, err
	}
	if err := enqueueCollaborationEventTx(ctx, tx, comment.Tenant, "comment", comment.ID,
		"comment.resolved.v1", comment, fmt.Sprintf("comment-resolved:%s:%d", comment.ID, comment.Version)); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return comment, nil
}

type commentRelationQuerier interface {
	Query(context.Context, string, ...any) (pgx.Rows, error)
}

func loadCommentRelations(ctx context.Context, q commentRelationQuerier, comment *controlmodel.Comment) error {
	rows, err := q.Query(ctx, `SELECT id,tenant,namespace,issue_id,comment_id,
		target_type,target_ref,created_at FROM comment_mentions WHERE comment_id=$1
		ORDER BY created_at,id`, comment.ID)
	if err != nil {
		return err
	}
	for rows.Next() {
		var mention controlmodel.Mention
		if err := rows.Scan(&mention.ID, &mention.Tenant, &mention.Namespace,
			&mention.IssueID, &mention.CommentID, &mention.TargetType,
			&mention.TargetRef, &mention.CreatedAt); err != nil {
			rows.Close()
			return err
		}
		comment.Mentions = append(comment.Mentions, mention)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return err
	}
	rows.Close()
	rows, err = q.Query(ctx, `SELECT id,tenant,namespace,issue_id,comment_id,comment_version,
		target_type,target_ref,route_type,outcome,task_id,reason_code,created_at
		FROM comment_routes WHERE comment_id=$1 ORDER BY created_at,id`, comment.ID)
	if err != nil {
		return err
	}
	defer rows.Close()
	for rows.Next() {
		var route controlmodel.CommentRoute
		var reason *string
		if err := rows.Scan(&route.ID, &route.Tenant, &route.Namespace, &route.IssueID,
			&route.CommentID, &route.CommentVersion, &route.TargetType, &route.TargetRef, &route.RouteType,
			&route.Outcome, &route.TaskID, &reason, &route.CreatedAt); err != nil {
			return err
		}
		route.ReasonCode = deref(reason)
		comment.Routes = append(comment.Routes, route)
	}
	return rows.Err()
}

func nonNilUUIDPG(id uuid.UUID) uuid.UUID {
	if id == uuid.Nil {
		return uuid.New()
	}
	return id
}

func nullTime(value time.Time) any {
	if value.IsZero() {
		return nil
	}
	return value
}
