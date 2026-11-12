// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"bytes"
	"encoding/json"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/agentservice"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/features"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func exerciseAgentServiceHostedApproval(t *testing.T, server *Server, st store.Store, worker *agentservice.Worker, row *agentservice.Turn,
	attempt *model.ExecutionAttempt, token string, advance func(), deny, leavePending bool) {
	t.Helper()
	ctx := t.Context()
	taskID := attempt.AgentTaskID.String()
	path := "/api/internal/runtime/agent-tasks/" + taskID + "/runtime-approvals"
	call := func(method, suffix, taskToken string, body any) *httptest.ResponseRecorder {
		raw, _ := json.Marshal(body)
		request := httptest.NewRequest(method, path+suffix, bytes.NewReader(raw))
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("X-Managed-Agents-Internal-Token", "internal-fixture")
		request.Header.Set("X-Agent-Task-Token", taskToken)
		out := httptest.NewRecorder()
		server.router.ServeHTTP(out, request)
		return out
	}
	mcp := func() int {
		request := httptest.NewRequest("POST", "/api/internal/runtime/mcp/collaboration", strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"math.evaluate","arguments":{"expression":"7*6"}}}`))
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("X-Managed-Agents-Internal-Token", "internal-fixture")
		request.Header.Set("Authorization", "Bearer "+token)
		out := httptest.NewRecorder()
		server.router.ServeHTTP(out, request)
		return out.Code
	}
	request := map[string]any{"toolUseId": "approval-call", "toolName": "shell", "input": map[string]any{"command": "fixture", "apiKey": "secret"}}
	if out := call("POST", "", "", request); out.Code != 401 {
		t.Fatalf("service transport impersonated task=%d %s", out.Code, out.Body.String())
	}
	created := call("POST", "", token, request)
	if created.Code != 201 {
		t.Fatalf("create approval=%d %s", created.Code, created.Body.String())
	}
	var result struct {
		Approval *model.Approval `json:"approval"`
	}
	if err := json.Unmarshal(created.Body.Bytes(), &result); err != nil || result.Approval == nil {
		t.Fatalf("approval response=%s %v", created.Body.String(), err)
	}
	approvalID := result.Approval.ID.String()
	control := NewServer(ServerOptions{LocalDev: true, Store: st, InternalToken: "internal-fixture", TaskTokenSecret: strings.Repeat("s", 32), Features: features.Gates{RuntimeHost: true}})
	raw, _ := json.Marshal(map[string]any{"status": "approved", "expectedVersion": result.Approval.Version})
	legacyRequest := httptest.NewRequest("POST", "/api/v1/approvals/"+approvalID+"/decide", bytes.NewReader(raw))
	legacyRequest.Header.Set("Content-Type", "application/json")
	legacyReply := httptest.NewRecorder()
	control.router.ServeHTTP(legacyReply, legacyRequest)
	if legacyReply.Code != 409 {
		t.Fatalf("control plane mutated a Agent Service-owned approval=%d %s", legacyReply.Code, legacyReply.Body.String())
	}
	if strings.Contains(string(result.Approval.Request), `"apiKey":"secret"`) {
		t.Fatal("approval preview leaked sensitive fields")
	}
	advance()
	waiting, err := st.RuntimeAgentService().Turn(ctx, row.Command.Context.CommandID)
	if err != nil || waiting.Status != "requires_action" {
		t.Fatalf("waiting=%v %v", waiting, err)
	}
	if mcp() != 403 {
		t.Fatal("suspended task executed a collaboration tool")
	}
	leased, err := st.RuntimeAgentService().ClaimTurns(ctx, time.Now().Add(3*time.Second), 45*time.Second, 1)
	if err != nil || len(leased) != 1 {
		t.Fatalf("paused claim=%v %v", leased, err)
	}
	paused := leased[0]
	paused.Status, paused.GrantUntil = "running", time.Now().Add(time.Minute).UnixMilli()
	if err = st.RuntimeAgentService().SaveTurn(ctx, paused, nil, time.Now(), false); err != nil {
		t.Fatal(err)
	}
	if mcp() != 403 {
		t.Fatal("a waiting physical task executed under its parallel root's grant")
	}
	paused.Status = "requires_action"
	paused.GrantUntil = 1
	if err = st.RuntimeAgentService().SaveTurn(ctx, paused, nil, time.Now(), true); err != nil {
		t.Fatal(err)
	}
	if _, err = server.taskPlane.RenewExecution(ctx, attempt.ID, attempt.LeaseToken, attempt.FencingToken, time.Minute); err != nil {
		t.Fatalf("paused physical lease=%v", err)
	}
	unchanged, _ := st.RuntimeAgentService().Turn(ctx, row.Command.Context.CommandID)
	if unchanged.GrantUntil != 1 {
		t.Fatal("waiting maintenance extended root grant")
	}
	if out := call("GET", "/"+approvalID+"/decision", token, nil); out.Code != 202 {
		t.Fatalf("pending poll=%d %s", out.Code, out.Body.String())
	}
	if out := call("POST", "", token, request); out.Code != 201 {
		t.Fatalf("waiting create retry=%d %s", out.Code, out.Body.String())
	}
	if leavePending {
		return
	}
	command := runtimeprotocol.InteractionCommand{Context: row.Command.Context, Kind: "approval", Payload: map[string]any{
		"target_command_id": row.Command.Context.CommandID, "expected_generation": waiting.Generation,
		"target_activation_command_id": waiting.ActivationCommandID,
		"answers":                      []any{map[string]any{"request_id": approvalID, "kind": "confirmation", "allow": !deny}}}}
	command.Context.CommandID = "approval-control"
	accept := func(command runtimeprotocol.InteractionCommand) *httptest.ResponseRecorder {
		raw, _ := json.Marshal(command)
		request := httptest.NewRequest("POST", "/api/internal/runtime/commands/interaction", bytes.NewReader(raw))
		request.Header.Set("Content-Type", "application/json")
		request.Header.Set("X-Managed-Agents-Internal-Token", "internal-fixture")
		out := httptest.NewRecorder()
		server.router.ServeHTTP(out, request)
		return out
	}
	wrongHuman := command
	wrongHuman.Context.Actor = map[string]any{"user": "different-approver"}
	if out := accept(wrongHuman); out.Code != 403 {
		t.Fatalf("approval changed accountable human=%d %s", out.Code, out.Body.String())
	}
	for range 2 {
		if out := accept(command); out.Code != 200 {
			t.Fatalf("approval admission=%d %s", out.Code, out.Body.String())
		}
	}
	// Reconstruct the backend after durable acceptance; decisions survive replicas.
	restarted := NewServer(ServerOptions{RuntimeOnly: true, Store: st, InternalToken: "internal-fixture", TaskTokenSecret: strings.Repeat("s", 32), Features: features.Gates{RuntimeHost: true}})
	worker.Backend = restarted.AgentServiceBackend()
	advance()
	decided, err := st.Collaboration().GetApproval(ctx, result.Approval.ID)
	if err != nil || decided.DecidedBy == nil || decided.DecidedBy.Ref != "owner" {
		t.Fatalf("human decision=%+v %v", decided, err)
	}
	unbound := *decided
	unbound.Decision = json.RawMessage(`{"allow":true}`)
	if err = server.checkRuntimeApprovalDelivery(ctx, &unbound); err == nil {
		t.Fatal("legacy decision bypassed Agent Service activation proof")
	}
	current, _ := st.RuntimeAgentService().Turn(ctx, row.Command.Context.CommandID)
	if current.Generation != 2 || current.ControlCommandID != "" || current.GrantUntil <= time.Now().UnixMilli() {
		t.Fatalf("approval activation=%+v", current)
	}
	out := call("GET", "/"+approvalID+"/decision", token, nil)
	var decision map[string]any
	_ = json.Unmarshal(out.Body.Bytes(), &decision)
	if out.Code != 200 || decision["allow"] != !deny {
		t.Fatalf("decision delivery=%d %s", out.Code, out.Body.String())
	}
	if mcp() != 200 {
		t.Fatal("freshly resumed task waited for the Agent Service observation before executing")
	}
	for range 2 {
		out = call("POST", "/"+approvalID+"/ack", token, map[string]any{"decisionVersion": decided.Version})
		if out.Code != 204 {
			t.Fatalf("decision ack=%d %s", out.Code, out.Body.String())
		}
	}
	advance()
	current, _ = st.RuntimeAgentService().Turn(ctx, row.Command.Context.CommandID)
	if current.Status != "running" {
		t.Fatalf("physical continuation not observed=%+v", current)
	}
	facts, err := st.RuntimeAgentService().ClaimFacts(ctx, time.Now(), time.Second, 128)
	if err != nil {
		t.Fatal(err)
	}
	requested, resolved := false, false
	// Only the next contiguous fact can be claimed per Session.
	for pass := 0; pass < 64 && len(facts) > 0; pass++ {
		for _, fact := range facts {
			requested = requested || fact.Value.Type == "required_action.created"
			if fact.Value.Type == "required_action.resolved" {
				resolved = fact.Value.Payload["activation_command_id"] == command.Context.CommandID && fact.Value.ExecutionID != nil && *fact.Value.ExecutionID == "2"
			}
			if err = st.RuntimeAgentService().AcknowledgeFact(ctx, fact.Value.Context.SessionID, fact.Value.SourceSequence, fact.ClaimToken, time.Now()); err != nil {
				t.Fatal(err)
			}
		}
		facts, err = st.RuntimeAgentService().ClaimFacts(ctx, time.Now(), time.Second, 128)
		if err != nil {
			t.Fatal(err)
		}
	}
	if !requested || !resolved {
		t.Fatalf("standard approval facts requested=%t resolved=%t", requested, resolved)
	}
}
