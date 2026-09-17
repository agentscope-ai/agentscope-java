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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package postgres

import (
	"context"
	"fmt"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func (r *collaborationRepo) ListInbox(ctx context.Context, filter store.InboxFilter) ([]*controlmodel.InboxItem, error) {
	limit := filter.Limit
	if limit <= 0 || limit > 500 {
		limit = 100
	}
	cursor, err := store.DecodeInboxCursor(filter.Cursor)
	if err != nil {
		return nil, err
	}
	where := ` WHERE ($1='' OR tenant=$1) AND ($2='' OR namespace=$2) AND recipient_ref=$3
	AND archived=$4 AND ($5='' OR type=$5)
	AND ($6<>'unread' OR NOT read) AND ($6<>'attention' OR NOT read OR needs_action)
	AND ($6<>'action' OR needs_action)`
	args := []any{filter.Tenant, filter.Namespace, filter.RecipientRef, filter.Archived, filter.Type, filter.View, limit, maxInt(filter.Offset, 0)}
	order := `created_at DESC,id`
	if filter.View == "attention" {
		order = `needs_action DESC,` + order
	}
	if cursor != nil {
		args = append(args, cursor.CreatedAt, cursor.ID)
		after := `(created_at<$9 OR (created_at=$9 AND id>$10))`
		if filter.View == "attention" {
			args = append(args, cursor.Action)
			after = `(needs_action<$11 OR (needs_action=$11 AND ` + after + `))`
		}
		where += ` AND ` + after
	}
	if access := store.WorkAccessFrom(ctx); access.Restricted {
		args = append(args, access.Refs)
		where += fmt.Sprintf(" AND (issue_id IS NULL OR issue_access_allowed(issue_id,$%d::text[]))", len(args))
	}
	rows, err := r.pool.Query(ctx, `SELECT `+inboxColumns+` FROM inbox_items`+where+` ORDER BY `+order+` LIMIT $7 OFFSET $8`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	items := make([]*controlmodel.InboxItem, 0)
	for rows.Next() {
		item, err := scanInbox(rows)
		if err != nil {
			return nil, err
		}
		items = append(items, item)
	}
	return items, rows.Err()
}

func (r *collaborationRepo) GetInbox(ctx context.Context, id uuid.UUID, recipient string) (*controlmodel.InboxItem, error) {
	return scanInbox(r.pool.QueryRow(ctx, `SELECT `+inboxColumns+` FROM inbox_items WHERE id=$1 AND recipient_ref=$2`, id, recipient))
}

func (r *collaborationRepo) InboxSummary(ctx context.Context, filter store.InboxFilter) (*controlmodel.InboxSummary, error) {
	rows, err := r.pool.Query(ctx, `SELECT type,count(*),count(*) FILTER (WHERE NOT read),
	count(*) FILTER (WHERE needs_action),count(*) FILTER (WHERE needs_action AND approval_id IS NOT NULL),
	count(*) FILTER (WHERE NOT read OR needs_action) FROM inbox_items
	WHERE ($1='' OR tenant=$1) AND ($2='' OR namespace=$2) AND recipient_ref=$3 AND NOT archived AND (NOT $4 OR issue_id IS NULL OR issue_access_allowed(issue_id,$5::text[])) GROUP BY type`, filter.Tenant, filter.Namespace, filter.RecipientRef, store.WorkAccessFrom(ctx).Restricted, store.WorkAccessFrom(ctx).Refs)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	result := &controlmodel.InboxSummary{ByType: map[string]int{}}
	for rows.Next() {
		var kind string
		var total, unread, action, pending, attention int
		if err := rows.Scan(&kind, &total, &unread, &action, &pending, &attention); err != nil {
			return nil, err
		}
		result.ByType[kind] = total
		result.Unread += unread
		result.ActionRequired += action
		result.PendingApprovals += pending
		result.AttentionTotal += attention
	}
	return result, rows.Err()
}

func (r *collaborationRepo) UpdateInbox(ctx context.Context, id uuid.UUID, recipient string, read, archived *bool) (*controlmodel.InboxItem, error) {
	tx, err := r.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer func() { _ = tx.Rollback(ctx) }()
	current, err := scanInbox(tx.QueryRow(ctx, `SELECT `+inboxColumns+` FROM inbox_items WHERE id=$1 AND recipient_ref=$2 FOR UPDATE`, id, recipient))
	if err != nil {
		return nil, err
	}
	if archived != nil && *archived && current.NeedsAction {
		return nil, store.ErrConflict
	}
	item, err := scanInbox(tx.QueryRow(ctx, `UPDATE inbox_items SET read=COALESCE($3,read),archived=COALESCE($4,archived),
	read_at=CASE WHEN $3=true THEN COALESCE(read_at,now()) WHEN $3=false THEN NULL ELSE read_at END
	WHERE id=$1 AND recipient_ref=$2 RETURNING `+inboxColumns, id, recipient, read, archived))
	if err != nil {
		return nil, err
	}
	return item, tx.Commit(ctx)
}

func notifyIssueInboxTx(ctx context.Context, tx pgx.Tx, issue *controlmodel.Issue, previous controlmodel.IssueStatus, actor controlmodel.Actor, reason string, task *controlmodel.AgentTask) error {
	// Close just the previous actionable episode, preserving the actual read state.
	if previous != issue.Status {
		if _, err := tx.Exec(ctx, `UPDATE inbox_items SET needs_action=false,archived=true,resolved_at=now()
		WHERE issue_id=$1 AND approval_id IS NULL AND type IN ('issue_blocked','review_request','agent_task_failed','routing_blocked','dead_letter') AND NOT archived`, issue.ID); err != nil {
			return err
		}
	}
	accountable := ""
	if task != nil {
		accountable = task.AccountableHumanRef
	}
	if accountable == "" {
		var owner *string
		err := tx.QueryRow(ctx, `SELECT accountable_human_ref FROM agent_tasks WHERE issue_id=$1
		AND parent_task_id IS NULL AND accountable_human_ref<>'' ORDER BY created_at DESC,id LIMIT 1`, issue.ID).Scan(&owner)
		if err != nil && err != pgx.ErrNoRows {
			return err
		}
		accountable = deref(owner)
	}
	rows, err := tx.Query(ctx, `SELECT subscriber_ref FROM issue_subscribers WHERE issue_id=$1 AND subscriber_type='human'`, issue.ID)
	if err != nil {
		return err
	}
	subscribers := []string{}
	for rows.Next() {
		var ref string
		if err = rows.Scan(&ref); err != nil {
			rows.Close()
			return err
		}
		subscribers = append(subscribers, ref)
	}
	rows.Close()
	if err = rows.Err(); err != nil {
		return err
	}
	for _, item := range store.IssueInboxItems(issue, previous, actor, reason, accountable, subscribers) {
		// A completion reply and the resulting review are one user-facing event.
		if task != nil {
			var commentID uuid.UUID
			err := tx.QueryRow(ctx, `SELECT id FROM comments WHERE source_task_id=$1 AND type='result' ORDER BY created_at DESC,id LIMIT 1`, task.ID).Scan(&commentID)
			if err != nil && err != pgx.ErrNoRows {
				return err
			}
			if err == nil {
				item.CommentID = &commentID
				if _, err = tx.Exec(ctx, `UPDATE inbox_items SET archived=true,needs_action=false,resolved_at=now()
				WHERE comment_id=$1 AND recipient_ref=$2 AND type IN ('result','reply','mention','review_request','issue_update')`, commentID, item.RecipientRef); err != nil {
					return err
				}
			}
		}
		if _, err = tx.Exec(ctx, `INSERT INTO inbox_items
		(id,tenant,namespace,recipient_type,recipient_ref,type,severity,issue_id,comment_id,actor_type,actor_ref,title,body,details,needs_action,dedupe_key,created_at)
		VALUES($1,$2,$3,'human',$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16)
		ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING`, item.ID, item.Tenant, item.Namespace, item.RecipientRef, item.Type, item.Severity, item.IssueID, item.CommentID, item.Actor.Type, nullStr(item.Actor.Ref), item.Title, item.Body, item.Details, item.NeedsAction, item.DedupeKey, item.CreatedAt); err != nil {
			return err
		}
	}
	return nil
}

func notifyTaskFailureInboxTx(ctx context.Context, tx pgx.Tx, task *controlmodel.AgentTask) error {
	issue, err := scanIssue(tx.QueryRow(ctx, `SELECT `+issueColumns+` FROM issues WHERE id=$1`, task.IssueID))
	if err != nil {
		return err
	}
	item := store.TaskFailureInbox(task, issue)
	if item == nil {
		return nil
	}
	_, err = tx.Exec(ctx, `INSERT INTO inbox_items (id,tenant,namespace,recipient_type,recipient_ref,type,severity,
	issue_id,actor_type,actor_ref,title,body,details,needs_action,dedupe_key,created_at)
	VALUES($1,$2,$3,'human',$4,$5,$6,$7,$8,$9,$10,$11,$12,true,$13,$14)
	ON CONFLICT (tenant,dedupe_key) WHERE dedupe_key IS NOT NULL DO NOTHING`, item.ID, item.Tenant, item.Namespace,
		item.RecipientRef, item.Type, item.Severity, item.IssueID, item.Actor.Type, item.Actor.Ref, item.Title, item.Body, item.Details, item.DedupeKey, item.CreatedAt)
	return err
}
