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

package httpapi

import (
	"context"
	"encoding/json"
	"github.com/gin-gonic/gin"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/dataplane"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
	"net/http/httptest"
	"strings"
	"testing"
)

func accessTestServer(t *testing.T, configs ...store.Config) (*Server, store.Store) {
	t.Helper()
	gin.SetMode(gin.TestMode)
	cfg := store.Config{Driver: store.DriverMemory}
	if len(configs) > 0 {
		cfg = configs[0]
	}
	st, err := store.Open(context.Background(), cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	_, err = st.Access().PutNamespace(context.Background(), &controlmodel.Namespace{Tenant: "default", Name: "engineering", DisplayName: "Engineering", Kind: "shared", Owner: "alice", Members: map[string][]string{"bob": {"member"}, "carol": {"viewer"}, "operator": {"operator"}, "auditor": {"auditor"}, "developer": {"developer"}}}, 0, "alice")
	if err != nil {
		t.Fatal(err)
	}
	s := NewServer(ServerOptions{Store: st})
	s.router = gin.New()
	s.router.ContextWithFallback = true
	s.router.Use(func(c *gin.Context) {
		user := c.GetHeader("X-Test-User")
		if user != "" {
			c.Set("userId", user)
			c.Set("username", user)
			c.Set("groups", []string{"user"})
			c.Set(ctxConsoleAuth, true)
		}
		c.Next()
	})
	s.registerRoutes()
	return s, st
}
func accessRequest(s *Server, user, method, path, body string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(method, path, strings.NewReader(body))
	r.Header.Set("X-Test-User", user)
	r.Header.Set("Content-Type", "application/json")
	w := httptest.NewRecorder()
	s.router.ServeHTTP(w, r)
	return w
}
func accessIssue(t *testing.T, st store.Store, title, creator, mode string, parent *uuid.UUID) *controlmodel.Issue {
	t.Helper()
	v, e := st.Collaboration().CreateIssue(context.Background(), &controlmodel.Issue{Tenant: "default", Namespace: "engineering", Title: title, Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: creator}, ParentIssueID: parent, Access: controlmodel.IssueAccess{Mode: mode}})
	if e != nil {
		t.Fatal(e)
	}
	return v
}

func TestNamespaceMembershipAndPrivateIssueInheritance(t *testing.T) {
	s, st := accessTestServer(t)
	ctx := context.Background()
	private := accessIssue(t, st, "private-alice", "alice", "private", nil)
	child := accessIssue(t, st, "child-secret", "bob", "namespace", &private.ID)
	public := accessIssue(t, st, "team-work", "alice", "namespace", nil)
	for _, tc := range []struct {
		user, path string
		status     int
	}{{"alice", private.ID.String(), 200}, {"bob", private.ID.String(), 404}, {"bob", child.ID.String(), 404}, {"bob", public.ID.String(), 200}, {"outsider", public.ID.String(), 404}, {"operator", private.ID.String(), 404}, {"developer", private.ID.String(), 404}, {"auditor", private.ID.String(), 200}} {
		w := accessRequest(s, tc.user, "GET", "/api/v1/issues/"+tc.path, "")
		if w.Code != tc.status {
			t.Errorf("%s %s: %d %s", tc.user, tc.path, w.Code, w.Body.String())
		}
	}
	w := accessRequest(s, "bob", "GET", "/api/v1/issues?tenant=default&namespace=engineering&limit=1", "")
	if w.Code != 200 || strings.Contains(w.Body.String(), "private-alice") || strings.Contains(w.Body.String(), "child-secret") || !strings.Contains(w.Body.String(), "team-work") {
		t.Fatalf("filtered page: %d %s", w.Code, w.Body.String())
	}
	private.Access = controlmodel.IssueAccess{Mode: "shared", Members: map[string]string{"bob": "reader"}}
	private, err := st.Collaboration().UpdateIssue(ctx, private, private.Version, private.Creator)
	if err != nil {
		t.Fatal(err)
	}
	if w = accessRequest(s, "bob", "GET", "/api/v1/issues/"+child.ID.String(), ""); w.Code != 200 {
		t.Fatalf("inherited grant: %d %s", w.Code, w.Body.String())
	}
	if w = accessRequest(s, "bob", "PATCH", "/api/v1/issues/"+child.ID.String(), `{"title":"stolen"}`); w.Code != 404 {
		t.Fatalf("reader mutation: %d %s", w.Code, w.Body.String())
	}
	private.Access = controlmodel.IssueAccess{Mode: "private"}
	if _, err = st.Collaboration().UpdateIssue(ctx, private, private.Version, private.Creator); err != nil {
		t.Fatal(err)
	}
	if w = accessRequest(s, "bob", "GET", "/api/v1/issues/"+child.ID.String(), ""); w.Code != 404 {
		t.Fatal("revocation did not propagate")
	}
	n, _ := st.Access().GetNamespace(ctx, "default", "engineering")
	delete(n.Members, "bob")
	_, _ = st.Access().PutNamespace(ctx, n, n.Version, "alice")
	if w = accessRequest(s, "bob", "GET", "/api/v1/issues/"+public.ID.String(), ""); w.Code != 404 {
		t.Fatal("revoked namespace membership still effective")
	}
}

