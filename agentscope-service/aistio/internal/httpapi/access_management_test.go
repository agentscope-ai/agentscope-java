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
	"fmt"
	"github.com/gin-gonic/gin"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/product"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"slices"
	"testing"
)

type testAccountDirectory struct{ unavailable bool }

func (d *testAccountDirectory) LookupAccounts(_ context.Context, q string, ids []string, limit int) ([]product.AccountSummary, error) {
	if d.unavailable {
		return nil, fmt.Errorf("offline")
	}
	items := []product.AccountSummary{}
	for _, id := range []string{"alice", "bob", "carol", "platform", "disabled"} {
		if len(ids) > 0 && !slices.Contains(ids, id) {
			continue
		}
		if q != "" && q != id {
			continue
		}
		items = append(items, product.AccountSummary{UserID: id, Username: id, Disabled: id == "disabled"})
	}
	return items, nil
}
func managementTestServer(t *testing.T) (*Server, store.Store, *testAccountDirectory) {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { st.Close() })
	_, err = st.Access().PutNamespace(ctx, &model.Namespace{Tenant: "default", Name: "engineering", DisplayName: "Engineering", Kind: "shared", Owner: "alice", Members: map[string][]string{"bob": {"member"}, "carol": {"admin"}}}, 0, "alice")
	if err != nil {
		t.Fatal(err)
	}
	dir := &testAccountDirectory{}
	s := NewServer(ServerOptions{Store: st, AccountDirectory: dir})
	s.router = gin.New()
	s.router.ContextWithFallback = true
	s.router.Use(func(c *gin.Context) {
		id := c.GetHeader("X-Test-User")
		c.Set("userId", id)
		c.Set("username", id)
		roles := []string{"user"}
		if id == "platform" {
			roles = append(roles, "admin")
		}
		c.Set("groups", roles)
		c.Set(ctxConsoleAuth, true)
		c.Next()
	})
	s.registerRoutes()
	return s, st, dir
}
func TestAccessManagementDirectoryAndGrants(t *testing.T) {
	s, st, dir := managementTestServer(t)
	for _, tc := range []struct {
		user, path string
		status     int
	}{{"bob", "/api/v1/access/accounts", 403}, {"bob", "/api/v1/namespaces/engineering/accounts", 404}, {"alice", "/api/v1/namespaces/engineering/accounts?q=bob", 200}, {"platform", "/api/v1/access/accounts", 200}, {"alice", "/api/v1/access/users/bob/namespaces", 403}, {"platform", "/api/v1/access/users/bob/namespaces", 200}, {"bob", "/api/v1/namespaces/engineering/audit", 404}, {"platform", "/api/v1/access/audit", 200}} {
		w := accessRequest(s, tc.user, "GET", tc.path, "")
		if w.Code != tc.status {
			t.Errorf("%s %s: %d %s", tc.user, tc.path, w.Code, w.Body.String())
		}
	}
	for _, id := range []string{"missing", "disabled"} {
		w := accessRequest(s, "alice", "PUT", "/api/v1/namespaces/engineering", `{"version":1,"members":{"`+id+`":["member"]}}`)
		if w.Code != 400 {
			t.Fatalf("invalid account accepted: %d %s", w.Code, w.Body.String())
		}
	}
	dir.unavailable = true
	if w := accessRequest(s, "alice", "PUT", "/api/v1/namespaces/engineering", `{"version":1,"members":{"bob":["developer"]}}`); w.Code != 400 {
		t.Fatalf("directory failure did not fail closed: %d", w.Code)
	}
	dir.unavailable = false
	w := accessRequest(s, "alice", "PUT", "/api/v1/namespaces/engineering", `{"version":1,"members":{"bob":["developer"]}}`)
	if w.Code != 200 {
		t.Fatalf("grant: %d %s", w.Code, w.Body.String())
	}
	if w = accessRequest(s, "alice", "PUT", "/api/v1/namespaces/engineering", `{"version":1,"members":{}}`); w.Code != 409 {
		t.Fatalf("stale update: %d", w.Code)
	}
	n, _ := st.Access().GetNamespace(context.Background(), "default", "engineering")
	if !slices.Contains(n.Roles("bob"), "developer") {
		t.Fatal("grant not persisted")
	}
	audit, _ := st.Access().ListNamespaceAudit(context.Background(), "default", "engineering", 50, 0)
	if len(audit) != 2 || audit[0].Actor != "alice" || audit[0].Version != 2 {
		t.Fatalf("audit: %+v", audit)
	}
}

