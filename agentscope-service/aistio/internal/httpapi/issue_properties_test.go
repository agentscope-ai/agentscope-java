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
	"bytes"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
	"time"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

func TestIssuePropertiesPatch(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "t", Namespace: "n", Title: "properties", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}})
	if err != nil {
		t.Fatal(err)
	}
	patch := func(body map[string]any, status int) *controlmodel.Issue {
		t.Helper()
		body["expectedVersion"] = issue.Version
		data, _ := json.Marshal(body)
		req := httptest.NewRequest(http.MethodPatch, "/api/v1/issues/"+issue.ID.String(), bytes.NewReader(data))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer console")
		recorder := httptest.NewRecorder()
		server.router.ServeHTTP(recorder, req)
		if recorder.Code != status {
			t.Fatalf("PATCH %s: %d %s", data, recorder.Code, recorder.Body.String())
		}
		current, loadErr := st.Collaboration().GetIssue(ctx, issue.ID)
		if loadErr != nil {
			t.Fatal(loadErr)
		}
		if status >= 400 && current.Version != issue.Version {
			t.Fatal("invalid patch changed the issue")
		}
		return current
	}
	due := time.Date(2026, 9, 10, 9, 30, 0, 0, time.UTC)
	issue = patch(map[string]any{"dueAt": due.Format(time.RFC3339)}, http.StatusOK)
	if issue.DueAt == nil || !issue.DueAt.Equal(due) {
		t.Fatal("deadline not saved")
	}
	issue = patch(map[string]any{"priority": "high"}, http.StatusOK)
	if issue.DueAt == nil || !issue.DueAt.Equal(due) {
		t.Fatal("omitted deadline was overwritten")
	}
	issue = patch(map[string]any{"dueAt": "not a date"}, http.StatusBadRequest)
	issue = patch(map[string]any{"dueAt": nil}, http.StatusOK)
	if issue.DueAt != nil {
		t.Fatal("explicit null did not clear the deadline")
	}
	for _, criteria := range []any{"string", map[string]any{"checklist": "bad"}, map[string]any{"minimumArtifacts": -1}, map[string]any{"checklist": []any{nil}}} {
		issue = patch(map[string]any{"acceptanceCriteria": criteria}, http.StatusBadRequest)
	}
	criteria := map[string]any{"checklist": []any{map[string]any{"text": "tested", "satisfied": true}}, "custom": "preserved"}
	issue = patch(map[string]any{"acceptanceCriteria": criteria}, http.StatusOK)
	if !bytes.Contains(issue.AcceptanceCriteria, []byte(`"custom":"preserved"`)) {
		t.Fatal("lost extension data")
	}
}

func TestIssueAssigneeClearAndValidation(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	issue, err := st.Collaboration().CreateIssue(ctx, &controlmodel.Issue{Tenant: "t", Namespace: "n", Title: "assign", AssigneeType: controlmodel.AssigneeHuman, AssigneeRef: "owner", Creator: controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}})
	if err != nil {
		t.Fatal(err)
	}
	for _, test := range []struct {
		body   string
		status int
	}{
		{`{}`, 400},
		{`{"assigneeType":"human","assigneeRef":""}`, 400},
		{`{"assigneeType":"invalid","assigneeRef":"owner"}`, 400},
		{`{"assigneeType":"","assigneeRef":""}`, 200},
	} {
		req := httptest.NewRequest(http.MethodPost, "/api/v1/issues/"+issue.ID.String()+"/assign", bytes.NewBufferString(test.body))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer console")
		recorder := httptest.NewRecorder()
		server.router.ServeHTTP(recorder, req)
		if recorder.Code != test.status {
			t.Fatalf("assign %s: %d %s", test.body, recorder.Code, recorder.Body.String())
		}
	}
	current, _ := st.Collaboration().GetIssue(ctx, issue.ID)
	if current.AssigneeRef != "" || current.AssigneeType != "" {
		t.Fatal("assignee not cleared")
	}
	tasks, _ := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: issue.ID})
	if len(tasks) != 0 {
		t.Fatal("clearing a human assignee started work")
	}
}
