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
package product

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

// RunChannelWork recovers persisted intake and subscriptions independently of
// callback requests and Scheduler instances. Row locks allow multiple replicas.
func (s *Server) RunChannelWork(ctx context.Context) {
	if s == nil || s.channelWork == nil {
		return
	}
	ticker := time.NewTicker(time.Second)
	defer ticker.Stop()
	for {
		for i := 0; i < 50; i++ {
			worked, err := s.processChannelInbound(ctx)
			if err != nil {
				log.Printf("channel intake recovery failed: %v", err)
				break
			}
			if !worked {
				break
			}
		}
		for i := 0; i < 50; i++ {
			worked, err := s.pollChannelLink(ctx)
			if err != nil {
				log.Printf("channel subscription recovery failed: %v", err)
				break
			}
			if !worked {
				break
			}
		}
		select {
		case <-ctx.Done():
			return
		case <-ticker.C:
		}
	}
}

type channelOutcome struct {
	IssueID   *uuid.UUID
	CommentID *uuid.UUID
	Reply     string
}

func (s *Server) processChannelInbound(ctx context.Context) (bool, error) {
	tx, err := s.db.Pool.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)
	var id uuid.UUID
	var raw []byte
	var user string
	var attempts int
	var created time.Time
	err = tx.QueryRow(ctx, `SELECT id,request,user_id,attempts,created_at FROM channel_inbound WHERE status='pending' AND next_attempt<=now() ORDER BY next_attempt,id FOR UPDATE SKIP LOCKED LIMIT 1`).Scan(&id, &raw, &user, &attempts, &created)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	var in ChannelInbound
	if err = json.Unmarshal(raw, &in); err != nil {
		return false, err
	}
	ch, err := s.loadChannel(ctx, in.ChannelID)
	if err != nil {
		return false, err
	}
	result, err := s.applyChannelInbound(ctx, ch, in, user)
	if err != nil {
		state := "pending"
		if attempts >= 9 {
			state = "failed"
		}
		_, err = tx.Exec(ctx, `UPDATE channel_inbound SET attempts=attempts+1,status=$2,result='Processing failed; retry required',next_attempt=now()+interval '30 seconds',updated_at=now() WHERE id=$1`, id, state)
		if err != nil {
			return false, err
		}
		return true, tx.Commit(ctx)
	}
	if result.IssueID != nil {
		linkID := uuid.NewSHA1(id, []byte("link"))
		// Store only addressing metadata, never copy user input into subscriptions.
		address := channelAddress(in)
		_, err = tx.Exec(ctx, `INSERT INTO channel_work_links(id,channel_id,address_key,address,user_id,issue_id,cursor_time)
 VALUES($1,$2,$3,$4::jsonb,$5,$6,$7) ON CONFLICT(channel_id,address_key,user_id,issue_id) DO UPDATE SET active=true WHERE $8`, linkID, ch.ChannelID, in.addressKey(), mustJSON(address), user, *result.IssueID, created, strings.HasPrefix(strings.TrimSpace(in.Text), "/follow "))
		if err != nil {
			return false, err
		}
	}
	if result.Reply != "" {
		if err = s.enqueueChannelDelivery(ctx, tx, in, user, result.IssueID, result.CommentID, "intake:"+id.String(), result.Reply); err != nil {
			return false, err
		}
	}
	_, err = tx.Exec(ctx, `UPDATE channel_inbound SET status='done',issue_id=$2,comment_id=$3,result=$4,attempts=attempts+1,updated_at=now() WHERE id=$1`, id, result.IssueID, result.CommentID, result.Reply)
	if err != nil {
		return false, err
	}
	return true, tx.Commit(ctx)
}
func channelAddress(in ChannelInbound) ChannelInbound {
	in.Text = ""
	in.MessageID = ""
	in.ReplyToID = ""
	return in
}
func (s *Server) enqueueChannelDelivery(ctx context.Context, tx pgx.Tx, in ChannelInbound, user string, issue, comment *uuid.UUID, key, text string) error {
	_, err := tx.Exec(ctx, `INSERT INTO channel_deliveries(id,channel_id,address_key,address,user_id,issue_id,comment_id,event_key,text)
 VALUES($1,$2,$3,$4::jsonb,$5,$6,$7,$8,$9) ON CONFLICT(event_key) DO NOTHING`, uuid.NewSHA1(uuid.NameSpaceURL, []byte(key)), in.ChannelID, in.addressKey(), mustJSON(channelAddress(in)), user, issue, comment, key, text)
	return err
}
func (s *Server) identityStillBound(ctx context.Context, in ChannelInbound, user string) error {
	var ok bool
	if err := s.db.Pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM channel_identities WHERE channel_id=$1 AND account_id=$2 AND sender_id=$3 AND user_id=$4)`, in.ChannelID, in.AccountID, in.SenderID, user).Scan(&ok); err != nil {
		return err
	}
	if !ok {
		return store.ErrNotFound
	}
	return nil
}

func (s *Server) applyChannelInbound(ctx context.Context, ch channelRow, in ChannelInbound, user string) (channelOutcome, error) {
	deny := channelOutcome{Reply: "无法处理此工作。请检查账号绑定、当前空间权限和工作共享范围。"}
	if e := s.identityStillBound(ctx, in, user); e != nil {
		if errors.Is(e, store.ErrNotFound) {
			return deny, nil
		}
		return channelOutcome{}, e
	}
	n, scoped, err := s.channelActor(ctx, ch, user, true)
	if err != nil {
		if errors.Is(err, store.ErrNotFound) {
			return deny, nil
		}
		return channelOutcome{}, err
	}
	cfg, err := s.loadChannelWorkSettings(ctx, ch.ChannelID)
	if err != nil {
		return channelOutcome{}, err
	}
	if !cfg.allowsWindow(n, user, in) {
		return channelOutcome{Reply: "当前聊天窗口仅对指定用户组开放，请联系空间管理员。"}, nil
	}
	if !cfg.Enabled {
		return channelOutcome{Reply: "此 Channel 的工作接待已停用。"}, nil
	}
	repo := s.channelWork.Store.Collaboration()
	svc := collaboration.Service{Store: s.channelWork.Store}
	issueKey := uuid.NewSHA1(in.key(), []byte("issue"))
	commentKey := uuid.NewSHA1(in.key(), []byte("comment"))
	// These records and their Tasks are committed atomically by the collaboration
	// repository. A crash after that commit but before intake acknowledgement is safe.
	if existing, e := repo.GetIssue(ctx, issueKey); e == nil {
		if _, e = s.channelIssue(ctx, ch, user, existing.ID, in.PeerKind == "GROUP", true); e != nil {
			return deny, nil
		}
		return channelOutcome{IssueID: &existing.ID, Reply: channelIssueReceipt(existing, "已创建工作")}, nil
	} else if !errors.Is(e, store.ErrNotFound) {
		return channelOutcome{}, e
	}
	if existing, e := repo.GetComment(ctx, commentKey); e == nil {
		if _, e = s.channelIssue(ctx, ch, user, existing.IssueID, in.PeerKind == "GROUP", true); e != nil {
			return deny, nil
		}
		return channelOutcome{IssueID: &existing.IssueID, CommentID: &existing.ID, Reply: "补充信息已记录到工作 " + existing.IssueID.String()}, nil
	} else if !errors.Is(e, store.ErrNotFound) {
		return channelOutcome{}, e
	}
	text := strings.TrimSpace(in.Text)
	command := ""
	var selected *uuid.UUID
	var parent *uuid.UUID
	version := int64(0)
	fields := strings.Fields(text)
	if strings.HasPrefix(text, "/") {
		command = fields[0]
		switch command {
		case "/new", "/new-shared":
			text = strings.TrimSpace(strings.TrimPrefix(text, command))
			if text == "" {
				return channelOutcome{Reply: "请在创建命令后填写工作内容。"}, nil
			}
		case "/issue", "/follow", "/status", "/accept":
			if len(fields) < 2 {
				return channelOutcome{Reply: "请提供完整 Issue ID。验收格式：/accept <Issue ID> <版本号>。"}, nil
			}
			id, e := uuid.Parse(fields[1])
			if e != nil {
				return channelOutcome{Reply: "Issue ID 格式错误。"}, nil
			}
			selected = &id
			text = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(strings.TrimPrefix(text, command)), fields[1]))
			if command == "/accept" {
				if len(fields) != 3 {
					return channelOutcome{Reply: "验收格式：/accept <Issue ID> <版本号>。"}, nil
				}
				version, e = strconv.ParseInt(fields[2], 10, 64)
				if e != nil || version < 1 {
					return channelOutcome{Reply: "验收需要当前工作版本号。"}, nil
				}
			}
		default:
			return channelOutcome{Reply: "支持 /new、/new-shared、/issue <ID> 补充信息、/follow <ID>、/status <ID> 和 /accept <ID> <版本号>。"}, nil
		}
	}
	if selected == nil && command != "/new" && command != "/new-shared" {
		// Reply anchors are scoped to a provider organization and conversation, and
		// may establish a new thread address within that same conversation.
		if in.ReplyToID != "" {
			var found uuid.UUID
			var comment *uuid.UUID
			err = s.db.Pool.QueryRow(ctx, `SELECT issue_id,comment_id FROM channel_deliveries WHERE channel_id=$1 AND address->>'accountId'=$2 AND address->>'peerId'=$3 AND provider_message_id=$4 AND issue_id IS NOT NULL ORDER BY created_at DESC LIMIT 1`, ch.ChannelID, in.AccountID, in.PeerID, in.ReplyToID).Scan(&found, &comment)
			if errors.Is(err, pgx.ErrNoRows) {
				err = s.db.Pool.QueryRow(ctx, `SELECT issue_id,comment_id FROM channel_inbound WHERE channel_id=$1 AND request->>'accountId'=$2 AND request->>'peerId'=$3 AND request->>'messageId'=$4 AND issue_id IS NOT NULL LIMIT 1`, ch.ChannelID, in.AccountID, in.PeerID, in.ReplyToID).Scan(&found, &comment)
			}
			if err == nil {
				selected = &found
				parent = comment
			} else if !errors.Is(err, pgx.ErrNoRows) {
				return channelOutcome{}, err
			} else {
				return channelOutcome{Reply: "无法确认被回复消息对应的工作，请使用 /issue <ID> 补充信息，或 /new 创建新工作。"}, nil
			}
		} else {
			rows, e := s.db.Pool.Query(ctx, `SELECT DISTINCT issue_id FROM channel_work_links WHERE channel_id=$1 AND address_key=$2 AND active=true`, ch.ChannelID, in.addressKey())
			if e != nil {
				return channelOutcome{}, e
			}
			ids := []uuid.UUID{}
			for rows.Next() {
				var id uuid.UUID
				if e = rows.Scan(&id); e != nil {
					rows.Close()
					return channelOutcome{}, e
				}
				ids = append(ids, id)
			}
			e = rows.Err()
			rows.Close()
			if e != nil {
				return channelOutcome{}, e
			}
			for _, id := range ids {
				issue, e := s.channelIssue(ctx, ch, user, id, in.PeerKind == "GROUP", false)
				if e != nil {
					continue
				}
				if issue.Status == model.IssueDone || issue.Status == model.IssueCancelled {
					continue
				}
				if selected != nil {
					return channelOutcome{Reply: "此会话关联多个工作。请回复具体工作消息，或使用 /issue <ID> 补充信息；创建新工作请使用 /new。"}, nil
				}
				copyID := id
				selected = &copyID
			}
		}
	}
	if selected != nil && command != "" && in.ReplyToID != "" {
		var anchor uuid.UUID
		e := s.db.Pool.QueryRow(ctx, `SELECT issue_id FROM channel_deliveries WHERE channel_id=$1 AND address->>'accountId'=$2 AND address->>'peerId'=$3 AND provider_message_id=$4 AND issue_id IS NOT NULL LIMIT 1`, ch.ChannelID, in.AccountID, in.PeerID, in.ReplyToID).Scan(&anchor)
		if errors.Is(e, pgx.ErrNoRows) {
			e = s.db.Pool.QueryRow(ctx, `SELECT issue_id FROM channel_inbound WHERE channel_id=$1 AND request->>'accountId'=$2 AND request->>'peerId'=$3 AND request->>'messageId'=$4 AND issue_id IS NOT NULL LIMIT 1`, ch.ChannelID, in.AccountID, in.PeerID, in.ReplyToID).Scan(&anchor)
		}
		if e != nil && !errors.Is(e, pgx.ErrNoRows) {
			return channelOutcome{}, e
		}
		if e != nil || anchor != *selected {
			return channelOutcome{Reply: "回复消息与指定的 Issue 无法匹配，请确认后重新发送。"}, nil
		}
	}
	actor := model.Actor{Type: model.ActorHuman, Ref: user}
	if selected != nil {
		issue, e := s.channelIssue(ctx, ch, user, *selected, in.PeerKind == "GROUP", command != "/status" && command != "/follow")
		if e != nil {
			return deny, nil
		}
		if in.PeerKind == "GROUP" {
			published, e := s.channelGroupPublished(ctx, in, issue)
			if e != nil {
				return channelOutcome{}, e
			}
			if !cfg.AllowGroupWork || (!published && !(command == "/follow" && issue.Creator.Type == model.ActorHuman && issue.Creator.Ref == user)) {
				return deny, nil
			}
		}
		switch command {
		case "/status", "/follow":
			return channelOutcome{IssueID: &issue.ID, Reply: channelIssueReceipt(issue, "工作状态")}, nil
		case "/accept":
			// Channel acceptance is an explicit creator action, never inferred from thanks.
			if issue.Creator.Type != model.ActorHuman || issue.Creator.Ref != user {
				return deny, nil
			}
			if issue.Status == model.IssueDone && issue.Version == version+1 {
				return channelOutcome{IssueID: &issue.ID, Reply: channelIssueReceipt(issue, "工作已验收")}, nil
			}
			next, e := svc.TransitionIssue(scoped, issue.ID, version, model.IssueDone, actor, "Explicit Channel acceptance")
			if e != nil {
				return channelOutcome{Reply: "当前工作无法验收：状态或版本已变化，或仍有未完成任务／审批。请先查询 /status " + issue.ID.String()}, nil
			}
			return channelOutcome{IssueID: &next.ID, Reply: channelIssueReceipt(next, "工作已验收")}, nil
		}
		if text == "" {
			return channelOutcome{Reply: "请填写需要补充的信息。"}, nil
		}
		result, e := svc.AddComment(scoped, collaboration.AddCommentRequest{ID: commentKey, IssueID: issue.ID, ParentID: parent, Author: actor, Content: text})
		if e != nil {
			return channelOutcome{}, e
		}
		return channelOutcome{IssueID: &issue.ID, CommentID: &result.Comment.ID, Reply: "补充信息已记录到工作 " + issue.ID.String()}, nil
	}
	if in.PeerKind == "GROUP" && (!cfg.AllowGroupWork || command != "/new-shared") {
		return channelOutcome{Reply: "群组创建工作需要启用群接待，并使用 /new-shared 工作内容。该工作将对当前 namespace 成员可见，并在此群回传结果。"}, nil
	}
	target := cfg.route(in)
	if s.channelWork.AuthorizeTarget != nil {
		if e := s.channelWork.AuthorizeTarget(ctx, n, user, target.TargetType, target.TargetRef); e != nil {
			return channelOutcome{Reply: "当前账号没有接待资源或其依赖的使用权限，请向空间管理员申请。"}, nil
		}
	}
	if err = s.channelWork.Target(ctx, n, target.TargetType, target.TargetRef); err != nil {
		return channelOutcome{Reply: "接待对象不可用，请检查同一空间内的 Agent／Team 配置。"}, nil
	}
	access := model.IssueAccess{Mode: "private"}
	if command == "/new-shared" {
		access.Mode = "namespace"
	}
	title := []rune(text)
	if len(title) > 160 {
		title = title[:160]
	}
	issue, _, err := svc.CreateIssue(scoped, collaboration.CreateIssueRequest{ID: issueKey, Tenant: n.Tenant, Namespace: n.Name, Title: string(title), Description: text, Creator: actor, Access: access, AssigneeType: model.AssigneeType(target.TargetType), AssigneeRef: target.TargetRef, SourceType: "channel", SourceRef: ch.ChannelID + ":" + in.key().String()})
	if err != nil {
		return channelOutcome{}, err
	}
	return channelOutcome{IssueID: &issue.ID, Reply: channelIssueReceipt(issue, "已创建工作")}, nil
}
func channelIssueReceipt(v *model.Issue, prefix string) string {
	return fmt.Sprintf("%s：%s\nIssue: %s\n状态：%s · 版本：%d\n回复此消息可补充信息。验收请发送 /accept %s %d", prefix, v.Title, v.ID, v.Status, v.Version, v.ID, v.Version)
}

func (s *Server) pollChannelLink(ctx context.Context) (bool, error) {
	tx, err := s.db.Pool.Begin(ctx)
	if err != nil {
		return false, err
	}
	defer tx.Rollback(ctx)
	var id, issueID, cursorID uuid.UUID
	var chID, user, last string
	var raw []byte
	var cursor time.Time
	err = tx.QueryRow(ctx, `SELECT id,channel_id,address,user_id,issue_id,cursor_time,cursor_id,last_status FROM channel_work_links WHERE active=true AND next_poll<=now() ORDER BY next_poll,id FOR UPDATE SKIP LOCKED LIMIT 1`).Scan(&id, &chID, &raw, &user, &issueID, &cursor, &cursorID, &last)
	if errors.Is(err, pgx.ErrNoRows) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	var in ChannelInbound
	if err = json.Unmarshal(raw, &in); err != nil {
		return false, err
	}
	ch, err := s.loadChannel(ctx, chID)
	if err != nil {
		return false, err
	}
	cfg, err := s.loadChannelWorkSettings(ctx, chID)
	if err != nil {
		return false, err
	}
	issue, accessErr := s.channelIssue(ctx, ch, user, issueID, in.PeerKind == "GROUP", false)
	identityErr := s.identityStillBound(ctx, in, user)
	if accessErr != nil && !errors.Is(accessErr, store.ErrNotFound) {
		return false, accessErr
	}
	if identityErr != nil && !errors.Is(identityErr, store.ErrNotFound) {
		return false, identityErr
	}
	if !cfg.Enabled || accessErr != nil || identityErr != nil {
		// Keep the subscription but never queue content while its principal or ACL is revoked.
		_, err = tx.Exec(ctx, `UPDATE channel_work_links SET next_poll=now()+interval '30 seconds' WHERE id=$1`, id)
		if err != nil {
			return false, err
		}
		return true, tx.Commit(ctx)
	}
	repo := s.channelWork.Store.Collaboration()
	comments, err := repo.ListComments(ctx, issueID, store.CommentListOptions{Limit: 100, CursorTime: &cursor, CursorID: cursorID})
	if err != nil {
		return false, err
	}
	for _, c := range comments {
		if c.DeletedAt == nil && c.Author.Type != model.ActorHuman && slices.Contains(cfg.NotifyEvents, string(c.Type)) {
			// Internal child/worker chatter is not a group publication surface.
			if c.SourceTaskID != nil {
				task, e := repo.GetAgentTask(ctx, *c.SourceTaskID)
				if e != nil {
					return false, e
				}
				if !store.AgentTaskOwnsIssueLifecycle(issue, task) {
					cursor = c.CreatedAt
					cursorID = c.ID
					continue
				}
			}
			body := fmt.Sprintf("%s · %s\nIssue: %s\n%s", issue.Title, c.Type, issue.ID, c.Content)
			if err = s.enqueueChannelDelivery(ctx, tx, in, user, &issueID, &c.ID, "comment:"+id.String()+":"+c.ID.String(), body); err != nil {
				return false, err
			}
		}
		cursor = c.CreatedAt
		cursorID = c.ID
	}
	if last != string(issue.Status) && slices.Contains(cfg.NotifyEvents, "status") {
		if err = s.enqueueChannelDelivery(ctx, tx, in, user, &issueID, nil, fmt.Sprintf("status:%s:%d", id, issue.Version), channelIssueReceipt(issue, "工作状态更新")); err != nil {
			return false, err
		}
	}
	// A tool approval may leave the Issue in progress. Notify the accountable
	// human without exposing tool inputs, and without treating an IM reply as a decision.
	if slices.Contains(cfg.NotifyEvents, "status") {
		for offset := 0; ; offset += 100 {
			approvals, e := repo.ListApprovals(ctx, store.ApprovalFilter{Tenant: issue.Tenant, Namespace: issue.Namespace, ApproverRef: user, Status: model.ApprovalPending, Limit: 100, Offset: offset})
			if e != nil {
				return false, e
			}
			for _, approval := range approvals {
				matches := approval.IssueID != nil && *approval.IssueID == issueID || approval.TargetType == "issue" && approval.TargetRef == issueID.String()
				if !matches {
					continue
				}
				body := fmt.Sprintf("Issue: %s\n工作正在等待您的审批。请到控制台查看并处理审批 %s。发送普通消息只会补充工作，不会授权工具执行。", issueID, approval.ID)
				if e = s.enqueueChannelDelivery(ctx, tx, in, user, &issueID, nil, "approval:"+id.String()+":"+approval.ID.String(), body); e != nil {
					return false, e
				}
			}
			if len(approvals) < 100 {
				break
			}
		}
	}
	_, err = tx.Exec(ctx, `UPDATE channel_work_links SET cursor_time=$2,cursor_id=$3,last_status=$4,next_poll=now()+interval '5 seconds' WHERE id=$1`, id, cursor, cursorID, string(issue.Status))
	if err != nil {
		return false, err
	}
	return true, tx.Commit(ctx)
}
