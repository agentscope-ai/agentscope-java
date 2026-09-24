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
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/gin-gonic/gin"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestInboxHTTPViewOwnershipAndReading(t *testing.T) {
	gin.SetMode(gin.TestMode)
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	s := NewServer(ServerOptions{Store: st})
	approval, err := st.Collaboration().CreateApproval(ctx, &controlmodel.Approval{Tenant: "t", Namespace: "n", TargetType: "issue", TargetRef: "work", ApproverRef: "alice", RequestedBy: controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: "agent"}})
	if err != nil {
		t.Fatal(err)
	}
	items, err := st.Collaboration().ListInbox(ctx, store.InboxFilter{Tenant: "t", Namespace: "n", RecipientRef: "alice"})
	if err != nil || len(items) != 1 {
		t.Fatal("missing approval inbox")
	}
	router := gin.New()
	router.Use(func(c *gin.Context) { c.Set("username", c.GetHeader("X-Test-User")); c.Next() })
	router.GET("/inbox", s.listInbox)
	router.GET("/inbox/summary", s.inboxSummary)
	router.GET("/inbox/:inboxId", s.getInbox)
	router.POST("/inbox/:inboxId/read", s.readInbox)
	router.POST("/inbox/:inboxId/archive", s.archiveInbox)
	request := func(method, path, user string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, nil)
		req.Header.Set("X-Test-User", user)
		w := httptest.NewRecorder()
		router.ServeHTTP(w, req)
		return w
	}
	path := "/inbox/" + items[0].ID.String()
	for _, tc := range []struct {
		method, path, user string
		status             int
	}{
		{"GET", "/inbox", "alice", 400}, {"GET", "/inbox?tenant=t&namespace=n&view=bad", "alice", 400},
		{"GET", "/inbox?tenant=t&namespace=n&cursor=bad", "alice", 400},
		{"GET", path, "bob", 404}, {"POST", path + "/read?tenant=t&namespace=other", "alice", 404},
		{"POST", path + "/archive", "alice", 409}, {"POST", path + "/read", "alice", 200},
		{"GET", "/inbox?tenant=t&namespace=n&view=attention", "alice", 200},
	} {
		if w := request(tc.method, tc.path, tc.user); w.Code != tc.status {
			t.Fatalf("%s %s=%d: %s", tc.method, tc.path, w.Code, w.Body.String())
		}
	}
	w := request("GET", "/inbox/summary?tenant=t&namespace=n", "alice")
	var response struct {
		Summary controlmodel.InboxSummary `json:"summary"`
	}
	if err = json.Unmarshal(w.Body.Bytes(), &response); err != nil || response.Summary.Unread != 0 || response.Summary.PendingApprovals != 1 || response.Summary.AttentionTotal != 1 {
		t.Fatalf("summary=%s %v", w.Body.String(), err)
	}
	if _, err = st.Collaboration().DecideApproval(ctx, approval.ID, approval.Version, controlmodel.ApprovalCancelled, controlmodel.Actor{Type: controlmodel.ActorSystem}, nil); err != nil {
		t.Fatal(err)
	}
	w = request(http.MethodGet, path, "alice")
	if w.Code != 200 {
		t.Fatalf("archived deep link=%s", w.Body.String())
	}
}
