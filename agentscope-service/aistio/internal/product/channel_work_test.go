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
	"bytes"
	"context"
	"encoding/json"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/postgres"
)

type channelFixture struct {
	s           *Server
	router      *gin.Engine
	n           *model.Namespace
	ch          channelRow
	user, other string
	in          ChannelInbound
}

func channelSetup(t *testing.T) *channelFixture {
	t.Helper()
	dsn := os.Getenv("AISTIO_TEST_POSTGRES_DSN")
	if dsn == "" {
		t.Skip("AISTIO_TEST_POSTGRES_DSN not set")
	}
	ctx := t.Context()
	db, e := openDB(ctx, dsn)
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(db.Close)
	if e = migrate(ctx, db); e != nil {
		t.Fatal(e)
	}
	st, e := store.Open(ctx, store.Config{Driver: store.DriverPostgres, PostgresDSN: dsn})
	if e != nil {
		t.Fatal(e)
	}
	t.Cleanup(func() { st.Close() })
	user := shortID("cw-")
	other := shortID("cw-")
	for _, u := range []string{user, other} {
		if _, e = db.Pool.Exec(ctx, `INSERT INTO users(user_id,username,password_hash,created_at) VALUES($1,$1,'unused',0)`, u); e != nil {
			t.Fatal(e)
		}
	}
	n, e := st.Access().PutNamespace(ctx, &model.Namespace{Tenant: "channel-tests", Name: shortID("ns-"), Kind: "shared", Owner: user, DisplayName: "Channel tests", Members: map[string][]string{other: {"member"}}}, 0, user)
	if e != nil {
		t.Fatal(e)
	}
	ch := channelRow{ChannelID: shortID("cw-"), OwnerID: "namespace:" + n.Tenant + ":" + n.Name, Type: "feishu"}
	if _, e = db.Pool.Exec(ctx, `INSERT INTO channels(channel_id,owner_id,type,disabled,properties_json,created_at,updated_at) VALUES($1,$2,'feishu',false,'{"verificationToken":"test-token"}',0,0)`, ch.ChannelID, ch.OwnerID); e != nil {
		t.Fatal(e)
	}
	s := &Server{db: db, cfg: DefaultConfig()}
	s.SetChannelWorkRuntime(&ChannelWorkRuntime{Store: st, Namespace: func(ctx context.Context, owner string) (*model.Namespace, error) {
		if owner != ch.OwnerID {
			return nil, store.ErrNotFound
		}
		return st.Access().GetNamespace(ctx, n.Tenant, n.Name)
	}, Target: func(ctx context.Context, scope *model.Namespace, kind, ref string) error {
		if kind == "agent" && ref == "worker" {
			return nil
		}
		if kind == "team" {
			id, e := uuid.Parse(ref)
			if e != nil {
				return e
			}
			team, e := st.Collaboration().GetTeam(ctx, id)
			if e != nil {
				return e
			}
			if team.Tenant == scope.Tenant && team.Namespace == scope.Name {
				return nil
			}
		}
		return store.ErrNotFound
	}})
	cfg := ChannelWorkSettings{Enabled: true, DefaultTarget: ChannelTarget{TargetType: "agent", TargetRef: "worker"}, AllowGroupWork: true, NotifyEvents: []string{"result", "status"}, Routes: []ChannelWorkRoute{}}
	if _, e = db.Pool.Exec(ctx, `INSERT INTO channel_work_settings(channel_id,config) VALUES($1,$2::jsonb)`, ch.ChannelID, mustJSON(cfg)); e != nil {
		t.Fatal(e)
	}
	gin.SetMode(gin.TestMode)
	r := gin.New()
	r.Use(s.Middlewares()...)
	r.Use(func(c *gin.Context) { SetResourceOwner(c, ch.OwnerID) })
	s.registerChannels(r)
	f := &channelFixture{s: s, router: r, n: n, ch: ch, user: user, other: other, in: ChannelInbound{ChannelID: ch.ChannelID, AccountID: "org", SenderID: "sender-a", PeerKind: "DIRECT", PeerID: "chat-a", MessageID: uuid.NewString(), Text: "/new investigate release failure"}}
	for _, binding := range [][2]string{{"sender-a", user}, {"sender-b", other}} {
		if _, e = db.Pool.Exec(ctx, `INSERT INTO channel_identities(channel_id,account_id,sender_id,user_id) VALUES($1,'org',$2,$3)`, ch.ChannelID, binding[0], binding[1]); e != nil {
			t.Fatal(e)
		}
	}
	t.Cleanup(func() {
		db.Pool.Exec(context.Background(), `DELETE FROM channels WHERE channel_id=$1`, ch.ChannelID)
		db.Pool.Exec(context.Background(), `DELETE FROM users WHERE user_id=ANY($1)`, []string{user, other})
	})
	return f
}
func (f *channelFixture) request(t *testing.T, method, path string, body any, user string) *httptest.ResponseRecorder {
	t.Helper()
	data, _ := json.Marshal(body)
	r := httptest.NewRequest(method, path, bytes.NewReader(data))
	r.Header.Set("Content-Type", "application/json")
	if strings.HasPrefix(path, "/api/internal/") {
		r.Header.Set("X-Builder-Internal-Token", f.s.cfg.InternalToken)
	} else {
		token, e := issueToken(f.s.cfg.JWTSecret, user, user, []string{"user"})
		if e != nil {
			t.Fatal(e)
		}
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	f.router.ServeHTTP(w, r)
	return w
}
func (f *channelFixture) send(t *testing.T, in ChannelInbound) channelOutcome {
	t.Helper()
	w := f.request(t, "POST", "/api/internal/channels/inbound", in, "")
	if w.Code != 202 {
		t.Fatalf("intake %d %s", w.Code, w.Body.String())
	}
	if worked, e := f.s.processChannelInbound(t.Context()); e != nil || !worked {
		t.Fatalf("process %v %v", worked, e)
	}
	var out channelOutcome
	var status string
	e := f.s.db.Pool.QueryRow(t.Context(), `SELECT status,issue_id,comment_id,result FROM channel_inbound WHERE id=$1`, in.key()).Scan(&status, &out.IssueID, &out.CommentID, &out.Reply)
	if e != nil || status != "done" {
		t.Fatalf("result %s %v %s", status, e, out.Reply)
	}
	return out
}
func TestChannelRoutePrefersExactThread(t *testing.T) {
	cfg := ChannelWorkSettings{DefaultTarget: ChannelTarget{"agent", "default"}, Routes: []ChannelWorkRoute{{ChannelTarget: ChannelTarget{"agent", "room"}, AccountID: "org", PeerKind: "GROUP", PeerID: "room"}, {ChannelTarget: ChannelTarget{"team", "team"}, AccountID: "org", PeerKind: "GROUP", PeerID: "room", ThreadID: "thread"}}}
	in := ChannelInbound{AccountID: "org", PeerKind: "GROUP", PeerID: "room", ThreadID: "thread"}
	if cfg.route(in).TargetRef != "team" {
		t.Fatal("thread route lost")
	}
	in.AccountID = "other"
	if cfg.route(in).TargetRef != "default" {
		t.Fatal("organization boundary lost")
	}
}
func TestChannelDurableIntakeAndReplyAuthorization(t *testing.T) {
	f := channelSetup(t)
	ctx := t.Context()
	first := f.send(t, f.in)
	if first.IssueID == nil {
		t.Fatal(first.Reply)
	}
	issue, e := f.s.channelWork.Store.Collaboration().GetIssue(ctx, *first.IssueID)
	if e != nil || issue.Creator.Ref != f.user || issue.Access.Mode != "private" {
		t.Fatalf("wrong human/ACL %+v %v", issue, e)
	}
	w := f.request(t, "POST", "/api/internal/channels/inbound", f.in, "")
	if w.Code != 202 {
		t.Fatal(w.Body.String())
	}
	conflict := f.in
	conflict.Text = "changed"
	w = f.request(t, "POST", "/api/internal/channels/inbound", conflict, "")
	if w.Code != 409 {
		t.Fatal("duplicate identity accepted different content")
	}
	// Simulate a crash after collaboration commit and before the intake acknowledgement.
	f.s.db.Pool.Exec(ctx, `UPDATE channel_inbound SET status='pending' WHERE id=$1`, f.in.key())
	f.s = &Server{db: f.s.db, cfg: f.s.cfg, channelWork: f.s.channelWork}
	if _, e = f.s.processChannelInbound(ctx); e != nil {
		t.Fatal(e)
	}
	tasks, e := f.s.channelWork.Store.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID})
	if e != nil || len(tasks) != 1 {
		t.Fatalf("duplicate task after recovery: %d %v", len(tasks), e)
	}
	follow := f.in
	follow.MessageID = uuid.NewString()
	follow.ReplyToID = f.in.MessageID
	follow.Text = "Add rollback strategy"
	result := f.send(t, follow)
	if result.CommentID == nil || result.IssueID == nil || *result.IssueID != issue.ID {
		t.Fatal(result)
	}
	f.s.db.Pool.Exec(ctx, `UPDATE channel_inbound SET status='pending' WHERE id=$1`, follow.key())
	if _, e = f.s.processChannelInbound(ctx); e != nil {
		t.Fatal(e)
	}
	comments, e := f.s.channelWork.Store.Collaboration().ListComments(ctx, issue.ID, store.CommentListOptions{Limit: 100})
	if e != nil || len(comments) != 1 {
		t.Fatalf("duplicate comments %d %v", len(comments), e)
	}
	other := follow
	other.MessageID = uuid.NewString()
	other.SenderID = "sender-b"
	if got := f.send(t, other); got.IssueID != nil {
		t.Fatal("private Issue leaked to another sender")
	}
	other.MessageID = uuid.NewString()
	other.ReplyToID = ""
	other.Text = "/issue " + issue.ID.String() + " read this"
	if got := f.send(t, other); got.IssueID != nil {
		t.Fatal("explicit ID bypassed ACL")
	}
	next := f.in
	next.MessageID = uuid.NewString()
	next.Text = "/new another task"
	if got := f.send(t, next); got.IssueID == nil {
		t.Fatal(got)
	}
	next.MessageID = uuid.NewString()
	next.Text = "continue"
	if got := f.send(t, next); got.IssueID != nil || !strings.Contains(got.Reply, "多个") {
		t.Fatal("ambiguous conversation was silently routed", got)
	}
}
func TestChannelGroupPublicationRequiresCreatorConsent(t *testing.T) {
	f := channelSetup(t)
	in := f.in
	in.PeerKind = "GROUP"
	in.PeerID = "group-1"
	if out := f.send(t, in); out.IssueID != nil {
		t.Fatal("group silently created private work")
	}
	in.MessageID = uuid.NewString()
	in.Text = "/new-shared investigate"
	out := f.send(t, in)
	if out.IssueID == nil {
		t.Fatal(out)
	}
	other := in
	other.MessageID = uuid.NewString()
	other.SenderID = "sender-b"
	other.PeerID = "unrelated-group"
	other.Text = "/follow " + out.IssueID.String()
	if got := f.send(t, other); got.IssueID != nil {
		t.Fatal("member published work to unrelated external audience")
	}
	other.MessageID = uuid.NewString()
	other.PeerID = in.PeerID
	other.Text = "/issue " + out.IssueID.String() + " add logs"
	if got := f.send(t, other); got.CommentID == nil {
		t.Fatal("authorized group followup failed", got)
	}
}
func TestChannelAsyncDeliveryRecoveryAndRevocation(t *testing.T) {
	f := channelSetup(t)
	ctx := t.Context()
	out := f.send(t, f.in)
	// The initial callback is long gone. A persisted result is discovered later.
	f.s.db.Pool.Exec(ctx, `UPDATE channel_inbound SET created_at=now()-interval '10 minutes' WHERE channel_id=$1`, f.ch.ChannelID)
	svc := collaboration.Service{Store: f.s.channelWork.Store}
	comment, e := svc.AddComment(ctx, collaboration.AddCommentRequest{IssueID: *out.IssueID, Author: model.Actor{Type: model.ActorAgent, Ref: "worker"}, Content: "Release failure diagnosed", Type: model.CommentResult, SuppressImplicitRouting: true})
	if e != nil {
		t.Fatal(e)
	}
	if worked, e := f.s.pollChannelLink(ctx); e != nil || !worked {
		t.Fatalf("poll %v %v", worked, e)
	}
	var count int
	e = f.s.db.Pool.QueryRow(ctx, `SELECT count(*) FROM channel_deliveries WHERE comment_id=$1`, comment.Comment.ID).Scan(&count)
	if e != nil || count != 1 {
		t.Fatalf("long task missing result: %d %v", count, e)
	}
	// Only this delivery is eligible, so claims below are deterministic.
	f.s.db.Pool.Exec(ctx, `UPDATE channel_deliveries SET next_attempt=now()+interval '1 hour' WHERE channel_id=$1 AND comment_id IS DISTINCT FROM $2`, f.ch.ChannelID, comment.Comment.ID)
	claim := f.request(t, "POST", "/api/internal/channels/deliveries/claim", map[string]any{}, "")
	if claim.Code != 200 {
		t.Fatalf("claim %d %s", claim.Code, claim.Body.String())
	}
	var d map[string]any
	json.Unmarshal(claim.Body.Bytes(), &d)
	receiptPath := "/api/internal/channels/deliveries/" + d["id"].(string) + "/receipt"
	w := f.request(t, "POST", receiptPath, map[string]any{"leaseToken": "stale", "providerMessageId": "om-stale"}, "")
	if w.Code != 409 {
		t.Fatal("stale lease accepted")
	}
	w = f.request(t, "POST", receiptPath, map[string]any{"leaseToken": d["leaseToken"], "error": "ProviderRejected"}, "")
	if w.Code != 204 {
		t.Fatal(w.Body.String())
	}
	f.s.db.Pool.Exec(ctx, `UPDATE channel_deliveries SET next_attempt=now()-interval '1 second' WHERE id=$1`, d["id"])
	claim = f.request(t, "POST", "/api/internal/channels/deliveries/claim", map[string]any{}, "")
	if claim.Code != 200 {
		t.Fatal(claim.Body.String())
	}
	json.Unmarshal(claim.Body.Bytes(), &d)
	w = f.request(t, "POST", receiptPath, map[string]any{"leaseToken": d["leaseToken"], "providerMessageId": "om-result"}, "")
	if w.Code != 204 {
		t.Fatal(w.Body.String())
	}
	var state, provider string
	f.s.db.Pool.QueryRow(ctx, `SELECT state,provider_message_id FROM channel_deliveries WHERE id=$1`, d["id"]).Scan(&state, &provider)
	if state != "provider_accepted" || provider != "om-result" {
		t.Fatal(state, provider)
	}
	reply := f.in
	reply.MessageID = uuid.NewString()
	reply.ReplyToID = "om-result"
	reply.Text = "thanks"
	replyOut := f.send(t, reply)
	if replyOut.CommentID == nil {
		t.Fatal(replyOut)
	}
	issue, e := f.s.channelWork.Store.Collaboration().GetIssue(ctx, *out.IssueID)
	if e != nil || issue.Status == model.IssueDone {
		t.Fatal("thanks accepted work")
	}
	// Revoking the identity cancels queued content before transport can claim it.
	f.s.db.Pool.Exec(ctx, `DELETE FROM channel_identities WHERE channel_id=$1`, f.ch.ChannelID)
	f.s.db.Pool.Exec(ctx, `UPDATE channel_deliveries SET next_attempt=now()-interval '1 second' WHERE channel_id=$1 AND state='pending'`, f.ch.ChannelID)
	claim = f.request(t, "POST", "/api/internal/channels/deliveries/claim", map[string]any{}, "")
	if claim.Code != 204 {
		t.Fatal("revoked identity received content", claim.Body.String())
	}
}
func TestChannelPairingAndConfigurationCAS(t *testing.T) {
	f := channelSetup(t)
	path := "/api/channels/" + f.ch.ChannelID
	w := f.request(t, "POST", path+"/pairing", map[string]any{}, f.other)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	var pair map[string]any
	json.Unmarshal(w.Body.Bytes(), &pair)
	in := f.in
	in.SenderID = "new-sender"
	in.Text = pair["command"].(string)
	w = f.request(t, "POST", "/api/internal/channels/inbound", in, "")
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	var user string
	f.s.db.Pool.QueryRow(t.Context(), `SELECT user_id FROM channel_identities WHERE channel_id=$1 AND sender_id='new-sender'`, f.ch.ChannelID).Scan(&user)
	if user != f.other {
		t.Fatal("paired as configurator", user)
	}
	w = f.request(t, "POST", "/api/internal/channels/inbound", in, "")
	if !strings.Contains(w.Body.String(), "无效") {
		t.Fatal("one-time pairing reused")
	}
	cfg, e := f.s.loadChannelWorkSettings(t.Context(), f.ch.ChannelID)
	if e != nil {
		t.Fatal(e)
	}
	cfg.DefaultTarget.TargetRef = "other-namespace-agent"
	w = f.request(t, "PUT", path+"/collaboration", cfg, f.user)
	if w.Code != 400 {
		t.Fatal("foreign target accepted")
	}
	cfg.DefaultTarget.TargetRef = "worker"
	w = f.request(t, "PUT", path+"/collaboration", cfg, f.user)
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	w = f.request(t, "PUT", path+"/collaboration", cfg, f.user)
	if w.Code != 409 {
		t.Fatal("stale configuration overwrite")
	}
}