func TestNamespaceProvisionTransferAndArchive(t *testing.T) {
	s, st, _ := managementTestServer(t)
	if w := accessRequest(s, "platform", "PUT", "/api/v1/namespaces/default", `{"displayName":"Changed","version":1}`); w.Code != 409 {
		t.Fatalf("global namespace mutation: %d %s", w.Code, w.Body.String())
	}
	body := `{"name":"support","displayName":"Support","owner":"bob","members":{"carol":["member"],"platform":["auditor"]}}`
	if w := accessRequest(s, "alice", "POST", "/api/v1/namespaces", body); w.Code != 403 {
		t.Fatal(w.Code)
	}
	w := accessRequest(s, "platform", "POST", "/api/v1/namespaces", body)
	if w.Code != 201 {
		t.Fatalf("create %d %s", w.Code, w.Body.String())
	}
	n, _ := st.Access().GetNamespace(context.Background(), "default", "support")
	if n.Owner != "bob" || !slices.Contains(n.Roles("platform"), "admin") || !slices.Contains(n.Roles("platform"), "auditor") {
		t.Fatal("owner/provisioner access missing")
	}
	if w = accessRequest(s, "carol", "POST", "/api/v1/namespaces/engineering/transfer", `{"owner":"bob","version":1}`); w.Code != 403 {
		t.Fatalf("nonowner transfer: %d %s", w.Code, w.Body.String())
	}
	w = accessRequest(s, "alice", "POST", "/api/v1/namespaces/engineering/transfer", `{"owner":"bob","version":1}`)
	if w.Code != 200 {
		t.Fatalf("transfer: %d %s", w.Code, w.Body.String())
	}
	n, _ = st.Access().GetNamespace(context.Background(), "default", "engineering")
	if n.Owner != "bob" || !slices.Contains(n.Roles("alice"), "admin") {
		t.Fatal("transfer lost previous owner access")
	}
	w = accessRequest(s, "alice", "PUT", "/api/v1/namespaces/engineering", `{"archived":true,"version":2}`)
	if w.Code != 403 {
		t.Fatalf("nonowner archive: %d", w.Code)
	}
	w = accessRequest(s, "bob", "PUT", "/api/v1/namespaces/engineering", `{"archived":true,"version":2}`)
	if w.Code != 200 {
		t.Fatalf("archive: %d %s", w.Code, w.Body.String())
	}
	n, _ = st.Access().GetNamespace(context.Background(), "default", "engineering")
	if len(n.Roles("bob")) > 0 || len(n.Roles("alice")) > 0 {
		t.Fatal("archived grants still effective")
	}
	w = accessRequest(s, "bob", "PUT", "/api/v1/namespaces/engineering", `{"archived":false,"version":3}`)
	if w.Code != 200 {
		t.Fatalf("restore %d %s", w.Code, w.Body.String())
	}
	if w = accessRequest(s, "alice", "GET", "/api/v1/namespaces/engineering/audit", ""); w.Code != 200 {
		t.Fatal(w.Code)
	}
	var out struct {
		Items []model.NamespaceAudit `json:"items"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil || len(out.Items) != 4 {
		t.Fatalf("audit: %s", w.Body.String())
	}
}

func TestManagementInventoryIncludesLaterPages(t *testing.T) {
	s, st, _ := managementTestServer(t)
	for i := 0; i < 501; i++ {
		_, err := st.Access().PutNamespace(t.Context(), &model.Namespace{Tenant: "default", Name: fmt.Sprintf("space-%03d", i), DisplayName: "Space", Kind: "shared", Owner: "alice"}, 0, "alice")
		if err != nil {
			t.Fatal(err)
		}
	}
	for _, path := range []string{"/api/v1/namespaces", "/api/v1/access/users/alice/namespaces"} {
		w := accessRequest(s, "platform", "GET", path, "")
		var out struct {
			Items []map[string]any `json:"items"`
		}
		want := 503 // 501 generated namespaces, engineering and global default.
		if path == "/api/v1/namespaces" {
			want++
		} // Caller also gets a personal namespace.
		if err := json.Unmarshal(w.Body.Bytes(), &out); err != nil || w.Code != 200 || len(out.Items) != want {
			t.Fatalf("inventory lost page: %d %d %v", w.Code, len(out.Items), err)
		}
	}
}
