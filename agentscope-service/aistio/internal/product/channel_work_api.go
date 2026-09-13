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
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"slices"
	"strings"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func (s *Server) registerChannelWork(r gin.IRouter) {
	r.GET("/api/channels/:channelId/collaboration", s.channelWorkConfig)
	r.PUT("/api/channels/:channelId/collaboration", s.putChannelWorkConfig)
	r.POST("/api/channels/:channelId/pairing", s.createChannelPairing)
	r.GET("/api/channels/:channelId/activity", s.channelWorkActivity)
	r.DELETE("/api/channels/:channelId/identity", s.deleteChannelIdentity)
	r.POST("/api/channels/:channelId/deliveries/:deliveryId/retry", s.retryChannelDelivery)
	r.POST("/api/channels/:channelId/messages/:messageId/retry", s.retryChannelIntake)
	r.DELETE("/api/channels/:channelId/links/:linkId", s.unsubscribeChannelWork)
	r.POST("/api/internal/channels/inbound", s.receiveChannelInbound)
	r.POST("/api/internal/channels/deliveries/claim", s.claimChannelDelivery)
	r.POST("/api/internal/channels/deliveries/:deliveryId/receipt", s.channelDeliveryReceipt)
}
func (s *Server) channelOwned(c *gin.Context) (channelRow, bool) {
	ch, err := s.loadChannel(c.Request.Context(), c.Param("channelId"))
	if err != nil || ch.OwnerID != currentResourceOwner(c) {
		writeErr(c, 404, "channel not found")
		return ch, false
	}
	if s.channelWork == nil {
		writeErr(c, 503, "channel collaboration unavailable")
		return ch, false
	}
	return ch, true
}
func (s *Server) loadChannelWorkSettings(ctx context.Context, id string) (ChannelWorkSettings, error) {
	v := ChannelWorkSettings{Routes: []ChannelWorkRoute{}, NotifyEvents: []string{"result", "status"}}
	var raw []byte
	err := s.db.Pool.QueryRow(ctx, `SELECT config,version FROM channel_work_settings WHERE channel_id=$1`, id).Scan(&raw, &v.Version)
	if errors.Is(err, pgx.ErrNoRows) {
		return v, nil
	}
	if err != nil {
		return v, err
	}
	version := v.Version
	err = json.Unmarshal(raw, &v)
	v.Version = version
	if v.Routes == nil {
		v.Routes = []ChannelWorkRoute{}
	}
	if v.NotifyEvents == nil {
		v.NotifyEvents = []string{}
	}
	return v, err
}
func (s *Server) channelWorkConfig(c *gin.Context) {
	ch, ok := s.channelOwned(c)
	if !ok {
		return
	}
	v, err := s.loadChannelWorkSettings(c.Request.Context(), ch.ChannelID)
	if err != nil {
		writeErr(c, 500, "cannot read channel settings")
		return
	}
	c.JSON(200, v)
}
func (s *Server) putChannelWorkConfig(c *gin.Context) {
	ch, ok := s.channelOwned(c)
	if !ok {
		return
	}
	var v ChannelWorkSettings
	if c.ShouldBindJSON(&v) != nil || len(v.Routes) > 100 || len(v.NotifyEvents) > 2 {
		writeErr(c, 400, "invalid channel settings")
		return
	}
	if v.Enabled && ch.Type != "feishu" {
		writeErr(c, 400, "durable work delivery currently requires Feishu")
		return
	}
	if v.Enabled {
		token, _ := propsAsMap(parseJSONRaw(deref(ch.PropertiesJSON)))["verificationToken"].(string)
		if strings.TrimSpace(token) == "" {
			writeErr(c, 400, "Feishu Verification Token is required for authenticated work intake")
			return
		}
	}
	n, err := s.channelWork.Namespace(c.Request.Context(), ch.OwnerID)
	if err != nil {
		writeErr(c, 403, "namespace unavailable")
		return
	}
	targets := []ChannelTarget{v.DefaultTarget}
	seen := map[string]bool{}
	for _, r := range v.Routes {
		key := mustJSON([]string{r.AccountID, r.PeerKind, r.PeerID, r.ThreadID})
		if seen[key] || r.AccountID == "" || r.PeerID == "" || r.PeerKind != "DIRECT" && r.PeerKind != "GROUP" {
			writeErr(c, 400, "routes require unique organization and conversation addresses")
			return
		}
		for _, id := range r.AllowedGroups {
			if _, ok := n.Groups[id]; !ok {
				writeErr(c, 400, "window rule references an unknown user group")
				return
			}
		}
		seen[key] = true
		targets = append(targets, r.ChannelTarget)
	}
	for _, t := range targets {
		if v.Enabled || t.TargetRef != "" {
			if e := s.channelWork.Target(c.Request.Context(), n, t.TargetType, t.TargetRef); e != nil {
				writeErr(c, 400, "target must be an active Agent or Team in this namespace")
				return
			}
		}
	}
	for _, e := range v.NotifyEvents {
		if !slices.Contains([]string{"result", "status"}, e) {
			writeErr(c, 400, "unsupported notification event")
			return
		}
	}
	raw := mustJSON(v)
	tag, err := s.db.Pool.Exec(c.Request.Context(), `INSERT INTO channel_work_settings(channel_id,config,version)
 SELECT $1,$2::jsonb,1 WHERE $3=0 ON CONFLICT(channel_id) DO UPDATE SET config=$2::jsonb,version=channel_work_settings.version+1 WHERE channel_work_settings.version=$3`, ch.ChannelID, raw, v.Version)
	if err != nil {
		writeErr(c, 500, "cannot save channel settings")
		return
	}
	// UPDATE for a nonzero version (the INSERT SELECT intentionally has no source row).
	if tag.RowsAffected() == 0 && v.Version > 0 {
		tag, err = s.db.Pool.Exec(c.Request.Context(), `UPDATE channel_work_settings SET config=$2::jsonb,version=version+1 WHERE channel_id=$1 AND version=$3`, ch.ChannelID, raw, v.Version)
	}
	if err != nil {
		writeErr(c, 500, "cannot save channel settings")
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, 409, "settings changed; reload before saving")
		return
	}
	v.Version++
	c.JSON(200, v)
}
func (s *Server) channelActor(ctx context.Context, ch channelRow, user string, write bool) (*model.Namespace, context.Context, error) {
	if s.channelWork == nil || ch.Disabled {
		return nil, ctx, store.ErrNotFound
	}
	var exists bool
	if e := s.db.Pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM users WHERE user_id=$1 AND disabled=false)`, user).Scan(&exists); e != nil {
		return nil, ctx, e
	}
	if !exists {
		return nil, ctx, store.ErrNotFound
	}
	n, e := s.channelWork.Namespace(ctx, ch.OwnerID)
	if e != nil {
		return nil, ctx, e
	}
	action := "read"
	if write {
		action = "work.write"
	}
	resourceAction := "discover"
	if write {
		resourceAction = "use"
	}
	if !n.Decide(user, "channel:"+ch.ChannelID, resourceAction).Allowed {
		return nil, ctx, store.ErrNotFound
	}
	if !model.NamespaceAllows(n.Roles(user), action) {
		return nil, ctx, store.ErrNotFound
	}
	return n, store.WithWorkAccess(ctx, store.WorkAccess{Refs: []string{user}, Restricted: true}), nil
}
func pairingHash(code string) string {
	h := sha256.Sum256([]byte(code))
	return hex.EncodeToString(h[:])
}
func (s *Server) createChannelPairing(c *gin.Context) {
	ch, ok := s.channelOwned(c)
	if !ok {
		return
	}
	if _, _, err := s.channelActor(c.Request.Context(), ch, currentUserID(c), true); err != nil {
		writeErr(c, 403, "namespace membership required")
		return
	}
	raw := make([]byte, 24)
	if _, e := rand.Read(raw); e != nil {
		writeErr(c, 500, "cannot generate pairing code")
		return
	}
	code := hex.EncodeToString(raw)
	_, err := s.db.Pool.Exec(c.Request.Context(), `INSERT INTO channel_pairing_codes(code_hash,channel_id,user_id,expires_at) VALUES($1,$2,$3,now()+interval '10 minutes')`, pairingHash(code), ch.ChannelID, currentUserID(c))
	if err != nil {
		writeErr(c, 500, "cannot create pairing code")
		return
	}
	c.Header("Cache-Control", "no-store")
	c.JSON(200, gin.H{"command": "/bind " + code, "expiresInSeconds": 600})
}
func (s *Server) deleteChannelIdentity(c *gin.Context) {
	ch, ok := s.channelOwned(c)
	if !ok {
		return
	}
	_, err := s.db.Pool.Exec(c.Request.Context(), `DELETE FROM channel_identities WHERE channel_id=$1 AND user_id=$2`, ch.ChannelID, currentUserID(c))
	if err != nil {
		writeErr(c, 500, "cannot unlink identity")
		return
	}
	c.Status(204)
}
func (s *Server) receiveChannelInbound(c *gin.Context) {
	c.Request.Body = http.MaxBytesReader(c.Writer, c.Request.Body, 48000)
	var in ChannelInbound
	if c.ShouldBindJSON(&in) != nil || in.validate() != nil {
		writeErr(c, 400, "invalid normalized channel message")
		return
	}
	ctx := c.Request.Context()
	ch, err := s.loadChannel(ctx, in.ChannelID)
	if err != nil || ch.Disabled || s.channelWork == nil {
		writeErr(c, 404, "channel unavailable")
		return
	}
	if strings.HasPrefix(strings.TrimSpace(in.Text), "/bind ") {
		if in.PeerKind != "DIRECT" {
			c.JSON(200, gin.H{"reply": "请在与机器人的私聊中绑定账号。"})
			return
		}
		code := strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(in.Text), "/bind "))
		tx, e := s.db.Pool.Begin(ctx)
		if e != nil {
			writeErr(c, 503, "pairing unavailable")
			return
		}
		defer tx.Rollback(ctx)
		var user string
		e = tx.QueryRow(ctx, `DELETE FROM channel_pairing_codes WHERE code_hash=$1 AND channel_id=$2 AND expires_at>now() RETURNING user_id`, pairingHash(code), ch.ChannelID).Scan(&user)
		if e != nil {
			c.JSON(200, gin.H{"reply": "绑定码无效或已过期，请在控制台重新生成。"})
			return
		}
		if _, _, e = s.channelActor(ctx, ch, user, true); e != nil {
			writeErr(c, 403, "namespace membership required")
			return
		}
		// A sender cannot be silently reassigned to another account. Unlink first.
		tag, e := tx.Exec(ctx, `INSERT INTO channel_identities(channel_id,account_id,sender_id,user_id) VALUES($1,$2,$3,$4)
 ON CONFLICT(channel_id,account_id,sender_id) DO UPDATE SET user_id=EXCLUDED.user_id WHERE channel_identities.user_id=EXCLUDED.user_id`, ch.ChannelID, in.AccountID, in.SenderID, user)
		if e != nil || tag.RowsAffected() == 0 {
			c.JSON(200, gin.H{"reply": "此外部身份已绑定其他账号，请先解除绑定。"})
			return
		}
		if tx.Commit(ctx) != nil {
			writeErr(c, 503, "pairing unavailable")
			return
		}
		c.JSON(200, gin.H{"reply": "账号绑定成功。"})
		return
	}
	var user string
	err = s.db.Pool.QueryRow(ctx, `SELECT user_id FROM channel_identities WHERE channel_id=$1 AND account_id=$2 AND sender_id=$3`, ch.ChannelID, in.AccountID, in.SenderID).Scan(&user)
	if err != nil {
		if !errors.Is(err, pgx.ErrNoRows) {
			writeErr(c, 503, "identity authority temporarily unavailable")
			return
		}
		c.JSON(200, gin.H{"reply": "请先在控制台 Channel 页面生成绑定码，并私聊机器人完成账号绑定。"})
		return
	}
	n, _, err := s.channelActor(ctx, ch, user, true)
	if err != nil {
		if !errors.Is(err, store.ErrNotFound) {
			writeErr(c, 503, "namespace authority temporarily unavailable")
			return
		}
		c.JSON(200, gin.H{"reply": "当前账号没有此 Channel 所属空间的工作权限。"})
		return
	}
	cfg, err := s.loadChannelWorkSettings(ctx, ch.ChannelID)
	if err != nil {
		writeErr(c, 503, "channel settings unavailable")
		return
	}
	if !cfg.Enabled {
		// Compatibility chat is limited to the personally owned, verified DM. Shared
		// sessions must never inherit the channel configurator's principal.
		if n.Kind != "personal" || user != ch.OwnerID || in.PeerKind != "DIRECT" {
			c.JSON(200, gin.H{"reply": "请先为此 Channel 启用工作接待并选择 Agent 或 Team。"})
			return
		}
		agentID := channelLegacyTarget(ch, in)
		if _, e := s.loadAgent(ctx, ch.OwnerID, agentID); e != nil {
			writeErr(c, 409, "chat target unavailable")
			return
		}
		c.JSON(200, gin.H{"chat": true, "ownerId": user, "agentId": agentID, "externalKey": channelLegacyExternalKey(ch, in, user)})
		return
	}
	if ch.Type != "feishu" {
		writeErr(c, 400, "unsupported durable channel provider")
		return
	}
	// Persist before acknowledging the callback; recovery never depends on its lifetime.
	payload := mustJSON(in)
	_, err = s.db.Pool.Exec(ctx, `INSERT INTO channel_inbound(id,channel_id,request,user_id) VALUES($1,$2,$3::jsonb,$4) ON CONFLICT(id) DO NOTHING`, in.key(), ch.ChannelID, payload, user)
	if err != nil {
		writeErr(c, 503, "cannot persist message")
		return
	}
	var same bool
	err = s.db.Pool.QueryRow(ctx, `SELECT request=$2::jsonb AND user_id=$3 FROM channel_inbound WHERE id=$1`, in.key(), payload, user).Scan(&same)
	if err != nil || !same {
		writeErr(c, 409, "message identity reused with different content")
		return
	}
	c.JSON(202, gin.H{"accepted": true, "id": in.key()})
}

func (s *Server) channelIssue(ctx context.Context, ch channelRow, user string, id uuid.UUID, group, write bool) (*model.Issue, error) {
	n, scoped, err := s.channelActor(ctx, ch, user, write)
	if err != nil {
		return nil, err
	}
	repo := s.channelWork.Store.Collaboration()
	issue, err := repo.GetIssue(scoped, id)
	if err != nil {
		return nil, err
	}
	if issue.Tenant != n.Tenant || issue.Namespace != n.Name || issue.ArchivedAt != nil {
		return nil, store.ErrNotFound
	}
	if err = store.CheckIssueWorkAccess(scoped, repo, id, write); err != nil {
		return nil, err
	}
	root := issue
	for root.ParentIssueID != nil {
		root, err = repo.GetIssue(scoped, *root.ParentIssueID)
		if err != nil {
			return nil, err
		}
	}
	// A group audience cannot be proven from one sender's account. Only explicitly
	// namespace-visible work can be linked or published to a group.
	if group && root.Access.Mode != "namespace" {
		return nil, store.ErrNotFound
	}
	return issue, nil
}

func (s *Server) channelWorkActivity(c *gin.Context) {
	ch, ok := s.channelOwned(c)
	if !ok {
		return
	}
	ctx := c.Request.Context()
	user := currentUserID(c)
	identities := []gin.H{}
	rows, e := s.db.Pool.Query(ctx, `SELECT account_id,sender_id FROM channel_identities WHERE channel_id=$1 AND user_id=$2`, ch.ChannelID, user)
	if e != nil {
		writeErr(c, 500, "cannot read identities")
		return
	}
	for rows.Next() {
		var a, b string
		if rows.Scan(&a, &b) == nil {
			identities = append(identities, gin.H{"accountId": a, "senderId": b})
		}
	}
	rows.Close()
	deliveries := []gin.H{}
	rows, e = s.db.Pool.Query(ctx, `SELECT id,issue_id,state,attempts,provider_message_id,last_error,created_at FROM channel_deliveries WHERE channel_id=$1 AND user_id=$2 ORDER BY created_at DESC LIMIT 100`, ch.ChannelID, user)
	if e != nil {
		writeErr(c, 500, "cannot read deliveries")
		return
	}
	for rows.Next() {
		var id uuid.UUID
		var issue *uuid.UUID
		var state, provider, errText string
		var attempts int
		var created any
		if rows.Scan(&id, &issue, &state, &attempts, &provider, &errText, &created) != nil {
			continue
		}
		if issue != nil {
			if _, e := s.channelIssue(ctx, ch, user, *issue, false, false); e != nil {
				continue
			}
		}
		deliveries = append(deliveries, gin.H{"id": id, "issueId": issue, "state": state, "attempts": attempts, "providerMessageId": provider, "lastError": errText, "createdAt": created})
	}
	rows.Close()
	links := []gin.H{}
	rows, e = s.db.Pool.Query(ctx, `SELECT id,issue_id,address,active FROM channel_work_links WHERE channel_id=$1 AND user_id=$2 ORDER BY next_poll DESC LIMIT 100`, ch.ChannelID, user)
	if e != nil {
		writeErr(c, 500, "cannot read work links")
		return
	}
	for rows.Next() {
		var id, issue uuid.UUID
		var address json.RawMessage
		var active bool
		if rows.Scan(&id, &issue, &address, &active) != nil {
			continue
		}
		if _, e := s.channelIssue(ctx, ch, user, issue, false, false); e != nil {
			continue
		}
		links = append(links, gin.H{"id": id, "issueId": issue, "address": address, "active": active})
	}
	rows.Close()
	inbounds := []gin.H{}
	rows, e = s.db.Pool.Query(ctx, `SELECT id,status,attempts FROM channel_inbound WHERE channel_id=$1 AND user_id=$2 ORDER BY created_at DESC LIMIT 100`, ch.ChannelID, user)
	if e != nil {
		writeErr(c, 500, "cannot read intake activity")
		return
	}
	for rows.Next() {
		var id uuid.UUID
		var state string
		var attempts int
		if rows.Scan(&id, &state, &attempts) == nil {
			inbounds = append(inbounds, gin.H{"id": id, "state": state, "attempts": attempts})
		}
	}
	rows.Close()
	c.JSON(200, gin.H{"identities": identities, "deliveries": deliveries, "links": links, "inbounds": inbounds})
}
func (s *Server) retryChannelDelivery(c *gin.Context) {
	ch, ok := s.channelOwned(c)
	if !ok {
		return
	}
	id, e := uuid.Parse(c.Param("deliveryId"))
	if e != nil {
		writeErr(c, 400, "invalid delivery")
		return
	}
	tag, e := s.db.Pool.Exec(c.Request.Context(), `UPDATE channel_deliveries SET state='pending',attempts=0,next_attempt=now(),updated_at=now() WHERE id=$1 AND channel_id=$2 AND user_id=$3 AND state='failed'`, id, ch.ChannelID, currentUserID(c))
	if e != nil {
		writeErr(c, 500, "cannot retry delivery")
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, 404, "failed delivery not found")
		return
	}
	c.Status(204)
}
func (s *Server) unsubscribeChannelWork(c *gin.Context) {
	ch, ok := s.channelOwned(c)
	if !ok {
		return
	}
	id, e := uuid.Parse(c.Param("linkId"))
	if e != nil {
		writeErr(c, 400, "invalid subscription")
		return
	}
	tag, e := s.db.Pool.Exec(c.Request.Context(), `UPDATE channel_work_links SET active=false WHERE id=$1 AND channel_id=$2 AND user_id=$3`, id, ch.ChannelID, currentUserID(c))
	if e != nil {
		writeErr(c, 500, "cannot stop subscription")
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, 404, "subscription not found")
		return
	}
	c.Status(204)
}

// The creator explicitly authorizes publication to a group. Another member's
// access to the Issue alone cannot open an unrelated external audience.
func (s *Server) channelGroupPublished(ctx context.Context, in ChannelInbound, issue *model.Issue) (bool, error) {
	root := issue
	for root.ParentIssueID != nil {
		var e error
		root, e = s.channelWork.Store.Collaboration().GetIssue(ctx, *root.ParentIssueID)
		if e != nil {
			return false, e
		}
	}
	var allowed bool
	err := s.db.Pool.QueryRow(ctx, `SELECT EXISTS(SELECT 1 FROM channel_work_links WHERE channel_id=$1 AND address->>'accountId'=$2 AND address->>'peerId'=$3 AND issue_id=$4 AND user_id=$5 AND active=true)`, in.ChannelID, in.AccountID, in.PeerID, root.ID, root.Creator.Ref).Scan(&allowed)
	return allowed, err
}

// Preserve the relevant legacy DM binding tiers, validating the chosen Agent
// against the personally owned partition before issuing a chat capability.
func channelLegacyTarget(ch channelRow, in ChannelInbound) string {
	for _, tier := range []string{"peer", "account", "channel"} {
		for _, value := range parseJSONArray(deref(ch.BindingsJSON)) {
			binding, ok := value.(map[string]any)
			if !ok {
				continue
			}
			match, _ := binding[tier].(string)
			expected := in.PeerKind + ":" + in.PeerID
			if tier == "account" {
				expected = in.AccountID
			}
			if tier == "channel" {
				expected = in.ChannelID
			}
			if match != "" && match == expected {
				ref, _ := binding["agentId"].(string)
				return ref
			}
		}
	}
	return deref(ch.DefaultAgentID)
}

func (s *Server) retryChannelIntake(c *gin.Context) {
	ch, ok := s.channelOwned(c)
	if !ok {
		return
	}
	id, err := uuid.Parse(c.Param("messageId"))
	if err != nil {
		writeErr(c, 400, "invalid intake ID")
		return
	}
	tag, err := s.db.Pool.Exec(c.Request.Context(), `UPDATE channel_inbound SET status='pending',attempts=0,next_attempt=now(),updated_at=now() WHERE id=$1 AND channel_id=$2 AND user_id=$3 AND status='failed'`, id, ch.ChannelID, currentUserID(c))
	if err != nil {
		writeErr(c, 500, "cannot retry intake")
		return
	}
	if tag.RowsAffected() == 0 {
		writeErr(c, 404, "failed intake not found")
		return
	}
	c.Status(204)
}

func channelLegacyExternalKey(ch channelRow, in ChannelInbound, user string) string {
	slot := in.addressKey()
	if strings.EqualFold(deref(ch.DmScope), "MAIN") {
		slot = "main"
	}
	// Even MAIN belongs to the verified personal owner, never arbitrary speakers.
	return "verified-channel:" + ch.ChannelID + ":" + user + ":" + slot
}