func TestChannelTeamUsesDurableTeamTaskAndMembershipRevocation(t *testing.T) {
	f := channelSetup(t)
	ctx := t.Context()
	repo := f.s.channelWork.Store.Collaboration()
	team, e := repo.CreateTeam(ctx, &model.CollaborationTeam{Tenant: f.n.Tenant, Namespace: f.n.Name, Name: "Operations", LeaderAgentRef: "worker"})
	if e != nil {
		t.Fatal(e)
	}
	cfg, _ := f.s.loadChannelWorkSettings(ctx, f.ch.ChannelID)
	cfg.DefaultTarget = ChannelTarget{"team", team.ID.String()}
	f.s.db.Pool.Exec(ctx, `UPDATE channel_work_settings SET config=$2::jsonb WHERE channel_id=$1`, f.ch.ChannelID, mustJSON(cfg))
	in := f.in
	in.SenderID = "sender-b"
	in.PeerID = "chat-b"
	out := f.send(t, in)
	if out.IssueID == nil {
		t.Fatal(out)
	}
	tasks, e := repo.ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: *out.IssueID})
	if e != nil || len(tasks) != 1 || tasks[0].TeamID == nil || *tasks[0].TeamID != team.ID || !tasks[0].LeaderTask || tasks[0].OrchestrationRunID == uuid.Nil {
		t.Fatalf("Team did not create orchestration task: %+v %v", tasks, e)
	}
	n, _ := f.s.channelWork.Store.Access().GetNamespace(ctx, f.n.Tenant, f.n.Name)
	delete(n.Members, f.other)
	if _, e = f.s.channelWork.Store.Access().PutNamespace(ctx, n, n.Version, f.user); e != nil {
		t.Fatal(e)
	}
	in.MessageID = uuid.NewString()
	in.Text = "/issue " + out.IssueID.String() + " continue"
	w := f.request(t, "POST", "/api/internal/channels/inbound", in, "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), "权限") {
		t.Fatalf("revoked membership retained ingress: %d %s", w.Code, w.Body.String())
	}
	claim := f.request(t, "POST", "/api/internal/channels/deliveries/claim", nil, "")
	if claim.Code != 204 {
		t.Fatal("revoked member retained delivery")
	}
}