func TestNamespaceScopeCannotBeForgedAndViewerCannotCreate(t *testing.T) {
	s, st := accessTestServer(t)
	v := accessIssue(t, st, "private", "alice", "private", nil)
	for _, tc := range []struct {
		user, method, path, body string
		status                   int
	}{
		{"bob", "GET", "/api/v1/issues/" + v.ID.String() + "?tenant=default&namespace=" + personalNamespace("bob"), "", 404},
		{"bob", "POST", "/api/v1/issues?tenant=default&namespace=engineering", `{"tenant":"default","namespace":"other","title":"x"}`, 400},
		{"carol", "POST", "/api/v1/issues", `{"tenant":"default","namespace":"engineering","title":"x"}`, 403},
		{"bob", "POST", "/api/v1/namespaces", `{"name":"existing","displayName":"Claim existing data"}`, 403},
		{"bob", "PUT", "/api/v1/namespaces/engineering", `{"members":{"bob":["admin"]},"version":1}`, 404},
	} {
		w := accessRequest(s, tc.user, tc.method, tc.path, tc.body)
		if w.Code != tc.status {
			t.Errorf("%s %s: %d %s", tc.method, tc.path, w.Code, w.Body.String())
		}
	}
	w := accessRequest(s, "bob", "GET", "/api/v1/me/scope", "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), personalNamespace("bob")) || !strings.Contains(w.Body.String(), `"namespace":"default"`) {
		t.Fatalf("global default and personal scope: %s", w.Body.String())
	}
}

func TestPrivateRunAndSessionAccessDoesNotFollowAgentOwnership(t *testing.T) {
	s, st := accessTestServer(t)
	ctx := context.Background()
	v := accessIssue(t, st, "secret", "alice", "private", nil)
	run, err := st.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{Tenant: v.Tenant, Namespace: v.Namespace, RootIssueID: v.ID, Mode: controlmodel.RunModeDeclared, State: controlmodel.RunRunning, CreatedBy: v.Creator})
	if err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"/api/v1/orchestration-runs/" + run.ID.String(), "/api/v1/orchestration-runs/" + run.ID.String() + "/graph"} {
		if w := accessRequest(s, "developer", "GET", path, ""); w.Code != 404 {
			t.Fatalf("private run: %d %s", w.Code, w.Body.String())
		}
	}
	w := accessRequest(s, "bob", "GET", "/api/v1/orchestration-runs?tenant=default&namespace=engineering", "")
	if w.Code != 200 || strings.Contains(w.Body.String(), run.ID.String()) {
		t.Fatalf("run list: %d %s", w.Code, w.Body.String())
	}
	session, err := st.Sessions().Upsert(ctx, &store.Session{Tenant: "default", Namespace: "engineering", AgentName: "shared", SessionID: "alice-chat"})
	if err != nil {
		t.Fatal(err)
	}
	_, err = st.Chats().Create(ctx, &controlmodel.Chat{Tenant: "default", Namespace: "engineering", CreatorRef: "alice", AgentID: uuid.New(), AgentName: "shared", SessionID: session.ID, RuntimeSession: session.SessionID, Title: "private chat", Status: controlmodel.ChatActive})
	if err != nil {
		t.Fatal(err)
	}
	if w = accessRequest(s, "developer", "GET", "/api/v1/sessions/"+session.ID.String(), ""); w.Code != 404 {
		t.Fatalf("agent developer session: %d %s", w.Code, w.Body.String())
	}
	if w = accessRequest(s, "alice", "GET", "/api/v1/sessions/"+session.ID.String(), ""); w.Code != 200 {
		t.Fatalf("own session: %d %s", w.Code, w.Body.String())
	}
	w = accessRequest(s, "bob", "GET", "/api/v1/sessions?tenant=default&namespace=engineering", "")
	if w.Code != 200 || strings.Contains(w.Body.String(), session.ID.String()) {
		t.Fatalf("session list: %d %s", w.Code, w.Body.String())
	}
}

