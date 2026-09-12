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
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"

	"github.com/spring-ai-alibaba/aistio/internal/asdp"
	"github.com/spring-ai-alibaba/aistio/internal/collaboration"
	controlmodel "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/orchestration"
	"github.com/spring-ai-alibaba/aistio/internal/store"
	_ "github.com/spring-ai-alibaba/aistio/internal/store/memory"
)

type endpointCommandCapture struct {
	turns []*asdp.ConversationTurnCommand
}

func TestEndpointIdempotencyComparesJSONBValues(t *testing.T) {
	for _, tc := range []struct {
		a, b  string
		equal bool
	}{
		{`{"title":"job","input":{"quantity":12,"price":12}}`, `{"input": {"price": 12, "quantity": 12}, "title": "job"}`, true},
		{`{"items":[1,2]}`, `{"items":[2,1]}`, false},
		{`{"value":9007199254740992}`, `{"value":9007199254740993}`, false},
		{`{"value":1}`, `{"value":"1"}`, false},
	} {
		if got := sameJSON(json.RawMessage(tc.a), json.RawMessage(tc.b)); got != tc.equal {
			t.Errorf("sameJSON(%s, %s) = %v, want %v", tc.a, tc.b, got, tc.equal)
		}
	}
}

func (*endpointCommandCapture) SendSessionCommand(_, _, _, _, _, _ string) error { return nil }
func (c *endpointCommandCapture) SendConversationTurn(_, _, _ string, command *asdp.ConversationTurnCommand) error {
	c.turns = append(c.turns, command)
	return nil
}

func TestListEndpointsUsesEmptyArray(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()

	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	req := httptest.NewRequest(http.MethodGet, "/api/v1/endpoints?tenant=t&namespace=n", nil)
	req.Header.Set("Authorization", "Bearer console")
	w := httptest.NewRecorder()
	server.router.ServeHTTP(w, req)
	if w.Code != http.StatusOK {
		t.Fatalf("list endpoints: %d %s", w.Code, w.Body)
	}
	var payload struct {
		Items []json.RawMessage `json:"items"`
	}
	if err = json.Unmarshal(w.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload.Items == nil || len(payload.Items) != 0 {
		t.Fatalf("empty Endpoint collection must be an array, got %s", w.Body)
	}
}

func TestCreateEndpointReportsIdentityConflicts(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{
		Tenant: "t", Namespace: "n", AgentKey: "endpoint-owner", DisplayName: "Endpoint owner",
		Status: controlmodel.AgentActive,
	})
	if err != nil {
		t.Fatal(err)
	}
	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	create := func(name, slug string) *httptest.ResponseRecorder {
		body := fmt.Sprintf(`{"tenant":"t","namespace":"n","name":%q,"slug":%q,"targetType":"agent","targetRef":"%s","invocationMode":"job"}`,
			name, slug, agent.ID)
		req := httptest.NewRequest(http.MethodPost, "/api/v1/endpoints", bytes.NewBufferString(body))
		req.Header.Set("Authorization", "Bearer console")
		req.Header.Set("Content-Type", "application/json")
		out := httptest.NewRecorder()
		server.router.ServeHTTP(out, req)
		return out
	}
	if first := create("Owner API", "owner-api"); first.Code != http.StatusCreated {
		t.Fatalf("first Endpoint: %d %s", first.Code, first.Body)
	}
	assertConflict := func(out *httptest.ResponseRecorder, code string) {
		t.Helper()
		var payload ErrorResponse
		if out.Code != http.StatusConflict || json.Unmarshal(out.Body.Bytes(), &payload) != nil || payload.Code != code {
			t.Fatalf("conflict code=%q: HTTP %d %s", code, out.Code, out.Body)
		}
	}
	assertConflict(create("Owner API 2", "owner-api"), "endpoint_slug_conflict")
	assertConflict(create("Owner API", "owner-api-2"), "endpoint_name_conflict")
}

