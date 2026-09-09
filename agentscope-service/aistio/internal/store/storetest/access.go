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

package storetest

import (
	"context"
	"github.com/google/uuid"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"testing"
)

func testNamespaceAccess(t *testing.T, ctx context.Context, s store.Store) {
	tenant := "access-" + uuid.NewString()
	n := &controlmodel.Namespace{Tenant: tenant, Name: "team", DisplayName: "Team", Kind: "shared", Owner: "alice", Members: map[string][]string{"bob": {"member"}}}
	n, err := s.Access().PutNamespace(ctx, n, 0, "alice")
	if err != nil {
		t.Fatal(err)
	}
	if _, err = s.Access().PutNamespace(ctx, n, 0, "alice"); err != store.ErrConflict {
		t.Fatalf("duplicate creation: %v", err)
	}
	items, err := s.Access().ListNamespaces(ctx, tenant, "bob", 10, 0)
	if err != nil || len(items) != 1 {
		t.Fatalf("member list: %v %+v", err, items)
	}
	items, err = s.Access().ListNamespaces(ctx, tenant, "outsider", 10, 0)
	if err != nil || len(items) != 0 {
		t.Fatalf("outsider list: %v %+v", err, items)
	}
	globalTenant := "global-" + uuid.NewString()
	global, err := s.Access().PutNamespace(ctx, &controlmodel.Namespace{Tenant: globalTenant, Name: "default", DisplayName: "Default", Kind: "global", Owner: "system", Members: map[string][]string{}}, 0, "system")
	if err != nil {
		t.Fatal(err)
	}
	items, err = s.Access().ListNamespaces(ctx, globalTenant, "outsider", 10, 0)
	if err != nil || len(items) != 1 || items[0].Name != global.Name || !controlmodel.NamespaceAllows(items[0].Roles("outsider"), "operate") || controlmodel.NamespaceAllows(items[0].Roles("outsider"), "members.manage") {
		t.Fatalf("global namespace access: %v %+v", err, items)
	}
	// Returned membership maps are detached from the stored authority.
	n.Members["intruder"] = []string{"admin"}
	reloaded, _ := s.Access().GetNamespace(ctx, tenant, n.Name)
	if len(reloaded.Roles("intruder")) != 0 {
		t.Fatal("mutable membership escaped repository")
	}
	delete(n.Members, "bob")
	n, err = s.Access().PutNamespace(ctx, n, n.Version, "alice")
	if err != nil {
		t.Fatal(err)
	}
	items, err = s.Access().ListNamespaces(ctx, tenant, "bob", 10, 0)
	if err != nil || len(items) != 0 {
		t.Fatalf("member revoke: %v %+v", err, items)
	}
	_, err = s.Access().PutNamespace(ctx, n, n.Version-1, "alice")
	if err != store.ErrConflict {
		t.Fatalf("stale membership: %v", err)
	}
	if _, err = s.Access().TransferNamespace(ctx, tenant, n.Name, "bob", n.Version-1, "alice"); err != store.ErrConflict {
		t.Fatalf("stale transfer: %v", err)
	}
	n, err = s.Access().TransferNamespace(ctx, tenant, n.Name, "bob", n.Version, "alice")
	if err != nil || n.Owner != "bob" || !controlmodel.NamespaceAllows(n.Roles("alice"), "members.manage") {
		t.Fatalf("ownership transfer: %+v %v", n, err)
	}
	n.Archived = true
	n, err = s.Access().PutNamespace(ctx, n, n.Version, "bob")
	if err != nil || len(n.Roles("bob")) != 0 {
		t.Fatalf("archive authority: %+v %v", n, err)
	}
	items, err = s.Access().ListNamespaces(ctx, tenant, "bob", 10, 0)
	if err != nil || len(items) != 0 {
		t.Fatalf("archived namespace in selector: %+v %v", items, err)
	}
	audit, err := s.Access().ListNamespaceAudit(ctx, tenant, n.Name, 50, 0)
	if err != nil || len(audit) != 4 || audit[0].Version != n.Version || !audit[0].Namespace.Archived {
		t.Fatalf("namespace audit: %+v %v", audit, err)
	}
	n.Archived = false
	n, err = s.Access().PutNamespace(ctx, n, n.Version, "bob")
	if err != nil {
		t.Fatal(err)
	}

	create := func(title, mode string, parent *uuid.UUID) *controlmodel.Issue {
		v, e := s.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: tenant, Namespace: n.Name, Title: title, Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "alice"}, Access: controlmodel.IssueAccess{Mode: mode}, ParentIssueID: parent})
		if e != nil {
			t.Fatal(e)
		}
		return v
	}
	private := create("secret", "private", nil)
	child := create("nested secret", "namespace", &private.ID)
	visible := create("visible", "namespace", nil)
	reader := store.WithWorkAccess(ctx, store.WorkAccess{Restricted: true, Refs: []string{"bob"}})
	issues, err := s.Collaboration().ListIssues(reader, store.IssueFilter{Tenant: tenant, Namespace: n.Name, Limit: 1})
	if err != nil || len(issues) != 1 || issues[0].ID != visible.ID {
		t.Fatalf("pre-pagination access: %v %+v", err, issues)
	}
	private.Access = controlmodel.IssueAccess{Mode: "shared", Members: map[string]string{"bob": "reader"}}
	private, err = s.Collaboration().UpdateIssue(ctx, private, private.Version, private.Creator)
	if err != nil {
		t.Fatal(err)
	}
	issues, err = s.Collaboration().ListIssues(reader, store.IssueFilter{Tenant: tenant, Namespace: n.Name, ParentID: &private.ID, Limit: 10})
	if err != nil || len(issues) != 1 || issues[0].ID != child.ID {
		t.Fatalf("inherited grant: %v %+v", err, issues)
	}
	private.Access = controlmodel.IssueAccess{Mode: "private"}
	_, err = s.Collaboration().UpdateIssue(ctx, private, private.Version, private.Creator)
	if err != nil {
		t.Fatal(err)
	}
	issues, err = s.Collaboration().ListIssues(reader, store.IssueFilter{Tenant: tenant, Namespace: n.Name, ParentID: &private.ID, Limit: 10})
	if err != nil || len(issues) != 0 {
		t.Fatalf("inherited revoke: %v %+v", err, issues)
	}
	for _, issue := range []*controlmodel.Issue{private, visible} {
		_, err = s.Orchestration().CreateRun(ctx, &controlmodel.OrchestrationRun{Tenant: tenant, Namespace: n.Name, RootIssueID: issue.ID, Mode: controlmodel.RunModeDirect, State: controlmodel.RunRunning, CreatedBy: issue.Creator})
		if err != nil {
			t.Fatal(err)
		}
	}
	runs, err := s.Orchestration().ListRuns(reader, store.OrchestrationRunFilter{Tenant: tenant, Namespace: n.Name, Limit: 1})
	if err != nil || len(runs) != 1 || runs[0].RootIssueID != visible.ID {
		t.Fatalf("run filtering: %v %+v", err, runs)
	}
	for _, issue := range []*controlmodel.Issue{private, visible} {
		_, err = s.Collaboration().CreateApproval(ctx, &controlmodel.Approval{Tenant: tenant, Namespace: n.Name, TargetType: "issue", TargetRef: issue.ID.String(), IssueID: &issue.ID, ApproverRef: "bob", RequestedBy: issue.Creator, Status: controlmodel.ApprovalPending})
		if err != nil {
			t.Fatal(err)
		}
	}
	approvals, err := s.Collaboration().ListApprovals(reader, store.ApprovalFilter{Tenant: tenant, Namespace: n.Name, ApproverRef: "bob", Limit: 1})
	if err != nil || len(approvals) != 1 || approvals[0].TargetRef != visible.ID.String() {
		t.Fatalf("approval filtering: %v %+v", err, approvals)
	}
	for _, owner := range []string{"alice", "bob"} {
		session, e := s.Sessions().Upsert(ctx, &store.Session{Tenant: tenant, Namespace: n.Name, SessionID: owner, AgentName: "shared"})
		if e != nil {
			t.Fatal(e)
		}
		_, e = s.Chats().Create(ctx, &controlmodel.Chat{ID: uuid.New(), Tenant: tenant, Namespace: n.Name, CreatorRef: owner, AgentID: uuid.New(), AgentName: "shared", SessionID: session.ID, RuntimeSession: session.SessionID, Title: owner, Status: controlmodel.ChatActive})
		if e != nil {
			t.Fatal(e)
		}
	}
	sessions, err := s.Sessions().List(reader, store.SessionFilter{Tenant: tenant, Namespace: n.Name, Limit: 1})
	if err != nil || len(sessions) != 1 || sessions[0].SessionID != "bob" {
		t.Fatalf("session filtering: %v %+v", err, sessions)
	}
}