func TestIssueSharingOnlyCreatorAndCurrentMembers(t *testing.T) {
	s, st := accessTestServer(t)
	v := accessIssue(t, st, "shared", "alice", "namespace", nil)
	body, _ := json.Marshal(map[string]any{"version": v.Version, "access": controlmodel.IssueAccess{Mode: "shared", Members: map[string]string{"bob": "contributor"}}})
	path := "/api/v1/issues/" + v.ID.String() + "/access"
	if w := accessRequest(s, "bob", "PUT", path, string(body)); w.Code != 403 {
		t.Fatalf("noncreator sharing: %d %s", w.Code, w.Body.String())
	}
	if w := accessRequest(s, "alice", "PUT", path, string(body)); w.Code != 200 {
		t.Fatalf("creator sharing: %d %s", w.Code, w.Body.String())
	}
	if w := accessRequest(s, "alice", "PUT", path, string(body)); w.Code != 409 {
		t.Fatalf("stale sharing: %d %s", w.Code, w.Body.String())
	}
}

func TestNamespaceRejectsForeignNestedReferencesAndApprovalTargets(t *testing.T) {
	s, st := accessTestServer(t)
	private := accessIssue(t, st, "Alice secret", "alice", "private", nil)
	_, err := st.Access().PutNamespace(context.Background(), &controlmodel.Namespace{Tenant: "default", Name: "other", DisplayName: "Other", Kind: "shared", Owner: "developer"}, 0, "developer")
	if err != nil {
		t.Fatal(err)
	}
	foreign, err := st.Collaboration().CreateTeam(context.Background(), &controlmodel.CollaborationTeam{Tenant: "default", Namespace: "other", Name: "foreign", LeaderAgentRef: uuid.NewString()})
	if err != nil {
		t.Fatal(err)
	}
	tests := []struct{ user, path, body string }{
		{"bob", "/api/v1/approvals", `{"tenant":"default","namespace":"engineering","targetType":"issue","targetRef":"` + private.ID.String() + `","approverRef":"bob"}`},
		{"developer", "/api/v1/orchestration-definitions", `{"tenant":"default","namespace":"engineering","name":"escape","draftSpec":{"nodes":[{"key":"worker","type":"team","teamRef":"` + foreign.ID.String() + `"}]}}`},
		{"bob", "/api/v1/automations", `{"tenant":"default","namespace":"engineering","name":"escape","actionConfig":{"issueId":"` + private.ID.String() + `"}}`},
	}
	for _, tc := range tests {
		w := accessRequest(s, tc.user, "POST", tc.path, tc.body)
		if w.Code != 404 {
			t.Fatalf("%s: %d %s", tc.path, w.Code, w.Body.String())
		}
	}
}