func TestEndpointJobIsIdempotent(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "t", Namespace: "n", AgentKey: "worker", DisplayName: "worker", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID, Tenant: "t", Namespace: "n", Kind: controlmodel.DataPlaneExternalApplication, Configuration: json.RawMessage(`{"instanceSelector":{}}`), Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.RuntimeRegistry().UpsertAgentInstance(ctx, &controlmodel.AgentInstance{
		Tenant: "t", Namespace: "n", AgentID: agent.ID, BindingID: binding.ID,
		BackendKind: controlmodel.DataPlaneExternalApplication, InstanceKey: "worker-1",
		Health: controlmodel.RuntimeHealthHealthy, Capacity: 1,
	}); err != nil {
		t.Fatal(err)
	}
	if _, err = st.Orchestration().PutRuntimePolicy(ctx, &controlmodel.AgentRuntimePolicy{
		Tenant: "t", Namespace: "n", AgentRef: agent.ID.String(), SelectionMode: "ordered", FallbackMode: "disabled",
		Candidates: []controlmodel.RuntimeBindingCandidate{{Binding: controlmodel.RuntimeBinding{
			BindingID: binding.ID, Kind: binding.Kind, InstanceSelector: map[string]string{},
		}}},
	}); err != nil {
		t.Fatal(err)
	}
	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	request := func(method, path, body, token string, idem bool) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
			req.Header.Set("X-API-Key", token)
		}
		if idem {
			req.Header.Set("Idempotency-Key", "same-job")
		}
		w := httptest.NewRecorder()
		server.router.ServeHTTP(w, req)
		return w
	}
	created := request(http.MethodPost, "/api/v1/endpoints", `{"tenant":"t","namespace":"n","name":"worker jobs","slug":"worker-jobs","targetType":"agent","targetRef":"`+agent.ID.String()+`","invocationMode":"job"}`, "console", false)
	if created.Code != http.StatusCreated {
		t.Fatalf("create endpoint: %d %s", created.Code, created.Body.String())
	}
	var out struct {
		Credential         string `json:"credential"`
		CredentialResource struct {
			ID uuid.UUID `json:"id"`
		} `json:"credentialResource"`
		Endpoint struct {
			ID      uuid.UUID `json:"id"`
			Version int64     `json:"version"`
		} `json:"endpoint"`
	}
	if err = json.Unmarshal(created.Body.Bytes(), &out); err != nil {
		t.Fatal(err)
	}
	published := request(http.MethodPost, "/api/v1/endpoints/"+out.Endpoint.ID.String()+"/publish", fmt.Sprintf(`{"version":%d}`, out.Endpoint.Version), "console", false)
	if published.Code != http.StatusOK {
		t.Fatalf("publish endpoint: %d %s", published.Code, published.Body.String())
	}
	first := request(http.MethodPost, "/invoke/v1/endpoints/worker-jobs/jobs", `{"title":"do it","input":{"x":1}}`, out.Credential, true)
	second := request(http.MethodPost, "/invoke/v1/endpoints/worker-jobs/jobs", `{"title":"do it","input":{"x":1}}`, out.Credential, true)
	conflict := request(http.MethodPost, "/invoke/v1/endpoints/worker-jobs/jobs", `{"title":"different"}`, out.Credential, true)
	if first.Code != http.StatusAccepted || second.Code != http.StatusAccepted {
		t.Fatalf("invoke statuses: %d %s / %d %s", first.Code, first.Body, second.Code, second.Body)
	}
	if conflict.Code != http.StatusConflict {
		t.Fatalf("changed idempotent job input was accepted: %d %s", conflict.Code, conflict.Body)
	}
	var a, b struct {
		InvocationID uuid.UUID `json:"invocationId"`
		IssueID      uuid.UUID `json:"issueId"`
		RunID        uuid.UUID `json:"runId"`
	}
	_ = json.Unmarshal(first.Body.Bytes(), &a)
	_ = json.Unmarshal(second.Body.Bytes(), &b)
	if a.InvocationID == uuid.Nil || a.InvocationID != b.InvocationID || a.IssueID == uuid.Nil || a.IssueID != b.IssueID || a.RunID != b.RunID {
		t.Fatalf("idempotency diverged: %+v %+v", a, b)
	}
	issue, err := st.Collaboration().GetIssue(ctx, a.IssueID)
	if err != nil || issue.AssigneeType != controlmodel.AssigneeAgent || issue.AssigneeRef != agent.ID.String() {
		t.Fatalf("Endpoint Agent target was not persisted as Issue assignee: issue=%+v err=%v", issue, err)
	}
	if issue.ExecutionTargetType != string(controlmodel.EndpointTargetAgent) || issue.ExecutionTargetRef != agent.ID.String() {
		t.Fatalf("Endpoint execution target was not persisted: issue=%+v", issue)
	}
	tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{IssueID: a.IssueID})
	if err != nil || len(tasks) != 1 || tasks[0].AgentRef != agent.ID.String() {
		t.Fatalf("expected one stable Agent task: %+v err=%v", tasks, err)
	}
	taskContext, err := (&collaboration.Service{Store: st}).BuildContext(ctx, tasks[0].ID)
	if err != nil || taskContext.Run == nil || string(taskContext.Run.Input) != `{"x":1}` || !strings.Contains(taskContext.CurrentRequest, `"x": 1`) || !strings.Contains(issue.Description, `"x": 1`) {
		t.Fatalf("Endpoint input was not propagated to AgentTask context: context=%+v err=%v", taskContext, err)
	}
	claimed, _, err := st.Collaboration().ClaimAgentTaskWithAttempt(ctx,
		store.TaskClaim{TaskID: tasks[0].ID, ExpectedVersion: tasks[0].Version},
		&controlmodel.ExecutionAttempt{BackendKind: controlmodel.DataPlaneExternalApplication,
			State: controlmodel.ExecutionAssigned, DispatchGeneration: 1})
	if err != nil {
		t.Fatal(err)
	}
	running, err := st.Collaboration().StartAgentTask(ctx, claimed.ID, claimed.Version)
	if err != nil {
		current, _ := st.Collaboration().GetAgentTask(ctx, tasks[0].ID)
		t.Fatalf("start Endpoint task: task=%+v err=%v", current, err)
	}
	if _, _, err = (&collaboration.Service{Store: st}).CompleteTask(ctx, running.ID,
		store.TaskCompletion{ExpectedVersion: running.Version, Summary: "work completed"},
		controlmodel.Actor{Type: controlmodel.ActorAgent, Ref: agent.ID.String()}); err != nil {
		t.Fatal(err)
	}
	reviewIssue, err := st.Collaboration().GetIssue(ctx, a.IssueID)
	if err != nil || reviewIssue.Status != controlmodel.IssueDone ||
		reviewIssue.Kind != controlmodel.IssueKindEndpointJob ||
		reviewIssue.Visibility != controlmodel.IssueVisibilityOperational {
		t.Fatalf("completed Endpoint Agent job should close its operational Issue: issue=%+v err=%v", reviewIssue, err)
	}
	var publishedOut struct {
		Endpoint struct {
			Version         int64      `json:"version"`
			ActiveReleaseID *uuid.UUID `json:"activeReleaseId"`
			ActiveRelease   int        `json:"activeRelease"`
		} `json:"endpoint"`
	}
	_ = json.Unmarshal(published.Body.Bytes(), &publishedOut)
	if publishedOut.Endpoint.ActiveReleaseID == nil || publishedOut.Endpoint.ActiveRelease != 1 {
		t.Fatalf("initial publication did not create release 1: %s", published.Body)
	}
	releaseHistory := request(http.MethodGet, "/api/v1/endpoints/"+out.Endpoint.ID.String()+"/releases", "", "console", false)
	if releaseHistory.Code != http.StatusOK {
		t.Fatalf("list endpoint releases: %d %s", releaseHistory.Code, releaseHistory.Body)
	}
	var history struct {
		Items []controlmodel.EndpointRelease `json:"items"`
	}
	if err = json.Unmarshal(releaseHistory.Body.Bytes(), &history); err != nil || len(history.Items) != 1 || history.Items[0].Number != 1 {
		t.Fatalf("unexpected release history: %+v err=%v", history.Items, err)
	}
	revealed := request(http.MethodPost, "/api/v1/endpoints/"+out.Endpoint.ID.String()+"/credentials/"+out.CredentialResource.ID.String()+"/reveal", `{}`, "console", false)
	if revealed.Code != http.StatusOK || revealed.Header().Get("Cache-Control") != "no-store" {
		t.Fatalf("reveal credential: %d headers=%v body=%s", revealed.Code, revealed.Header(), revealed.Body)
	}
	var revealedOut struct {
		Secret string `json:"secret"`
	}
	if err = json.Unmarshal(revealed.Body.Bytes(), &revealedOut); err != nil || revealedOut.Secret != out.Credential {
		t.Fatalf("revealed credential mismatch: got=%q want=%q err=%v", revealedOut.Secret, out.Credential, err)
	}
	revealedAgain := request(http.MethodPost, "/api/v1/endpoints/"+out.Endpoint.ID.String()+"/credentials/"+out.CredentialResource.ID.String()+"/reveal", `{}`, "console", false)
	var revealedAgainOut struct {
		Secret string `json:"secret"`
	}
	if revealedAgain.Code != http.StatusOK || json.Unmarshal(revealedAgain.Body.Bytes(), &revealedAgainOut) != nil ||
		revealedAgainOut.Secret != out.Credential {
		t.Fatalf("credential was not repeatably revealable: %d %s", revealedAgain.Code, revealedAgain.Body)
	}
	rotated := request(http.MethodPost, "/api/v1/endpoints/"+out.Endpoint.ID.String()+"/credentials/"+out.CredentialResource.ID.String()+"/rotate", `{}`, "console", false)
	if rotated.Code != http.StatusCreated {
		t.Fatalf("rotate credential: %d %s", rotated.Code, rotated.Body)
	}
	var rotatedOut struct {
		Secret string `json:"secret"`
	}
	_ = json.Unmarshal(rotated.Body.Bytes(), &rotatedOut)
	disabled := request(http.MethodPost, "/api/v1/endpoints/"+out.Endpoint.ID.String()+"/disable", fmt.Sprintf(`{"version":%d}`, publishedOut.Endpoint.Version), "console", false)
	if disabled.Code != http.StatusOK {
		t.Fatalf("disable endpoint: %d %s", disabled.Code, disabled.Body)
	}
	newCall := request(http.MethodPost, "/invoke/v1/endpoints/worker-jobs/jobs", `{"title":"blocked"}`, rotatedOut.Secret, true)
	if newCall.Code != http.StatusNotFound {
		t.Fatalf("disabled endpoint accepted new call: %d %s", newCall.Code, newCall.Body)
	}
	status := request(http.MethodGet, "/invoke/v1/jobs/"+a.InvocationID.String(), "", rotatedOut.Secret, false)
	if status.Code != http.StatusOK {
		t.Fatalf("rotated credential could not read existing job after disable: %d %s", status.Code, status.Body)
	}
}

