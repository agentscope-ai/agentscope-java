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

package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"github.com/google/uuid"
	"github.com/spring-ai-alibaba/aistio/internal/automation"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func automationHTTPFixture(t *testing.T) (*Server, store.Store, *controlmodel.Automation, string) {
	t.Helper()
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = st.Close() })
	a, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "acme", Namespace: "default", AgentKey: "researcher", DisplayName: "Researcher", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	body := map[string]any{"tenant": "acme", "namespace": "default", "name": "Daily report", "description": "Preserve me", "execution": map[string]any{"runbook": "Inspect project activity", "assigneeType": "agent", "assigneeRef": a.ID, "outputMode": "run_only"}, "triggers": []map[string]any{{"id": uuid.New(), "type": "cron", "enabled": true, "schedule": "0 9 * * 1-5", "timezone": "Asia/Shanghai"}, {"id": uuid.New(), "type": "webhook", "enabled": true, "events": []string{"build.completed"}}}}
	response := automationRequestTest(server, "POST", "/api/v1/automations", body, "", true)
	if response.Code != 201 {
		t.Fatalf("create: %d %s", response.Code, response.Body.String())
	}
	var payload struct {
		Automation *controlmodel.Automation `json:"automation"`
		Secret     string                   `json:"webhookSecret"`
	}
	if err = json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if len(payload.Secret) < 32 {
		t.Fatal("missing generated credential")
	}
	return server, st, payload.Automation, payload.Secret
}
func automationRequestTest(server *Server, method, path string, body any, key string, auth bool) *httptest.ResponseRecorder {
	var b []byte
	if body != nil {
		b, _ = json.Marshal(body)
	}
	req := httptest.NewRequest(method, path, bytes.NewReader(b))
	req.Header.Set("Content-Type", "application/json")
	if auth {
		req.Header.Set("Authorization", "Bearer console")
	}
	if key != "" {
		req.Header.Set("Idempotency-Key", key)
	}
	rec := httptest.NewRecorder()
	server.router.ServeHTTP(rec, req)
	return rec
}
func TestAutomationHTTPPatchPreservesAndResumesSchedule(t *testing.T) {
	s, st, rule, _ := automationHTTPFixture(t)
	path := "/api/v1/automations/" + rule.ID.String()
	response := automationRequestTest(s, "PATCH", path, map[string]any{"enabled": false, "expectedVersion": rule.Version}, "", true)
	if response.Code != 200 {
		t.Fatalf("pause: %d %s", response.Code, response.Body.String())
	}
	paused, _ := st.Collaboration().GetAutomation(context.Background(), rule.ID)
	if paused.Enabled || paused.NextRunAt != nil || paused.Description != rule.Description || paused.Execution.Runbook != rule.Execution.Runbook {
		t.Fatalf("patch lost data: %+v", paused)
	}
	response = automationRequestTest(s, "PATCH", path, map[string]any{"enabled": true, "expectedVersion": paused.Version}, "", true)
	if response.Code != 200 {
		t.Fatalf("resume: %d %s", response.Code, response.Body.String())
	}
	resumed, _ := st.Collaboration().GetAutomation(context.Background(), rule.ID)
	if !resumed.Enabled || resumed.NextRunAt == nil || !resumed.NextRunAt.After(time.Now()) {
		t.Fatalf("not rescheduled: %+v", resumed)
	}
	response = automationRequestTest(s, "PATCH", path, map[string]any{"description": "", "expectedVersion": resumed.Version}, "", true)
	if response.Code != 200 {
		t.Fatal(response.Body.String())
	}
	cleared, _ := st.Collaboration().GetAutomation(context.Background(), rule.ID)
	if cleared.Description != "" {
		t.Fatal("explicit description clear ignored")
	}
	response = automationRequestTest(s, "PATCH", path, map[string]any{"name": "Stale edit", "expectedVersion": rule.Version}, "", true)
	if response.Code != 409 {
		t.Fatalf("stale edit: %d", response.Code)
	}
	response = automationRequestTest(s, "PATCH", path, map[string]any{"triggers": []map[string]any{{"type": "cron", "enabled": true, "schedule": "garbage", "timezone": "UTC"}}, "expectedVersion": cleared.Version}, "", true)
	if response.Code != 400 {
		t.Fatalf("invalid schedule accepted: %d %s", response.Code, response.Body.String())
	}
}
func TestAutomationHTTPManualDoesNotNeedWebhookCredential(t *testing.T) {
	s, _, rule, _ := automationHTTPFixture(t)
	path := "/api/v1/automations/" + rule.ID.String() + "/trigger"
	missing := automationRequestTest(s, "POST", path, nil, "", true)
	if missing.Code != 400 {
		t.Fatalf("missing idempotency: %d", missing.Code)
	}
	first := automationRequestTest(s, "POST", path, nil, "click-1", true)
	if first.Code != 202 {
		t.Fatalf("manual trigger: %d %s", first.Code, first.Body.String())
	}
	var p struct {
		Run controlmodel.AutomationRun `json:"run"`
	}
	_ = json.Unmarshal(first.Body.Bytes(), &p)
	if p.Run.Status != controlmodel.AutomationRunQueued || p.Run.Source != "manual" {
		t.Fatalf("unexpected acceptance: %+v", p.Run)
	}
	duplicate := automationRequestTest(s, "POST", path, nil, "click-1", true)
	var d struct {
		Run controlmodel.AutomationRun `json:"run"`
	}
	_ = json.Unmarshal(duplicate.Body.Bytes(), &d)
	if duplicate.Code != 202 || d.Run.ID != p.Run.ID {
		t.Fatal("click retry created a second run")
	}
	if strings.Contains(first.Body.String(), "SecretHash") || strings.Contains(first.Body.String(), "leaseToken") {
		t.Fatal("internal credential leaked")
	}
}
func webhookRequest(s *Server, rule *controlmodel.Automation, secret, key, event string) *httptest.ResponseRecorder {
	body, _ := json.Marshal(map[string]any{"event": event, "build": 42})
	req := httptest.NewRequest("POST", "/hooks/v1/automations/"+rule.ID.String()+"/"+rule.Triggers[1].ID.String(), bytes.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("X-Automation-Secret", secret)
	req.Header.Set("Idempotency-Key", key)
	rec := httptest.NewRecorder()
	s.router.ServeHTTP(rec, req)
	return rec
}
func TestAutomationWebhookAuthFilterDeduplicationAndRotation(t *testing.T) {
	s, st, rule, secret := automationHTTPFixture(t)
	if r := webhookRequest(s, rule, "invalid", "bad", "build.completed"); r.Code != 401 {
		t.Fatal(r.Code, r.Body.String())
	}
	ignored := webhookRequest(s, rule, secret, "ignore", "unrelated")
	if ignored.Code != 200 || !strings.Contains(ignored.Body.String(), "event_filtered") {
		t.Fatal(ignored.Code, ignored.Body.String())
	}
	first := webhookRequest(s, rule, secret, "event-1", "build.completed")
	if first.Code != 200 {
		t.Fatal(first.Code, first.Body.String())
	}
	duplicate := webhookRequest(s, rule, secret, "event-1", "build.completed")
	if duplicate.Code != 200 || !strings.Contains(duplicate.Body.String(), "duplicate") {
		t.Fatal(duplicate.Body.String())
	}
	conflict := webhookRequest(s, rule, secret, "event-1", "different")
	if conflict.Code != 409 {
		t.Fatal(conflict.Code, conflict.Body.String())
	}
	runs, _ := st.Collaboration().ListAutomationRuns(context.Background(), rule.ID, 100, 0)
	if len(runs) != 1 {
		t.Fatalf("expected one run, got %d", len(runs))
	}
	rotation := automationRequestTest(s, "POST", "/api/v1/automations/"+rule.ID.String()+"/rotate-secret", map[string]any{"expectedVersion": rule.Version}, "", true)
	if rotation.Code != 200 {
		t.Fatal(rotation.Code, rotation.Body.String())
	}
	if r := webhookRequest(s, rule, secret, "old-secret", "build.completed"); r.Code != 401 {
		t.Fatal("old credential remained valid")
	}
	deliveries, _ := st.Collaboration().ListAutomationDeliveries(context.Background(), rule.ID, 100, 0)
	if len(deliveries) != 2 {
		t.Fatalf("expected two authenticated deliveries: %d", len(deliveries))
	}
}
func TestAutomationHTTPRunCannotBeReadThroughAnotherRule(t *testing.T) {
	s, st, rule, _ := automationHTTPFixture(t)
	svc := &automation.Service{Store: st}
	run, err := svc.Trigger(context.Background(), rule.ID, "", "one", nil)
	if err != nil {
		t.Fatal(err)
	}
	other := *rule
	other.ID = uuid.New()
	other.Name = "Other"
	created, errValue := st.Collaboration().CreateAutomation(context.Background(), &other)
	if errValue != nil {
		t.Fatal(errValue)
	}
	response := automationRequestTest(s, http.MethodGet, "/api/v1/automations/"+created.ID.String()+"/runs/"+run.ID.String(), nil, "", true)
	if response.Code != 404 {
		t.Fatal(response.Code, response.Body.String())
	}
}