func TestNamespaceEventAccessRechecksLivePolicy(t *testing.T) {
	s, st := accessTestServer(t)
	ctx := context.Background()
	issue := accessIssue(t, st, "shared event", "alice", "namespace", nil)
	n, _ := st.Access().GetNamespace(ctx, "default", "engineering")
	a := &namespaceAccess{User: "bob", Refs: []string{"bob"}, Namespace: n, Roles: n.Roles("bob")}
	event := &controlmodel.OutboxEvent{AggregateType: "issue", AggregateID: issue.ID.String()}
	if !s.canReceiveWorkEvent(ctx, a, event) {
		t.Fatal("authorized event rejected")
	}
	issue.Access = controlmodel.IssueAccess{Mode: "private"}
	_, err := st.Collaboration().UpdateIssue(ctx, issue, issue.Version, issue.Creator)
	if err != nil {
		t.Fatal(err)
	}
	if s.canReceiveWorkEvent(ctx, a, event) {
		t.Fatal("revoked event delivered")
	}
}

func TestNamespaceOverviewDoesNotReuseTenantData(t *testing.T) {
	s, st := accessTestServer(t)
	ctx := context.Background()
	for _, owner := range []string{"alice", "operator"} {
		session, e := st.Sessions().Upsert(ctx, &store.Session{Tenant: "default", Namespace: "engineering", SessionID: owner, AgentName: "shared", Phase: "active"})
		if e != nil {
			t.Fatal(e)
		}
		_, e = st.Chats().Create(ctx, &controlmodel.Chat{ID: uuid.New(), Tenant: "default", Namespace: "engineering", CreatorRef: owner, AgentID: uuid.New(), AgentName: "shared", SessionID: session.ID, RuntimeSession: session.SessionID, Title: owner, Status: controlmodel.ChatActive})
		if e != nil {
			t.Fatal(e)
		}
	}
	w := accessRequest(s, "operator", "GET", "/api/v1/overview?namespace=engineering", "")
	var body struct {
		SessionCount int `json:"sessionCount"`
	}
	_ = json.Unmarshal(w.Body.Bytes(), &body)
	if w.Code != 200 || body.SessionCount != 1 {
		t.Fatalf("private session count: %d %s", w.Code, w.Body.String())
	}
}

func TestNamespaceArtifactFollowsRootSharing(t *testing.T) {
	s, st := accessTestServer(t)
	ctx := context.Background()
	issue := accessIssue(t, st, "artifact-secret", "alice", "private", nil)
	artifact, err := st.Collaboration().CreateArtifact(ctx, &controlmodel.Artifact{Tenant: "default", Namespace: "engineering", StorageProvider: "contract", StorageKey: "objects/private", Filename: "private.txt", ContentType: "text/plain", SizeBytes: 6, Checksum: "sha256:contract", Uploader: issue.Creator}, []controlmodel.ArtifactLink{{TargetType: "issue", TargetRef: issue.ID.String(), Relation: "result"}})
	if err != nil {
		t.Fatal(err)
	}
	n, _ := st.Access().GetNamespace(ctx, "default", "engineering")
	a := &namespaceAccess{User: "bob", Refs: []string{"bob"}, Namespace: n, Roles: n.Roles("bob")}
	if s.canAccessArtifact(ctx, a, artifact.ID, false) {
		t.Fatal("private artifact exposed")
	}
	issue.Access = controlmodel.IssueAccess{Mode: "shared", Members: map[string]string{"bob": "reader"}}
	issue, err = st.Collaboration().UpdateIssue(ctx, issue, issue.Version, issue.Creator)
	if err != nil {
		t.Fatal(err)
	}
	if !s.canAccessArtifact(ctx, a, artifact.ID, false) || s.canAccessArtifact(ctx, a, artifact.ID, true) {
		t.Fatal("artifact reader grant not enforced")
	}
	issue.Access = controlmodel.IssueAccess{Mode: "private"}
	_, err = st.Collaboration().UpdateIssue(ctx, issue, issue.Version, issue.Creator)
	if err != nil {
		t.Fatal(err)
	}
	if s.canAccessArtifact(ctx, a, artifact.ID, false) {
		t.Fatal("artifact grant survived revocation")
	}
}