func TestWorkflowEndpointRecordsTargetAndRequestsReview(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	actor := controlmodel.Actor{Type: controlmodel.ActorHuman, Ref: "developer"}
	definition, err := st.Orchestration().CreateDefinition(ctx, &controlmodel.OrchestrationDefinition{
		Tenant: "t", Namespace: "n", Name: "instant", CreatedBy: actor,
		DraftSpec: json.RawMessage(`{"nodes":[{"key":"done","type":"condition"}]}`),
	})
	if err != nil {
		t.Fatal(err)
	}
	revision, err := (&orchestration.Service{Store: st}).Publish(ctx, definition.ID, actor)
	if err != nil {
		t.Fatal(err)
	}
	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	request := func(method, path, body, token, idem string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
			req.Header.Set("X-API-Key", token)
		}
		if idem != "" {
			req.Header.Set("Idempotency-Key", idem)
		}
		w := httptest.NewRecorder()
		server.router.ServeHTTP(w, req)
		return w
	}
	created := request(http.MethodPost, "/api/v1/endpoints", fmt.Sprintf(
		`{"tenant":"t","namespace":"n","name":"instant workflow","slug":"instant-workflow","targetType":"orchestration_revision","targetRef":"%s","invocationMode":"job"}`,
		revision.ID), "console", "")
	if created.Code != http.StatusCreated {
		t.Fatalf("create endpoint: %d %s", created.Code, created.Body)
	}
	var resource struct {
		Credential string `json:"credential"`
		Endpoint   struct {
			ID      uuid.UUID `json:"id"`
			Version int64     `json:"version"`
		} `json:"endpoint"`
	}
	if err = json.Unmarshal(created.Body.Bytes(), &resource); err != nil {
		t.Fatal(err)
	}
	published := request(http.MethodPost, "/api/v1/endpoints/"+resource.Endpoint.ID.String()+"/publish",
		fmt.Sprintf(`{"version":%d}`, resource.Endpoint.Version), "console", "")
	if published.Code != http.StatusOK {
		t.Fatalf("publish endpoint: %d %s", published.Code, published.Body)
	}
	invoked := request(http.MethodPost, "/invoke/v1/endpoints/instant-workflow/jobs",
		`{"title":"workflow test"}`, resource.Credential, "workflow-job-1")
	if invoked.Code != http.StatusAccepted {
		t.Fatalf("invoke endpoint: %d %s", invoked.Code, invoked.Body)
	}
	var job struct {
		IssueID uuid.UUID `json:"issueId"`
	}
	if err = json.Unmarshal(invoked.Body.Bytes(), &job); err != nil {
		t.Fatal(err)
	}
	issue, err := st.Collaboration().GetIssue(ctx, job.IssueID)
	if err != nil {
		t.Fatal(err)
	}
	if issue.AssigneeType != "" || issue.AssigneeRef != "" {
		t.Fatalf("Workflow definition must not masquerade as an accountable assignee: %+v", issue)
	}
	if issue.ExecutionTargetType != string(controlmodel.EndpointTargetOrchestrationRevision) ||
		issue.ExecutionTargetRef != revision.ID.String() {
		t.Fatalf("Workflow execution target was not persisted: %+v", issue)
	}
	if issue.Status != controlmodel.IssueDone {
		t.Fatalf("successful Workflow job should automatically complete, got %s", issue.Status)
	}
}