func TestChannelAcceptanceIsExplicitAndVersionFenced(t *testing.T) {
	f := channelSetup(t)
	ctx := t.Context()
	repo := f.s.channelWork.Store.Collaboration()
	issue, e := repo.CreateIssue(ctx, &model.Issue{Tenant: f.n.Tenant, Namespace: f.n.Name, Title: "Ready for review", Status: model.IssueInReview, Creator: model.Actor{Type: model.ActorHuman, Ref: f.user}, Access: model.IssueAccess{Mode: "private"}})
	if e != nil {
		t.Fatal(e)
	}
	in := f.in
	in.Text = "/accept " + issue.ID.String() + " 100"
	if out := f.send(t, in); out.IssueID != nil {
		t.Fatal("stale acceptance applied")
	}
	in.MessageID = uuid.NewString()
	in.Text = "/accept " + issue.ID.String() + " 1"
	if out := f.send(t, in); out.IssueID == nil {
		t.Fatal(out)
	}
	current, _ := repo.GetIssue(ctx, issue.ID)
	if current.Status != model.IssueDone {
		t.Fatal("explicit acceptance failed")
	}
}

func TestChannelDeliveryLeaseExpiryAndUnsubscribe(t *testing.T) {
	f := channelSetup(t)
	ctx := t.Context()
	out := f.send(t, f.in)
	claim := f.request(t, "POST", "/api/internal/channels/deliveries/claim", nil, "")
	if claim.Code != 200 {
		t.Fatal(claim.Body.String())
	}
	var first map[string]any
	json.Unmarshal(claim.Body.Bytes(), &first)
	f.s.db.Pool.Exec(ctx, `UPDATE channel_deliveries SET next_attempt=now()-interval '1 second' WHERE id=$1`, first["id"])
	claim = f.request(t, "POST", "/api/internal/channels/deliveries/claim", nil, "")
	var second map[string]any
	json.Unmarshal(claim.Body.Bytes(), &second)
	if first["id"] != second["id"] || first["leaseToken"] == second["leaseToken"] {
		t.Fatal("expired lease did not retain delivery identity with new fence")
	}
	receiptPath := "/api/internal/channels/deliveries/" + first["id"].(string) + "/receipt"
	w := f.request(t, "POST", receiptPath, map[string]any{"leaseToken": first["leaseToken"], "providerMessageId": "stale"}, "")
	if w.Code != 409 {
		t.Fatal("replaced worker committed a stale receipt")
	}
	f.request(t, "POST", receiptPath, map[string]any{"leaseToken": second["leaseToken"], "providerMessageId": "ok"}, "")
	var linkID uuid.UUID
	f.s.db.Pool.QueryRow(ctx, `SELECT id FROM channel_work_links WHERE channel_id=$1`, f.ch.ChannelID).Scan(&linkID)
	w = f.request(t, "DELETE", "/api/channels/"+f.ch.ChannelID+"/links/"+linkID.String(), nil, f.user)
	if w.Code != 204 {
		t.Fatal(w.Body.String())
	}
	follow := f.in
	follow.MessageID = uuid.NewString()
	follow.Text = "/follow " + out.IssueID.String()
	f.send(t, follow)
	var active bool
	f.s.db.Pool.QueryRow(ctx, `SELECT active FROM channel_work_links WHERE id=$1`, linkID).Scan(&active)
	if !active {
		t.Fatal("explicit follow did not restore subscription")
	}
}