func TestNamespaceInfrastructureDiscoveryIsScoped(t *testing.T) {
	s, _ := accessTestServer(t)
	s.registry = dataplane.NewRegistry()
	s.registry.Upsert(dataplane.Entry{Tenant: "default", Namespace: "engineering", InstanceID: "own", AgentName: "worker"})
	s.registry.Upsert(dataplane.Entry{Tenant: "default", Namespace: "other", InstanceID: "foreign", AgentName: "secret"})
	w := accessRequest(s, "operator", "GET", "/api/v1/dataplanes?namespace=engineering", "")
	if w.Code != 200 || strings.Contains(w.Body.String(), "foreign") || !strings.Contains(w.Body.String(), "own") {
		t.Fatalf("infrastructure scope: %d %s", w.Code, w.Body.String())
	}
}

func TestDeletedChatSessionRemainsReadableByOwner(t *testing.T) {
	testDeletedChatSessionAccess(t, store.Config{Driver: store.DriverMemory})
}

func TestDeletedChatSessionRemainsReadableByOwnerPostgres(t *testing.T) {
	testDeletedChatSessionAccess(t, acceptancePostgresConfig(t))
}

func testDeletedChatSessionAccess(t *testing.T, cfg store.Config) {
	s, st := accessTestServer(t, cfg)
	ctx := context.Background()
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "default", Namespace: "engineering", AgentKey: "deleted-chat-agent", DisplayName: "Chat Agent", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	session, err := st.Sessions().Upsert(ctx, &store.Session{Tenant: "default", Namespace: "engineering", AgentID: agent.ID, AgentName: agent.AgentKey, SessionID: "deleted-chat-runtime", Phase: "idle"})
	if err != nil {
		t.Fatal(err)
	}
	// bob is an ordinary member, not an auditor or administrator.
	_, err = st.Chats().Create(ctx, &controlmodel.Chat{Tenant: session.Tenant, Namespace: session.Namespace, CreatorRef: "bob", AgentID: agent.ID, AgentName: agent.AgentKey, SessionID: session.ID, RuntimeSession: session.SessionID, Title: "Deleted private chat", Status: controlmodel.ChatDeleted})
	if err != nil {
		t.Fatal(err)
	}
	base := "/api/v1/sessions/" + session.ID.String()
	for _, suffix := range []string{"", "/events", "/turns", "/commands"} {
		for _, query := range []string{"?tenant=default&namespace=engineering", "?tenant=default&namespace=engineering&agentId=" + agent.ID.String()} {
			for _, user := range []string{"bob", "alice", "developer", "operator", "auditor", "outsider"} {
				want := 404
				if user == "bob" || user == "auditor" {
					want = 200
				}
				w := accessRequest(s, user, "GET", base+suffix+query, "")
				if w.Code != want {
					t.Errorf("%s GET %s: got %d, want %d: %s", user, suffix+query, w.Code, want, w.Body.String())
				}
			}
		}
	}
	if w := accessRequest(s, "bob", "GET", base+"?tenant=default&namespace=other", ""); w.Code != 404 {
		t.Fatalf("cross-scope read: %d %s", w.Code, w.Body.String())
	}
	n, err := st.Access().GetNamespace(ctx, "default", "engineering")
	if err != nil {
		t.Fatal(err)
	}
	a := &namespaceAccess{User: "bob", Refs: []string{"bob"}, Namespace: n, Roles: n.Roles("bob")}
	if s.canAccessSession(ctx, a, session, true) {
		t.Fatal("deleted Chat granted write access to its Session")
	}
	if w := accessRequest(s, "bob", "POST", base+"/user-message?tenant=default&namespace=engineering", `{"content":"should not run"}`); w.Code != 404 {
		t.Fatalf("deleted session mutation: %d %s", w.Code, w.Body.String())
	}
	list := accessRequest(s, "bob", "GET", "/api/v1/sessions?tenant=default&namespace=engineering&agentId="+agent.ID.String(), "")
	if list.Code != 200 || !strings.Contains(list.Body.String(), session.ID.String()) {
		t.Fatalf("owner's execution history disappeared: %d %s", list.Code, list.Body.String())
	}
}