func TestEndpointConversationFreezesExternalRuntimeAndIsIdempotent(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	agent, err := st.AgentCatalog().CreateAgent(ctx, &controlmodel.Agent{Tenant: "t", Namespace: "n",
		AgentKey: "online", DisplayName: "Online", Status: controlmodel.AgentActive})
	if err != nil {
		t.Fatal(err)
	}
	binding, err := st.AgentCatalog().CreateBinding(ctx, &controlmodel.AgentBinding{AgentID: agent.ID,
		Tenant: "t", Namespace: "n", Kind: controlmodel.DataPlaneExternalApplication,
		Configuration: json.RawMessage(`{"instanceSelector":{}}`), Enabled: true})
	if err != nil {
		t.Fatal(err)
	}
	instance, err := st.RuntimeRegistry().UpsertAgentInstance(ctx, &controlmodel.AgentInstance{Tenant: "t", Namespace: "n",
		AgentID: agent.ID, BindingID: binding.ID, BackendKind: controlmodel.DataPlaneExternalApplication,
		InstanceKey: "paw-1", Health: controlmodel.RuntimeHealthHealthy, Capacity: 4,
		Capabilities: json.RawMessage(`["conversation-inbound"]`)})
	if err != nil {
		t.Fatal(err)
	}
	if _, err = st.Orchestration().PutRuntimePolicy(ctx, &controlmodel.AgentRuntimePolicy{Tenant: "t", Namespace: "n",
		AgentRef: agent.ID.String(), SelectionMode: "ordered", FallbackMode: "disabled",
		Candidates: []controlmodel.RuntimeBindingCandidate{{Binding: controlmodel.RuntimeBinding{
			BindingID: binding.ID, Kind: binding.Kind, InstanceSelector: map[string]string{},
		}}}}); err != nil {
		t.Fatal(err)
	}
	commands := &endpointCommandCapture{}
	server := NewServer(ServerOptions{Store: st, AuthToken: "console", ASDPCommands: commands})
	request := func(method, path, body, token, idem string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
			req.Header.Set("X-API-Key", token)
		}
		if idem != "" {
			req.Header.Set("Idempotency-Key", idem)
		}
		w := httptest.NewRecorder()
		server.router.ServeHTTP(w, req)
		return w
	}
	created := request(http.MethodPost, "/api/v1/endpoints", `{"tenant":"t","namespace":"n","name":"online chat","slug":"online-chat","targetType":"agent","targetRef":"`+agent.ID.String()+`","invocationMode":"conversation","inputSchema":{"type":"object","required":["message"],"properties":{"message":{"type":"string"}}}}`, "console", "")
	if created.Code != http.StatusCreated {
		t.Fatalf("create endpoint: %d %s", created.Code, created.Body)
	}
	var resource struct {
		Credential string `json:"credential"`
		Endpoint   struct {
			ID      uuid.UUID `json:"id"`
			Version int64     `json:"version"`
		} `json:"endpoint"`
	}
	_ = json.Unmarshal(created.Body.Bytes(), &resource)
	published := request(http.MethodPost, "/api/v1/endpoints/"+resource.Endpoint.ID.String()+"/publish",
		fmt.Sprintf(`{"version":%d}`, resource.Endpoint.Version), "console", "")
	if published.Code != http.StatusOK {
		t.Fatalf("publish endpoint: %d %s", published.Code, published.Body)
	}
	first := request(http.MethodPost, "/invoke/v1/endpoints/online-chat/conversations", `{"message":"hello"}`, resource.Credential, "turn-1")
	second := request(http.MethodPost, "/invoke/v1/endpoints/online-chat/conversations", `{"message":"hello"}`, resource.Credential, "turn-1")
	conflict := request(http.MethodPost, "/invoke/v1/endpoints/online-chat/conversations", `{"message":"different"}`, resource.Credential, "turn-1")
	if first.Code != http.StatusAccepted || second.Code != http.StatusAccepted {
		t.Fatalf("conversation statuses: %d %s / %d %s", first.Code, first.Body, second.Code, second.Body)
	}
	if conflict.Code != http.StatusConflict {
		t.Fatalf("changed idempotent turn input was accepted: %d %s", conflict.Code, conflict.Body)
	}
	if len(commands.turns) != 1 {
		t.Fatalf("expected one ASDP command, got %d", len(commands.turns))
	}
	command := commands.turns[0]
	if command.GetAgentId() != agent.ID.String() || command.GetBindingId() != binding.ID.String() ||
		command.GetInstanceId() != instance.ID.String() || command.GetGeneration() != instance.Generation {
		t.Fatalf("command did not freeze runtime identity: %+v", command)
	}
	var firstOut, secondOut struct {
		InvocationID   uuid.UUID `json:"invocationId"`
		ConversationID uuid.UUID `json:"conversationId"`
		SessionID      string    `json:"sessionId"`
		SessionRef     uuid.UUID `json:"sessionRef"`
	}
	_ = json.Unmarshal(first.Body.Bytes(), &firstOut)
	_ = json.Unmarshal(second.Body.Bytes(), &secondOut)
	if firstOut.InvocationID == uuid.Nil || firstOut.InvocationID != secondOut.InvocationID ||
		firstOut.ConversationID == uuid.Nil || firstOut.ConversationID != secondOut.ConversationID ||
		firstOut.SessionRef == uuid.Nil || firstOut.SessionRef != secondOut.SessionRef {
		t.Fatalf("turn idempotency diverged: %+v %+v", firstOut, secondOut)
	}
	sessions, err := st.Sessions().List(ctx, store.SessionFilter{Tenant: "t", Namespace: "n", AgentID: agent.ID,
		SessionID: firstOut.SessionID, Limit: 1})
	if err != nil || len(sessions) != 1 || sessions[0].OriginType != "endpoint" || sessions[0].OriginRef != resource.Endpoint.ID.String() {
		t.Fatalf("Endpoint Session identity was not stored: sessions=%+v err=%v", sessions, err)
	}
}