func TestChannelLegacyDMIsolationAndRouting(t *testing.T) {
	main, defaultAgent, bindings := "MAIN", "default", `[{"peer":"DIRECT:alice-chat","agentId":"peer-agent"},{"account":"org","agentId":"org-agent"}]`
	ch := channelRow{ChannelID: "ch", DmScope: &main, DefaultAgentID: &defaultAgent, BindingsJSON: &bindings}
	in := ChannelInbound{ChannelID: "ch", AccountID: "org", PeerKind: "DIRECT", PeerID: "alice-chat"}
	if channelLegacyTarget(ch, in) != "peer-agent" {
		t.Fatal("legacy peer routing lost")
	}
	other := in
	other.PeerID = "another-chat"
	if channelLegacyExternalKey(ch, in, "alice") != channelLegacyExternalKey(ch, other, "alice") {
		t.Fatal("personal MAIN no longer shares the same verified owner")
	}
	if channelLegacyExternalKey(ch, in, "alice") == channelLegacyExternalKey(ch, in, "bob") {
		t.Fatal("MAIN shares identities")
	}
	if channelLegacyTarget(ch, other) != "org-agent" {
		t.Fatal("legacy account fallback lost")
	}
	peer := "PER_PEER"
	ch.DmScope = &peer
	if channelLegacyExternalKey(ch, in, "alice") == channelLegacyExternalKey(ch, other, "alice") {
		t.Fatal("per-peer isolation lost")
	}
}

