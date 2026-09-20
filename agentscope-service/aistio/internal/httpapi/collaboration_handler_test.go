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

	"github.com/google/uuid"

	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

func TestCreateIssueWithWorkflowStartsLatestPublishedRevision(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "owner"}
	definition, err := st.Orchestration().CreateDefinition(ctx, &controlmodel.OrchestrationDefinition{
		Tenant: "tenant-a", Namespace: "default", Name: "release",
		DraftSpec: json.RawMessage(`{"nodes":[{"key":"done","type":"condition"}]}`), CreatedBy: actor,
	})
	if err != nil {
		t.Fatal(err)
	}
	revision, err := (&orchestration.Service{Store: st}).Publish(ctx, definition.ID, actor)
	if err != nil {
		t.Fatal(err)
	}

	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	body, _ := json.Marshal(map[string]any{
		"tenant": "tenant-a", "namespace": "default", "title": "Ship release",
		"executionTargetType": "workflow", "executionTargetRef": definition.ID.String(),
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/issues", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer console")
	response := httptest.NewRecorder()
	server.router.ServeHTTP(response, req)
	if response.Code != http.StatusCreated {
		t.Fatalf("create Workflow Issue: %d %s", response.Code, response.Body.String())
	}
	var payload struct {
		Issue controlmodel.Issue            `json:"issue"`
		Run   controlmodel.OrchestrationRun `json:"orchestrationRun"`
	}
	if err = json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload.Issue.AssigneeType != "" || payload.Issue.AssigneeRef != "" {
		t.Fatalf("Workflow must not masquerade as an assignee: %+v", payload.Issue)
	}
	if payload.Issue.ExecutionTargetType != string(controlmodel.EndpointTargetOrchestrationRevision) ||
		payload.Issue.ExecutionTargetRef != revision.ID.String() {
		t.Fatalf("Issue did not freeze the latest Workflow revision: %+v", payload.Issue)
	}
	if payload.Run.ID == uuid.Nil || payload.Run.RootIssueID != payload.Issue.ID ||
		payload.Run.DefinitionRevisionID == nil || *payload.Run.DefinitionRevisionID != revision.ID {
		t.Fatalf("Workflow Run was not linked to the Issue and revision: %+v", payload.Run)
	}
}

func TestCreateIssueRejectsUnpublishedWorkflow(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	definition, err := st.Orchestration().CreateDefinition(ctx, &controlmodel.OrchestrationDefinition{
		Tenant: "tenant-a", Namespace: "default", Name: "draft",
		DraftSpec: json.RawMessage(`{"nodes":[{"key":"done","type":"condition"}]}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	body, _ := json.Marshal(map[string]any{
		"tenant": "tenant-a", "namespace": "default", "title": "Draft run",
		"executionTargetType": "workflow", "executionTargetRef": definition.ID.String(),
	})
	req := httptest.NewRequest(http.MethodPost, "/api/v1/issues", bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Authorization", "Bearer console")
	response := httptest.NewRecorder()
	server.router.ServeHTTP(response, req)
	if response.Code != http.StatusConflict {
		t.Fatalf("unpublished Workflow status=%d body=%s", response.Code, response.Body.String())
	}
}