func TestHostedEndpointConversationCompletesPublicInvocation(t *testing.T) {
	ctx := context.Background()
	st, agent, _, host := setupHostedConversationAgent(t)
	server := NewServer(ServerOptions{Store: st, AuthToken: "console"})
	request := func(method, path, body, token, idem string) *httptest.ResponseRecorder {
		req := httptest.NewRequest(method, path, bytes.NewBufferString(body))
		req.Header.Set("Content-Type", "application/json")
		if token != "" {
			req.Header.Set("Authorization", "Bearer "+token)
			req.Header.Set("X-API-Key", token)
		}
		if idem != "" {
			req.Header.Set("Idempotency-Key", idem)
		}
		w := httptest.NewRecorder()
		server.router.ServeHTTP(w, req)
		return w
	}
	created := request(http.MethodPost, "/api/v1/endpoints",
		`{"tenant":"t","namespace":"n","name":"hosted chat","slug":"hosted-chat","targetType":"agent","targetRef":"`+
			agent.ID.String()+`","invocationMode":"conversation"}`, "console", "")
	if created.Code != http.StatusCreated {
		t.Fatalf("create hosted endpoint: %d %s", created.Code, created.Body)
	}
	var resource struct {
		Credential string `json:"credential"`
		Endpoint   struct {
			ID      uuid.UUID `json:"id"`
			Version int64     `json:"version"`
		} `json:"endpoint"`
	}
	if err := json.Unmarshal(created.Body.Bytes(), &resource); err != nil {
		t.Fatal(err)
	}
	published := request(http.MethodPost, "/api/v1/endpoints/"+resource.Endpoint.ID.String()+"/publish",
		fmt.Sprintf(`{"version":%d}`, resource.Endpoint.Version), "console", "")
	if published.Code != http.StatusOK {
		t.Fatalf("publish hosted endpoint: %d %s", published.Code, published.Body)
	}
	invoked := request(http.MethodPost, "/invoke/v1/endpoints/hosted-chat/conversations",
		`{"message":"hello hosted"}`, resource.Credential, "hosted-turn-1")
	if invoked.Code != http.StatusAccepted {
		t.Fatalf("invoke hosted endpoint: %d %s", invoked.Code, invoked.Body)
	}
	var accepted struct {
		InvocationID uuid.UUID `json:"invocationId"`
		SessionID    string    `json:"sessionId"`
	}
	if err := json.Unmarshal(invoked.Body.Bytes(), &accepted); err != nil {
		t.Fatal(err)
	}
	invocation, err := st.Endpoints().GetInvocation(ctx, accepted.InvocationID)
	if err != nil || invocation.Status != controlmodel.EndpointInvocationRunning ||
		invocation.IssueID == nil || invocation.RunID == nil {
		t.Fatalf("hosted invocation did not enter running state: %+v err=%v", invocation, err)
	}
	attempts, err := st.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{
		Tenant: "t", Namespace: "n", AgentID: agent.ID, SessionID: accepted.SessionID,
	})
	if err != nil || len(attempts) != 1 {
		t.Fatalf("hosted endpoint attempt=%+v err=%v", attempts, err)
	}
	claimed, err := server.taskPlane.Claim(ctx, store.ExecutionClaim{Tenant: "t", Namespace: "n",
		RuntimePoolName: attempts[0].RuntimePoolName, HostID: host.ID,
		HostGeneration: host.LeaseGeneration, LeaseOwner: "host/endpoint", LeaseToken: "endpoint-lease",
		LeaseTTL: time.Minute})
	if err != nil {
		t.Fatal(err)
	}
	preparing, _ := server.taskPlane.MarkPreparing(ctx, claimed.ID, claimed.LeaseToken, claimed.FencingToken)
	running, _ := server.taskPlane.MarkRunning(ctx, preparing.ID, preparing.LeaseToken,
		preparing.FencingToken, "endpoint-provider-session", "workspace")
	completed, err := server.taskPlane.Complete(ctx, running.ID, running.LeaseToken, running.FencingToken,
		json.RawMessage(`{"output":"hosted answer"}`), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = server.projectHostedAttemptTerminal(ctx, completed); err != nil {
		t.Fatal(err)
	}
	invocation, err = st.Endpoints().GetInvocation(ctx, accepted.InvocationID)
	if err != nil || invocation.Status != controlmodel.EndpointInvocationCompleted ||
		hostedResultOutput(invocation.Result) != "hosted answer" || invocation.CompletedAt == nil {
		t.Fatalf("hosted endpoint terminal state was not projected: %+v err=%v", invocation, err)
	}
}