func TestChannelPendingApprovalNotificationIsPrivateAndDoesNotAuthorizeTool(t *testing.T) {
	f := channelSetup(t)
	ctx := t.Context()
	out := f.send(t, f.in)
	repo := f.s.channelWork.Store.Collaboration()
	approval, e := repo.CreateApproval(ctx, &model.Approval{Tenant: f.n.Tenant, Namespace: f.n.Name, TargetType: "issue", TargetRef: out.IssueID.String(), IssueID: out.IssueID, ApproverRef: f.user, RequestedBy: model.Actor{Type: model.ActorAgent, Ref: "worker"}, Status: model.ApprovalPending, Request: json.RawMessage(`{"secretToolInput":"do not publish"}`)})
	if e != nil {
		t.Fatal(e)
	}
	if _, e = f.s.pollChannelLink(ctx); e != nil {
		t.Fatal(e)
	}
	var id uuid.UUID
	var text string
	e = f.s.db.Pool.QueryRow(ctx, `SELECT id,text FROM channel_deliveries WHERE channel_id=$1 AND event_key LIKE 'approval:%'`, f.ch.ChannelID).Scan(&id, &text)
	if e != nil || strings.Contains(text, "secretToolInput") || !strings.Contains(text, "控制台") {
		t.Fatalf("bad approval notification: %s %v", text, e)
	}
	reply := f.in
	reply.MessageID = uuid.NewString()
	reply.Text = "/issue " + out.IssueID.String() + " agree"
	f.send(t, reply)
	current, e := repo.GetApproval(ctx, approval.ID)
	if e != nil || current.Status != model.ApprovalPending {
		t.Fatal("text reply authorized tool")
	}
	if _, e = repo.DecideApproval(ctx, approval.ID, approval.Version, model.ApprovalApproved, model.Actor{Type: model.ActorHuman, Ref: f.user}, json.RawMessage(`{}`)); e != nil {
		t.Fatal(e)
	}
	f.s.db.Pool.Exec(ctx, `UPDATE channel_deliveries SET next_attempt=now()+interval '1 hour' WHERE channel_id=$1 AND id<>$2`, f.ch.ChannelID, id)
	w := f.request(t, "POST", "/api/internal/channels/deliveries/claim", nil, "")
	if w.Code != 204 {
		t.Fatal("obsolete approval prompt published")
	}
}
