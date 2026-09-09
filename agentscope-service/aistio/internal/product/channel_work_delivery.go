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
	"encoding/json"
	"errors"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"slices"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
)

func (s *Server) claimChannelDelivery(c *gin.Context) {
	if s.channelWork == nil {
		c.Status(204)
		return
	}
	ctx := c.Request.Context()
	tx, err := s.db.Pool.Begin(ctx)
	if err != nil {
		writeErr(c, 503, "delivery queue unavailable")
		return
	}
	defer tx.Rollback(ctx)
	var id uuid.UUID
	var chID, user, text, event string
	var raw []byte
	var issueID *uuid.UUID
	var commentID *uuid.UUID
	var attempts int
	err = tx.QueryRow(ctx, `SELECT id,channel_id,address,user_id,issue_id,comment_id,text,attempts,event_key FROM channel_deliveries WHERE state IN ('pending','submitted') AND next_attempt<=now() ORDER BY next_attempt,id FOR UPDATE SKIP LOCKED LIMIT 1`).Scan(&id, &chID, &raw, &user, &issueID, &commentID, &text, &attempts, &event)
	if errors.Is(err, pgx.ErrNoRows) {
		c.Status(204)
		return
	}
	if err != nil {
		writeErr(c, 503, "delivery queue unavailable")
		return
	}
	var in ChannelInbound
	if json.Unmarshal(raw, &in) != nil {
		writeErr(c, 500, "invalid delivery address")
		return
	}
	transient := func(e error) bool {
		if e != nil && !errors.Is(e, store.ErrNotFound) && !errors.Is(e, pgx.ErrNoRows) {
			writeErr(c, 503, "delivery authority temporarily unavailable")
			return true
		}
		return false
	}
	ch, err := s.loadChannel(ctx, chID)
	if transient(err) {
		return
	}
	allowed := err == nil && !ch.Disabled
	if allowed {
		_, _, err = s.channelActor(ctx, ch, user, false)
		if transient(err) {
			return
		}
		allowed = err == nil
	}
	if allowed {
		err = s.identityStillBound(ctx, in, user)
		if transient(err) {
			return
		}
		allowed = err == nil
	}
	cfg, err := s.loadChannelWorkSettings(ctx, chID)
	if transient(err) {
		return
	}
	allowed = allowed && err == nil && cfg.Enabled
	if allowed && issueID != nil {
		n, e := s.channelWork.Namespace(ctx, ch.OwnerID)
		if transient(e) {
			return
		}
		allowed = e == nil && cfg.allowsWindow(n, user, in)
	}
	if allowed && issueID != nil {
		issue, issueErr := s.channelIssue(ctx, ch, user, *issueID, in.PeerKind == "GROUP", false)
		if transient(issueErr) {
			return
		}
		allowed = issueErr == nil
		if allowed && in.PeerKind == "GROUP" {
			published, e := s.channelGroupPublished(ctx, in, issue)
			if transient(e) {
				return
			}
			allowed = cfg.AllowGroupWork && published
		}
	}
	parts := strings.Split(event, ":")
	if allowed && len(parts) > 2 && (parts[0] == "comment" || parts[0] == "status" || parts[0] == "approval") {
		var active bool
		err = tx.QueryRow(ctx, `SELECT active FROM channel_work_links WHERE id=$1`, parts[1]).Scan(&active)
		if transient(err) {
			return
		}
		allowed = err == nil && active
		if parts[0] == "status" || parts[0] == "approval" {
			allowed = allowed && slices.Contains(cfg.NotifyEvents, "status")
		}
	}
	if allowed && len(parts) == 3 && parts[0] == "approval" {
		approvalID, parseErr := uuid.Parse(parts[2])
		if parseErr != nil {
			writeErr(c, 500, "invalid approval notification")
			return
		}
		approval, e := s.channelWork.Store.Collaboration().GetApproval(ctx, approvalID)
		if transient(e) {
			return
		}
		allowed = e == nil && approval.Status == model.ApprovalPending && approval.ApproverRef == user
	}
	if allowed && commentID != nil {
		comment, e := s.channelWork.Store.Collaboration().GetComment(ctx, *commentID)
		if transient(e) {
			return
		}
		allowed = e == nil && comment.DeletedAt == nil
		// Never replay stale copies of edited result content.
		if allowed && strings.HasPrefix(event, "comment:") {
			allowed = slices.Contains(cfg.NotifyEvents, string(comment.Type))
			text = "Issue: " + comment.IssueID.String() + "\n" + comment.Content
		}
	}
	if !allowed || attempts >= 10 {
		state := "cancelled"
		if allowed {
			state = "failed"
		}
		if _, err = tx.Exec(ctx, `UPDATE channel_deliveries SET state=$2,updated_at=now() WHERE id=$1`, id, state); err != nil {
			writeErr(c, 503, "delivery queue unavailable")
			return
		}
		if tx.Commit(ctx) != nil {
			writeErr(c, 503, "delivery queue unavailable")
			return
		}
		c.Status(204)
		return
	}
	lease := uuid.NewString()
	_, err = tx.Exec(ctx, `UPDATE channel_deliveries SET state='submitted',attempts=attempts+1,lease_token=$2,next_attempt=now()+interval '60 seconds',updated_at=now() WHERE id=$1`, id, lease)
	if err != nil || tx.Commit(ctx) != nil {
		writeErr(c, 503, "delivery queue unavailable")
		return
	}
	c.JSON(200, gin.H{"id": id, "leaseToken": lease, "channelId": chID, "accountId": in.AccountID, "peerKind": in.PeerKind, "peerId": in.PeerID, "threadId": in.ThreadID, "text": channelNotificationText(text)})
}
func (s *Server) channelDeliveryReceipt(c *gin.Context) {
	var req struct {
		LeaseToken        string `json:"leaseToken"`
		ProviderMessageID string `json:"providerMessageId"`
		Error             string `json:"error"`
	}
	id, e := uuid.Parse(c.Param("deliveryId"))
	if e != nil || c.ShouldBindJSON(&req) != nil || req.LeaseToken == "" || len(req.ProviderMessageID) > 512 || len(req.Error) > 512 || (req.ProviderMessageID == "") == (req.Error == "") {
		writeErr(c, 400, "receipt requires lease and either provider message ID or error")
		return
	}
	state := "provider_accepted"
	if req.Error != "" {
		state = "pending"
	}
	tag, e := s.db.Pool.Exec(c.Request.Context(), `UPDATE channel_deliveries SET state=CASE WHEN $3='pending' AND attempts>=10 THEN 'failed' ELSE $3 END,
 provider_message_id=$4,last_error=$5,next_attempt=now()+make_interval(secs=>LEAST(600,5*(1<<LEAST(attempts,7)))),updated_at=now()
 WHERE id=$1 AND state='submitted' AND lease_token=$2 AND next_attempt>now()`, id, req.LeaseToken, state, req.ProviderMessageID, req.Error)
	if e != nil {
		writeErr(c, 503, "cannot save receipt")
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, 409, "delivery lease expired or replaced")
		return
	}
	c.Status(204)
}

// Keep platform messages bounded; the durable Issue retains the complete result.
func channelNotificationText(text string) string {
	chars := []rune(text)
	if len(chars) <= 4000 {
		return text
	}
	return string(chars[:4000]) + "\n…内容较长，请在控制台查看完整工作。"
}
