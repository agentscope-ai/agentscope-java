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
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/channels"
	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/gin-gonic/gin"
	"github.com/jackc/pgx/v5"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"
)

func schedulerFixture(t *testing.T) *Server {
	t.Helper()
	s := resourceTestServer(t)
	s.cfg = DefaultConfig()
	resourceSQL(t, s, `INSERT INTO channels(channel_id,owner_id,type,disabled,properties_json,created_at,updated_at) VALUES('channel','owner','feishu',false,'{"appId":"app","appSecret":"secret","verificationToken":"verified"}',1,1)`)
	s.scheduler = newSchedulerRuntime(s)
	return s
}
func TestSchedulerConcurrentIntakeClaimAndStaleFence(t *testing.T) {
	s := schedulerFixture(t)
	second := newSchedulerRuntime(s)
	message := channels.Message{ChannelID: "channel", AccountID: "org", SenderID: "human", PeerKind: "DIRECT", PeerID: "room", MessageID: "stable", Text: "hello"}
	var workers sync.WaitGroup
	failures := make(chan error, 16)
	for i := range 16 {
		workers.Go(func() {
			r := s.scheduler
			if i%2 == 1 {
				r = second
			}
			failures <- r.accept(t.Context(), message)
		})
	}
	workers.Wait()
	close(failures)
	for err := range failures {
		if err != nil {
			t.Fatal(err)
		}
	}
	var count int
	if err := s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM scheduler_jobs`).Scan(&count); err != nil || count != 1 {
		t.Fatalf("duplicate intake %d %v", count, err)
	}
	first, err := s.scheduler.claimJob(t.Context())
	if err != nil {
		t.Fatal(err)
	}
	if _, err = second.claimJob(t.Context()); !errors.Is(err, pgx.ErrNoRows) {
		t.Fatalf("double claim %v", err)
	}
	resourceSQL(t, s, `UPDATE scheduler_jobs SET lease_until=now()-interval '1 second' WHERE id=$1`, first.ID)
	next, err := second.claimJob(t.Context())
	if err != nil || next.ID != first.ID || next.Token == first.Token {
		t.Fatalf("failover claim %+v %v", next, err)
	}
	tag, err := s.db.Pool.Exec(t.Context(), `UPDATE scheduler_jobs SET state='done' WHERE id=$1 AND lease_token=$2 AND lease_until>now()`, first.ID, first.Token)
	if err != nil || tag.RowsAffected() != 0 {
		t.Fatalf("stale worker committed %v", err)
	}
}
func TestSchedulerCallbackRoutesCoexistWithManagementAndRejectBeforeCommit(t *testing.T) {
	s := schedulerFixture(t)
	r := gin.New()
	s.registerChannels(r)
	s.RegisterChannelTransports(r)
	body := `{"header":{"token":"verified","tenant_key":"org"},"event":{"sender":{"sender_type":"user","sender_id":{"open_id":"human"}},"message":{"chat_id":"room","chat_type":"p2p","message_id":"stable","message_type":"text","content":"{\"text\":\"hello\"}"}}}`
	for range 2 {
		w := httptest.NewRecorder()
		r.ServeHTTP(w, httptest.NewRequest("POST", "/api/channels/feishu/channel/callback", strings.NewReader(body)))
		if w.Code != 200 {
			t.Fatalf("callback %d %s", w.Code, w.Body.String())
		}
	}
	var count int
	s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM scheduler_jobs`).Scan(&count)
	if count != 1 {
		t.Fatal(count)
	}
	resourceSQL(t, s, `UPDATE channels SET disabled=true WHERE channel_id='channel'`)
	w := httptest.NewRecorder()
	r.ServeHTTP(w, httptest.NewRequest("POST", "/api/channels/feishu/channel/callback", strings.NewReader(body)))
	if w.Code != 404 {
		t.Fatalf("disabled callback %d", w.Code)
	}
}
func TestSchedulerCronReplicaRetryUsesSameFireIdentity(t *testing.T) {
	s := schedulerFixture(t)
	anchor := time.Now().Add(-2 * time.Minute).Truncate(time.Minute).UnixMilli()
	resourceSQL(t, s, `INSERT INTO environments(environment_id,owner_id,name,type,created_at,updated_at) VALUES('env','owner','test','local',1,1)`)
	resourceSQL(t, s, `INSERT INTO deployments(deployment_id,owner_id,name,agent_id,environment_id,trigger_type,cron_expression,enabled,created_at,updated_at) VALUES('deployment','owner','test','agent','env','cron','0 * * * * *',true,$1,$1)`, anchor)
	second := newSchedulerRuntime(s)
	for _, r := range []*schedulerRuntime{s.scheduler, second} {
		if err := r.scanCron(t.Context()); err != nil {
			t.Fatal(err)
		}
	}
	var total int
	s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM scheduler_jobs WHERE kind='cron'`).Scan(&total)
	if total != 1 {
		t.Fatal(total)
	}
	requests := []ManagedTrigger{}
	s.SetManagedTriggerHandler(func(_ context.Context, r ManagedTrigger) (json.RawMessage, error) {
		requests = append(requests, r)
		if len(requests) == 1 {
			return nil, fmt.Errorf("ack lost")
		}
		return json.RawMessage(`{"sessionId":"same-session"}`), nil
	})
	if err := s.scheduler.advanceJob(t.Context()); err == nil {
		t.Fatal("lost ACK was not retried")
	}
	resourceSQL(t, s, `UPDATE scheduler_jobs SET next_attempt=now()`)
	if err := second.advanceJob(t.Context()); err != nil {
		t.Fatal(err)
	}
	if len(requests) != 2 || requests[0].RequestID != requests[1].RequestID || requests[0].ExternalKey != requests[1].ExternalKey {
		t.Fatal(requests)
	}
	var fire int64
	var status string
	s.db.Pool.QueryRow(t.Context(), `SELECT last_run_at,last_status FROM deployments WHERE deployment_id='deployment'`).Scan(&fire, &status)
	if fire < time.Now().Add(-time.Minute).UnixMilli() || fire > time.Now().UnixMilli() || status != "accepted" {
		t.Fatalf("fire %d %s", fire, status)
	}
}
func TestManagedChannelReplayScopesTurnAndRetainsCursor(t *testing.T) {
	v := channelJob{SessionID: "session", TurnID: "turn", Phase: "observe", Deadline: time.Now().Add(time.Minute).UnixMilli()}
	page := `{"turn":{"id":"turn","status":"completed"},"events":{"data":[{"session_id":"session","cursor":"c1","type":"item.completed","data":{"turn_id":"other","item":{"role":"assistant","content":[{"type":"text","text":"wrong turn"}]}}},{"session_id":"session","cursor":"c2","type":"item.finalized","data":{"turn_id":"turn","item":{"role":"assistant","content":[{"type":"text","text":"answer"}]}}}],"next_cursor":"c2","has_more":true}}`
	if err := v.observe([]byte(page)); err != nil || v.Reply != "answer" || v.Phase != "observe" || v.After != "c2" {
		t.Fatalf("page %+v %v", v, err)
	}
	if err := v.observe([]byte(`{"turn":{"id":"turn","status":"completed"},"events":{"data":[],"next_cursor":"c2","has_more":false}}`)); err != nil || v.Phase != "send" {
		t.Fatal(v, err)
	}
	if err := v.observe([]byte(strings.Replace(page, `"session_id":"session"`, `"session_id":"foreign"`, 1))); err == nil {
		t.Fatal("cross-session event accepted")
	}
}
func TestPortalWebhookSignatureFenceAndRedirect(t *testing.T) {
	var sent, receipts int
	var expectedSignature string
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		sent++
		raw, _ := io.ReadAll(r.Body)
		if string(raw) != "payload" || r.Header.Get("X-AgentScope-Delivery") != "delivery" || r.Header.Get("X-AgentScope-Signature") != webhookSign("secret", r.Header.Get("X-AgentScope-Timestamp"), "payload") {
			t.Error("invalid signed webhook")
		}
		expectedSignature = r.Header.Get("X-AgentScope-Signature")
		w.Header().Set("Location", "http://untrusted.invalid")
		w.WriteHeader(302)
	}))
	defer target.Close()
	fence := int64(2)
	portal := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("X-Builder-Internal-Token") != "internal" {
			t.Error("missing internal token")
		}
		switch {
		case strings.HasSuffix(r.URL.Path, "validate"):
			json.NewEncoder(w).Encode(map[string]any{"version": 1, "allowed": true, "deliveryId": "delivery", "claimFence": fence})
		case strings.HasSuffix(r.URL.Path, "receipts"):
			var receipt struct {
				HTTPStatus int          `json:"httpStatus"`
				Proof      webhookProof `json:"proof"`
			}
			json.NewDecoder(r.Body).Decode(&receipt)
			if receipt.HTTPStatus != 302 || receipt.Proof.ClaimFence != 2 {
				t.Error(receipt)
			}
			receipts++
			io.WriteString(w, `{}`)
		}
	}))
	defer portal.Close()
	w, err := newPortalWebhookWorker(portal.URL, "internal")
	if err != nil {
		t.Fatal(err)
	}
	defer w.close()
	w.loopback = true
	claim := webhookClaim{webhookProof: webhookProof{Version: 1, WebhookID: "webhook", DeliveryID: "delivery", ClaimToken: "fenced", ClaimFence: 2}, SessionID: "session", LeaseUntil: time.Now().Add(time.Minute).UnixMilli(), URL: target.URL, SigningSecret: "secret", Payload: "payload"}
	if err = w.deliver(t.Context(), claim); err != nil || sent != 1 || receipts != 1 || expectedSignature == "" {
		t.Fatalf("send %d receipt %d %v", sent, receipts, err)
	}
	fence = 3
	if err = w.deliver(t.Context(), claim); err != nil || sent != 1 {
		t.Fatalf("stale fence sent %d %v", sent, err)
	}
	claim.LeaseUntil = 1
	if err = w.deliver(t.Context(), claim); err == nil || sent != 1 {
		t.Fatal("expired lease sent")
	}
	w.allowed["example.com"] = true
	for _, u := range []string{"http://example.com", "https://example.com:444/hook", "https://user@example.com", "https://example.com/#fragment", "https://evil.com"} {
		if _, err = w.destination(u); err == nil {
			t.Errorf("allowed %s", u)
		}
	}
}
func TestSchedulerStreamLeaseAllowsOnlyOneReplica(t *testing.T) {
	s := schedulerFixture(t)
	resourceSQL(t, s, `UPDATE channels SET type='dingtalk',properties_json='{"appKey":"app","appSecret":"secret","robotCode":"robot","streamRegisterUrl":"http://127.0.0.1:1"}'`)
	ctx, cancel := context.WithCancel(t.Context())
	first, second := s.scheduler, newSchedulerRuntime(s)
	defer func() { cancel(); first.group.Wait(); second.group.Wait() }()
	if err := first.refreshStreams(ctx, ctx); err != nil {
		t.Fatal(err)
	}
	if err := second.refreshStreams(ctx, ctx); err != nil {
		t.Fatal(err)
	}
	if len(first.streams) != 1 || len(second.streams) != 0 {
		t.Fatalf("stream duplicated %d %d", len(first.streams), len(second.streams))
	}
	resourceSQL(t, s, `UPDATE scheduler_stream_leases SET lease_until=now()-interval '1 second'`)
	if err := second.refreshStreams(ctx, ctx); err != nil {
		t.Fatal(err)
	}
	if len(second.streams) != 1 {
		t.Fatal("stream failover missing")
	}
}

func TestManagedChannelJobSurvivesWorkerReplacement(t *testing.T) {
	s := schedulerFixture(t)
	var deliveries int
	var deliveryID string
	provider := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.Contains(r.URL.Path, "tenant_access_token") {
			io.WriteString(w, `{"code":0,"tenant_access_token":"token","expire":7200}`)
			return
		}
		var body struct {
			UUID    string `json:"uuid"`
			Content string `json:"content"`
		}
		json.NewDecoder(r.Body).Decode(&body)
		if !strings.Contains(body.Content, "final answer") || body.UUID == "" {
			t.Error(body)
		}
		deliveries++
		deliveryID = body.UUID
		io.WriteString(w, `{"code":0,"data":{"message_id":"provider-receipt"}}`)
	}))
	defer provider.Close()
	resourceSQL(t, s, `UPDATE channels SET default_agent_id='agent',properties_json=$1 WHERE channel_id='channel'`, mustJSON(map[string]string{"appId": "app", "appSecret": "secret", "verificationToken": "verified", "apiBase": provider.URL}))
	resourceSQL(t, s, `INSERT INTO users(user_id,username,password_hash,created_at) VALUES('owner','owner','unused',1)`)
	resourceSQL(t, s, `INSERT INTO channel_identities(channel_id,account_id,sender_id,user_id) VALUES('channel','org','human','owner')`)
	resourceSQL(t, s, `INSERT INTO agents(owner_id,agent_id,name,created_at,updated_at) VALUES('owner','agent','Agent',1,1)`)
	s.SetChannelWorkRuntime(&ChannelWorkRuntime{Namespace: func(context.Context, string) (*model.Namespace, error) {
		return &model.Namespace{Kind: "personal", Owner: "owner"}, nil
	}})
	var submits int
	s.SetManagedTriggerHandler(func(_ context.Context, request ManagedTrigger) (json.RawMessage, error) {
		if request.Operation == "submit" {
			submits++
			if request.RequestID != "provider-message" || request.OwnerID != "owner" {
				t.Error(request)
			}
			return json.RawMessage(`{"sessionId":"session","turn":{"id":"turn"}}`), nil
		}
		return json.RawMessage(`{"turn":{"id":"turn","status":"completed"},"events":{"data":[{"session_id":"session","cursor":"cursor","type":"item.finalized","data":{"turn_id":"turn","item":{"role":"assistant","content":[{"type":"text","text":"final answer"}]}}}],"next_cursor":"cursor","has_more":false}}`), nil
	})
	message := channels.Message{ChannelID: "channel", AccountID: "org", SenderID: "human", PeerKind: "DIRECT", PeerID: "room", MessageID: "provider-message", Text: "question"}
	if err := s.scheduler.accept(t.Context(), message); err != nil {
		t.Fatal(err)
	}
	if err := s.scheduler.advanceJob(t.Context()); err != nil {
		t.Fatal(err)
	}
	// A new process has no provider/token/reply/cursor state in memory.
	replacement := newSchedulerRuntime(s)
	for range 2 {
		resourceSQL(t, s, `UPDATE scheduler_jobs SET next_attempt=now()`)
		if err := replacement.advanceJob(t.Context()); err != nil {
			t.Fatal(err)
		}
	}
	var state string
	s.db.Pool.QueryRow(t.Context(), `SELECT state FROM scheduler_jobs`).Scan(&state)
	if state != "done" || submits != 1 || deliveries != 1 || deliveryID == "" {
		t.Fatalf("state=%s submits=%d deliveries=%d", state, submits, deliveries)
	}
	if err := replacement.accept(t.Context(), message); err != nil {
		t.Fatal(err)
	}
	if err := replacement.advanceJob(t.Context()); !errors.Is(err, pgx.ErrNoRows) {
		t.Fatalf("duplicate intake reran: %v", err)
	}
}

func TestSchedulerRateGuardAndCooldownAreShared(t *testing.T) {
	s := schedulerFixture(t)
	second := newSchedulerRuntime(s)
	msg := channels.Message{ChannelID: "channel", ProviderType: "feishu", AccountID: "org", SenderID: "sender", PeerKind: "DIRECT", PeerID: "peer", Text: "hello"}
	for i := range 21 {
		msg.MessageID = fmt.Sprint(i)
		runtime := s.scheduler
		if i%2 == 0 {
			runtime = second
		}
		if err := runtime.accept(t.Context(), msg); err != nil {
			t.Fatal(err)
		}
	}
	var count int
	s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM scheduler_jobs`).Scan(&count)
	if count != 20 {
		t.Fatalf("rate guard accepted %d messages", count)
	}
	resourceSQL(t, s, `UPDATE scheduler_channel_throttle SET events='{}'`)
	msg.MessageID = "during-cooldown"
	if err := second.accept(t.Context(), msg); err != nil {
		t.Fatal(err)
	}
	s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM scheduler_jobs`).Scan(&count)
	if count != 20 {
		t.Fatal("second replica ignored cooldown")
	}
	resourceSQL(t, s, `UPDATE scheduler_channel_throttle SET blocked_until=now()-interval '1 second'`)
	msg.MessageID = "after-cooldown"
	if err := second.accept(t.Context(), msg); err != nil {
		t.Fatal(err)
	}
	s.db.Pool.QueryRow(t.Context(), `SELECT count(*) FROM scheduler_jobs`).Scan(&count)
	if count != 21 {
		t.Fatal("peer did not recover after cooldown")
	}
}
