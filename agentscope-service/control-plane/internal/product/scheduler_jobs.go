// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package product

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/compat"
	"net/http"
	"strings"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/channels"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

type cronJob struct {
	DeploymentID string `json:"deploymentId"`
	Expression   string `json:"expression"`
	Fire         string `json:"fire"`
}
type channelJob struct {
	Message     channels.Message `json:"message"`
	Phase       string           `json:"phase,omitempty"`
	OwnerID     string           `json:"ownerId,omitempty"`
	AgentID     string           `json:"agentId,omitempty"`
	ExternalKey string           `json:"externalKey,omitempty"`
	SessionID   string           `json:"sessionId,omitempty"`
	TurnID      string           `json:"turnId,omitempty"`
	After       string           `json:"after,omitempty"`
	Reply       string           `json:"reply,omitempty"`
	More        bool             `json:"more,omitempty"`
	Deadline    int64            `json:"deadline,omitempty"`
}
type schedulerJob struct {
	ID, Kind, Token string
	Payload         json.RawMessage
	Attempts        int
}

func (s *schedulerRuntime) advanceJob(ctx context.Context) error {
	job, err := s.claimJob(ctx)
	if err != nil {
		return err
	}
	payload, done, err := s.stepJob(ctx, job)
	if err != nil {
		_, releaseErr := s.server.db.Pool.Exec(ctx, `UPDATE scheduler_jobs SET attempts=attempts+1,state='pending',next_attempt=now()+make_interval(secs=>LEAST(300,power(2,LEAST(attempts,8)))::int),lease_token='',lease_until='-infinity',updated_at=now() WHERE id=$1 AND lease_token=$2 AND lease_until>now()`, job.ID, job.Token)
		if releaseErr != nil {
			return releaseErr
		}
		return err
	}
	nextAttempt := time.Now().Add(s.pollInterval)
	if job.Kind == "channel" {
		var message channelJob
		if json.Unmarshal(payload, &message) == nil && message.Phase == "observe" && message.More {
			nextAttempt = time.Now()
		}
	}
	state := "pending"
	if done {
		state = "done"
	}
	tag, err := s.server.db.Pool.Exec(ctx, `UPDATE scheduler_jobs SET payload=$3::jsonb,state=$4,attempts=0,next_attempt=$5,lease_token='',lease_until='-infinity',updated_at=now() WHERE id=$1 AND lease_token=$2 AND lease_until>now()`, job.ID, job.Token, payload, state, nextAttempt)
	if err != nil {
		return err
	}
	if tag.RowsAffected() != 1 {
		return store.ErrConflict
	}
	return nil
}
func (s *schedulerRuntime) claimJob(ctx context.Context) (schedulerJob, error) {
	var job schedulerJob
	job.Token = uuid.NewString()
	err := s.server.db.Pool.QueryRow(ctx, `WITH candidate AS (SELECT id FROM scheduler_jobs WHERE state='pending' AND next_attempt<=now() AND lease_until<=now() ORDER BY next_attempt,id FOR UPDATE SKIP LOCKED LIMIT 1)
 UPDATE scheduler_jobs j SET lease_token=$1,lease_until=now()+interval '45 seconds' FROM candidate c WHERE j.id=c.id RETURNING j.id,j.kind,j.payload,j.attempts`, job.Token).Scan(&job.ID, &job.Kind, &job.Payload, &job.Attempts)
	return job, err
}
func (s *schedulerRuntime) stepJob(ctx context.Context, job schedulerJob) (json.RawMessage, bool, error) {
	if job.Kind == "cron" {
		var v cronJob
		if json.Unmarshal(job.Payload, &v) != nil {
			return nil, false, fmt.Errorf("invalid cron job")
		}
		d, err := s.server.loadDeploy(ctx, v.DeploymentID)
		if errors.Is(err, pgx.ErrNoRows) {
			return job.Payload, true, nil
		}
		if err != nil {
			return nil, false, err
		}
		if !d.Enabled || d.ArchivedAt != nil || d.TriggerType != "cron" || d.CronExpression == nil || *d.CronExpression != v.Expression {
			return job.Payload, true, nil
		}
		_, err = time.Parse(time.RFC3339Nano, v.Fire)
		if err != nil {
			return nil, false, err
		}
		_, err = s.server.fireDeployment(ctx, d, "", job.ID, "")
		return job.Payload, err == nil, err
	}
	var v channelJob
	if job.Kind != "channel" || json.Unmarshal(job.Payload, &v) != nil {
		return nil, false, fmt.Errorf("invalid channel job")
	}
	p, err := s.provider(ctx, v.Message.ChannelID)
	if errors.Is(err, pgx.ErrNoRows) || errors.Is(err, store.ErrForbidden) {
		return job.Payload, true, nil
	}
	if err != nil {
		return nil, false, err
	}
	if v.Message.ProviderType != "" && v.Message.ProviderType != p.Config.Type {
		return job.Payload, true, nil
	}
	switch v.Phase {
	case "":
		var result struct {
			Chat        bool   `json:"chat"`
			Reply       string `json:"reply"`
			OwnerID     string `json:"ownerId"`
			AgentID     string `json:"agentId"`
			ExternalKey string `json:"externalKey"`
		}
		if err = s.call(ctx, "/inbound", v.Message, &result); err != nil {
			return nil, false, err
		}
		if !result.Chat {
			v.Reply = result.Reply
			if v.Reply == "" {
				return job.Payload, true, nil
			}
			v.Phase = "send"
			break
		}
		v.OwnerID, v.AgentID, v.ExternalKey = result.OwnerID, result.AgentID, result.ExternalKey
		requestID := v.Message.MessageID
		if len(requestID) > 256 {
			requestID = sha256Hex(requestID)
		}
		raw, e := s.server.SubmitManagedTrigger(ctx, ManagedTrigger{OwnerID: v.OwnerID, AgentID: v.AgentID, ExternalKey: v.ExternalKey, RequestID: requestID, Text: v.Message.Text})
		if e != nil {
			return nil, false, e
		}
		var accepted struct {
			SessionID string `json:"sessionId"`
			Turn      struct {
				ID string `json:"id"`
			} `json:"turn"`
		}
		if json.Unmarshal(raw, &accepted) != nil || accepted.SessionID == "" || accepted.Turn.ID == "" {
			return nil, false, fmt.Errorf("invalid Managed Turn receipt")
		}
		v.SessionID, v.TurnID, v.Phase, v.Deadline = accepted.SessionID, accepted.Turn.ID, "observe", time.Now().Add(s.replyTimeout).UnixMilli()
	case "observe":
		raw, e := s.server.SubmitManagedTrigger(ctx, ManagedTrigger{Operation: "events", OwnerID: v.OwnerID, AgentID: v.AgentID, SessionID: v.SessionID, TurnID: v.TurnID, After: v.After})
		if e != nil {
			return nil, false, e
		}
		if e = v.observe(raw); e != nil {
			return nil, false, e
		}
	case "send":
		// Config disable and deletion take effect before each physical send. For
		// chat results, also recheck the original verified identity and live grants.
		if v.OwnerID != "" {
			ch, e := s.server.loadChannel(ctx, v.Message.ChannelID)
			if e != nil {
				return nil, false, e
			}
			in := ChannelInbound{ChannelID: v.Message.ChannelID, AccountID: v.Message.AccountID, SenderID: v.Message.SenderID}
			if e = s.server.identityStillBound(ctx, in, v.OwnerID); e != nil {
				return nil, false, e
			}
			if _, _, e = s.server.channelActor(ctx, ch, v.OwnerID, false); e != nil {
				return nil, false, e
			}
		}
		_, err = p.Send(ctx, channels.Address{AccountID: v.Message.AccountID, PeerKind: v.Message.PeerKind, PeerID: v.Message.PeerID, ThreadID: v.Message.ThreadID}, v.Reply, job.ID)
		return job.Payload, err == nil, err
	default:
		return nil, false, fmt.Errorf("invalid channel phase")
	}
	raw, err := json.Marshal(v)
	return raw, false, err
}
func (v *channelJob) observe(raw []byte) error {
	var result struct {
		Turn   struct{ ID, Status string } `json:"turn"`
		Events struct {
			Data []struct {
				SessionID string `json:"session_id"`
				Cursor    string `json:"cursor"`
				Type      string `json:"type"`
				Data      struct {
					TurnID string `json:"turn_id"`
					Item   struct {
						Role    string                        `json:"role"`
						Content []struct{ Type, Text string } `json:"content"`
					} `json:"item"`
				} `json:"data"`
			} `json:"data"`
			Next string `json:"next_cursor"`
			More bool   `json:"has_more"`
		} `json:"events"`
	}
	if json.Unmarshal(raw, &result) != nil || result.Turn.ID != v.TurnID {
		return fmt.Errorf("invalid Managed Turn projection")
	}
	for _, event := range result.Events.Data {
		if event.SessionID != v.SessionID || event.Cursor == "" {
			return fmt.Errorf("invalid Managed event coordinate")
		}
		if event.Data.TurnID != v.TurnID || (event.Type != "item.completed" && event.Type != "item.finalized") || !strings.EqualFold(event.Data.Item.Role, "assistant") {
			continue
		}
		text := ""
		for _, block := range event.Data.Item.Content {
			if block.Type == "text" {
				text += block.Text
			}
		}
		if text != "" {
			v.Reply = text
		}
	}
	if result.Events.Next == "" || result.Events.More && result.Events.Next == v.After {
		return fmt.Errorf("invalid Managed event cursor")
	}
	v.After = result.Events.Next
	v.More = result.Events.More
	if !result.Events.More {
		switch result.Turn.Status {
		case "completed", "failed", "cancelled":
			v.Phase = "send"
			if v.Reply == "" {
				v.Reply = "(the agent turn " + result.Turn.Status + " without a text reply)"
			}
		case "waiting":
			v.Phase = "send"
			if v.Reply == "" {
				v.Reply = "(the agent is waiting for input — open the Session in the console)"
			}
		default:
			if time.Now().UnixMilli() >= v.Deadline {
				v.Phase = "send"
				if v.Reply == "" {
					v.Reply = "(still processing — the agent will keep running; check the Session in the console)"
				}
			}
		}
	}
	if v.Phase == "observe" && time.Now().UnixMilli() >= v.Deadline {
		v.Phase = "send"
		if v.Reply == "" {
			v.Reply = "(still processing — the agent will keep running; check the Session in the console)"
		}
	}
	return nil
}
func (s *schedulerRuntime) deliverWork(ctx context.Context) error {
	var d struct {
		ID        string `json:"id"`
		Token     string `json:"leaseToken"`
		ChannelID string `json:"channelId"`
		AccountID string `json:"accountId"`
		PeerKind  string `json:"peerKind"`
		PeerID    string `json:"peerId"`
		ThreadID  string `json:"threadId"`
		Text      string `json:"text"`
	}
	if err := s.call(ctx, "/deliveries/claim", nil, &d); err != nil {
		return err
	}
	p, err := s.provider(ctx, d.ChannelID)
	var receipt string
	if err == nil {
		receipt, err = p.Send(ctx, channels.Address{AccountID: d.AccountID, PeerKind: d.PeerKind, PeerID: d.PeerID, ThreadID: d.ThreadID}, d.Text, d.ID)
		if receipt == "" && err == nil {
			err = fmt.Errorf("provider receipt missing")
		}
	}
	body := map[string]string{"leaseToken": d.Token, "providerMessageId": receipt}
	if err != nil {
		body = map[string]string{"leaseToken": d.Token, "error": "provider_unavailable"}
	}
	receiptErr := s.call(ctx, "/deliveries/"+d.ID+"/receipt", body, nil)
	if errors.Is(receiptErr, pgx.ErrNoRows) {
		receiptErr = nil
	}
	if receiptErr != nil {
		return receiptErr
	}
	return err
}
func (s *schedulerRuntime) outbound(c *gin.Context) {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 48000)
	if s.server.cfg.InternalToken == "" || compat.InternalToken(c.Request.Header) != s.server.cfg.InternalToken {
		c.Status(403)
		return
	}
	var req struct {
		ChannelID string `json:"channelId"`
		AccountID string `json:"accountId"`
		PeerKind  string `json:"peerKind"`
		PeerID    string `json:"peerId"`
		ThreadID  string `json:"threadId"`
		AgentID   string `json:"agentId"`
		Text      string `json:"text"`
		Markdown  string `json:"markdown"`
	}
	if c.ShouldBindJSON(&req) != nil || req.ChannelID == "" || req.PeerID == "" {
		writeErr(c, 400, "channelId and peerId required")
		return
	}
	if req.PeerKind == "" {
		req.PeerKind = "DIRECT"
	}
	req.PeerKind = strings.ToUpper(req.PeerKind)
	if req.AgentID != "" {
		ch, err := s.server.loadChannel(c.Request.Context(), req.ChannelID)
		if err != nil {
			c.Status(404)
			return
		}
		// Explicit bindings take precedence over a caller-supplied Agent hint.
		in := ChannelInbound{ChannelID: req.ChannelID, AccountID: req.AccountID, PeerKind: req.PeerKind, PeerID: req.PeerID, ThreadID: req.ThreadID}
		target := channelLegacyTarget(ch, in)
		if target != "" && target != req.AgentID {
			writeErr(c, 404, "Agent routing mismatch")
			return
		}
		owner := c.GetHeader("X-Managed-Agents-Internal-User")
		if owner == "" {
			owner = ch.OwnerID
		}
		if _, err = s.server.loadAgent(c.Request.Context(), owner, req.AgentID); err != nil {
			c.Status(403)
			return
		}
	}
	text := req.Text
	if strings.TrimSpace(req.Markdown) != "" {
		text = req.Markdown
	}
	if strings.TrimSpace(text) == "" {
		writeErr(c, 400, "text or markdown required")
		return
	}
	provider, err := s.provider(c.Request.Context(), req.ChannelID)
	if err != nil {
		writeErr(c, 404, "channel unavailable")
		return
	}
	if _, err = provider.Send(c.Request.Context(), channels.Address{AccountID: req.AccountID, PeerKind: req.PeerKind, PeerID: req.PeerID, ThreadID: req.ThreadID}, text, uuid.NewString()); err != nil {
		writeErr(c, 502, "channel delivery failed")
		return
	}
	c.JSON(http.StatusAccepted, gin.H{"status": "submitted"})
}
