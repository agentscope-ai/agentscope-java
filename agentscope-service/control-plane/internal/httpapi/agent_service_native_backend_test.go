// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.

package httpapi

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/agentservice"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/features"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/orchestration"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func TestAgentServiceNativeHostedExecutionUsesFrozenConfigurationAndPhysicalStopBarrier(t *testing.T) {
	types := []struct {
		mode              model.EndpointInvocationMode
		driver            string
		cancel            bool
		schemaFailure     bool
		approval          bool
		deny              bool
		team              bool
		parallel          bool
		largeEvents       bool
		capacityFailure   bool
		pendingApproval   bool
		credentialRefresh bool
	}{
		{mode: model.EndpointJobMode, driver: store.DriverMemory}, {mode: model.EndpointConversationMode, driver: store.DriverMemory},
		{mode: model.EndpointJobMode, driver: store.DriverPostgres}, {mode: model.EndpointConversationMode, driver: store.DriverPostgres},
		{mode: model.EndpointJobMode, driver: store.DriverMemory, cancel: true}, {mode: model.EndpointJobMode, driver: store.DriverPostgres, cancel: true},
		{mode: model.EndpointJobMode, driver: store.DriverMemory, schemaFailure: true},
		{mode: model.EndpointJobMode, driver: store.DriverMemory, approval: true},
		{mode: model.EndpointJobMode, driver: store.DriverMemory, approval: true, deny: true},
		{mode: model.EndpointJobMode, driver: store.DriverPostgres, approval: true},
		{mode: model.EndpointJobMode, driver: store.DriverMemory, team: true},
		{mode: model.EndpointJobMode, driver: store.DriverPostgres, team: true},
		{mode: model.EndpointJobMode, driver: store.DriverPostgres, team: true, cancel: true},
		{mode: model.EndpointJobMode, driver: store.DriverMemory, team: true, parallel: true},
		{mode: model.EndpointJobMode, driver: store.DriverPostgres, team: true, parallel: true},
		{mode: model.EndpointJobMode, driver: store.DriverMemory, largeEvents: true},
		{mode: model.EndpointJobMode, driver: store.DriverPostgres, largeEvents: true},
		{mode: model.EndpointJobMode, driver: store.DriverMemory, capacityFailure: true},
		{mode: model.EndpointJobMode, driver: store.DriverPostgres, capacityFailure: true},
		{mode: model.EndpointJobMode, driver: store.DriverMemory, cancel: true, pendingApproval: true},
		{mode: model.EndpointJobMode, driver: store.DriverPostgres, cancel: true, pendingApproval: true},
		{mode: model.EndpointJobMode, driver: store.DriverPostgres, cancel: true, pendingApproval: true, capacityFailure: true},
		{mode: model.EndpointJobMode, driver: store.DriverMemory, credentialRefresh: true},
		{mode: model.EndpointJobMode, driver: store.DriverPostgres, credentialRefresh: true},
	}
	for _, test := range types {
		t.Run(fmt.Sprintf("%s/%s/cancel=%t/schemaFailure=%t/approval=%t/deny=%t/team=%t/parallel=%t/largeEvents=%t/capacityFailure=%t/pendingApproval=%t/credentialRefresh=%t", test.driver, test.mode, test.cancel, test.schemaFailure, test.approval, test.deny, test.team, test.parallel, test.largeEvents, test.capacityFailure, test.pendingApproval, test.credentialRefresh), func(t *testing.T) {
			mode := test.mode
			cfg := store.Config{Driver: store.DriverMemory}
			if test.driver == store.DriverPostgres {
				cfg = agentServiceNativePostgresFixture(t)
			}
			st, agent, binding, host := setupHostedConversationAgent(t, cfg)
			ctx := t.Context()
			runtimeBinding, _ := binding.RuntimeBinding()
			profile, err := st.RuntimeRegistry().GetRuntimeProfileByID(ctx, runtimeBinding.RuntimeProfileID)
			if err != nil {
				t.Fatal(err)
			}
			profile.Configuration = json.RawMessage(`{"model":"frozen-model"}`)
			if _, err = st.RuntimeRegistry().UpsertRuntimeProfile(ctx, profile); err != nil {
				t.Fatal(err)
			}
			id := uuid.New()
			endpoint := &model.Endpoint{ID: id, Tenant: "t", Namespace: "n", TargetType: model.EndpointTargetAgent, TargetRef: agent.ID, InvocationMode: mode}
			if test.team {
				team, err := st.Collaboration().CreateTeam(ctx, &model.CollaborationTeam{Tenant: "t", Namespace: "n", Name: "portal-team",
					Status: model.TeamActive, LeaderAgentRef: agent.ID.String()})
				if err != nil {
					t.Fatal(err)
				}
				endpoint.TargetType, endpoint.TargetRef = model.EndpointTargetTeam, team.ID
			}
			if test.schemaFailure {
				endpoint.OutputSchema = json.RawMessage(`{"type":"object","required":["missing"]}`)
			}
			raw, err := serviceapi.Freeze(ctx, st, endpoint)
			if err != nil {
				t.Fatal(err)
			}
			contract, _ := serviceapi.ReadContract(raw)
			contract.PublicSessionID = id
			configuration, _ := json.Marshal(map[string]any{"session": map[string]any{"id": id.String(), "ownerId": "owner"}, "contract": contract})
			requestContext := runtimeprotocol.Context{Version: 1, Tenant: "t", Namespace: "n", SessionID: id.String(), CommandID: "bootstrap:" + id.String(), Actor: map[string]any{"user": "owner"}, TraceID: "fixture", PermissionExpiresAt: time.Now().Add(time.Minute).UnixMilli()}
			revision, _ := runtimeprotocol.Digest(json.RawMessage(configuration))
			kind := "hosted-runtime"
			if test.team {
				kind = "team"
			}
			bootstrap := runtimeprotocol.RuntimeSessionBootstrap{Context: requestContext, RuntimeKind: kind, ConfigurationRevision: revision, Configuration: configuration}
			digest, _ := runtimeprotocol.BootstrapDigest(bootstrap)
			if _, err = st.RuntimeAgentService().Bootstrap(ctx, bootstrap, digest, time.Now()); err != nil {
				t.Fatal(err)
			}
			profile.Configuration = json.RawMessage(`{"model":"later-model"}`)
			if _, err = st.RuntimeRegistry().UpsertRuntimeProfile(ctx, profile); err != nil {
				t.Fatal(err)
			}
			turnID := strings.Repeat("b", 64)
			requestContext.CommandID, requestContext.TurnID = turnID, &turnID
			input := map[string]any{"message": "Hello hosted"}
			inputDigest, _ := runtimeprotocol.Digest(input)
			command := runtimeprotocol.TurnCommand{Context: requestContext, Input: input, InputDigest: inputDigest, IdempotencyKey: "fixture-turn", AdmissionSequence: 1}
			digest, _ = runtimeprotocol.TurnDigest(command)
			if _, err = st.RuntimeAgentService().AcceptTurn(ctx, command, digest, time.Now()); err != nil {
				t.Fatal(err)
			}
			grants := 0
			agentService := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				grants++
				var request runtimeprotocol.RuntimeExecutionRequest
				if err := runtimeprotocol.Decode(r.Body, &request); err != nil {
					t.Error(err)
					w.WriteHeader(400)
					return
				}
				_ = json.NewEncoder(w).Encode(runtimeprotocol.RuntimeExecutionGrant{Context: request.Context, ExecutionGeneration: request.ExecutionGeneration, ExecutionFence: request.ExecutionFence, ValidUntil: time.Now().Add(time.Minute).UnixMilli()})
			}))
			defer agentService.Close()
			client, err := agentservice.NewClient(agentService.URL, "internal-fixture")
			if err != nil {
				t.Fatal(err)
			}
			defer client.Close()
			server := NewServer(ServerOptions{RuntimeOnly: true, Store: st, InternalToken: "internal-fixture", TaskTokenSecret: strings.Repeat("s", 32), Features: features.Gates{RuntimeHost: true}})
			worker := &agentservice.Worker{Repository: st.RuntimeAgentService(), Client: client, Backend: server.AgentServiceBackend(), Slots: 1}
			advance := func() {
				rows, err := st.RuntimeAgentService().ClaimTurns(ctx, time.Now().Add(3*time.Second), 45*time.Second, 1)
				if err != nil || len(rows) != 1 {
					t.Fatalf("claim=%v %v", rows, err)
				}
				if err = worker.Advance(ctx, rows[0]); err != nil {
					t.Fatal(err)
				}
			}
			advance()
			row, _ := st.RuntimeAgentService().Turn(ctx, turnID)
			if row.Status != "running" {
				t.Fatalf("materialization suspended runnable work: status=%s", row.Status)
			}
			inv, err := st.Endpoints().GetInvocation(ctx, uuid.MustParse(row.NativeInvocationID))
			if err != nil || inv.RunID == nil {
				t.Fatalf("native reservation=%+v %v", inv, err)
			}
			if strings.Contains(string(inv.Contract), "frozen-model") || len(inv.Input) > 0 {
				t.Fatal("native reservation copied confidential admission payloads")
			}
			tasks, err := st.Collaboration().ListAgentTasks(ctx, store.AgentTaskFilter{RunID: *inv.RunID, Limit: 2})
			if err != nil || len(tasks) != 1 {
				t.Fatalf("tasks=%v %v", tasks, err)
			}
			if tasks[0].Originator.Ref != "owner" {
				t.Fatalf("original actor lost=%+v", tasks[0].Originator)
			}
			if mode == model.EndpointJobMode {
				if err = server.DispatchAgentTask(ctx, tasks[0].ID); err != nil {
					t.Fatal(err)
				}
			}
			attempts, err := st.ExecutionAttempts().List(ctx, store.ExecutionAttemptFilter{AgentServiceCommandID: turnID, Limit: 2})
			if err != nil || len(attempts) != 1 {
				t.Fatalf("attempt scope=%v %v", attempts, err)
			}
			var frozen model.RuntimeDispatchSnapshot
			_ = json.Unmarshal(attempts[0].RuntimeBinding, &frozen)
			if frozen.SnapshotCipherVersion != 1 || len(frozen.SealedConfiguration) == 0 || len(frozen.ResolvedProviderConfiguration) != 0 || len(frozen.RuntimeProfile.Configuration) != 0 {
				t.Fatal("native Attempt snapshot retained plaintext provider configuration")
			}
			frozen, err = st.RuntimeSnapshots().RevealDispatch(tasks[0].ID.String(), frozen)
			if err != nil {
				t.Fatal(err)
			}
			var resolved map[string]any
			if json.Unmarshal(frozen.ResolvedProviderConfiguration, &resolved) != nil || len(resolved) != 1 || resolved["model"] != "frozen-model" {
				t.Fatalf("live profile replaced frozen configuration=%s", frozen.ResolvedProviderConfiguration)
			}
			if test.driver == store.DriverPostgres && mode == model.EndpointConversationMode {
				pool, err := pgxpool.New(ctx, cfg.PostgresDSN)
				if err != nil {
					t.Fatal(err)
				}
				defer pool.Close()
				var taskJSON, attemptJSON, outboxJSON string
				if err = pool.QueryRow(ctx, `SELECT t.runtime_binding::text, a.runtime_binding::text
					FROM agent_tasks t JOIN execution_attempts a ON a.agent_task_id=t.id WHERE a.id=$1`, attempts[0].ID).Scan(&taskJSON, &attemptJSON); err != nil {
					t.Fatal(err)
				}
				if err = pool.QueryRow(ctx, `SELECT payload::text FROM control_outbox WHERE aggregate_id=$1 AND event_type='agent-task.dispatched.v1' LIMIT 1`, tasks[0].ID.String()).Scan(&outboxJSON); err != nil {
					t.Fatal(err)
				}
				for _, raw := range []string{taskJSON, attemptJSON, outboxJSON} {
					if strings.Contains(raw, "frozen-model") || !strings.Contains(raw, "sealedConfiguration") {
						t.Fatal("native storage or dispatch outbox retained plaintext configuration")
					}
				}
			}
			claimed, err := server.taskPlane.Claim(ctx, store.ExecutionClaim{Tenant: "t", Namespace: "n", RuntimePoolName: host.PoolName, HostID: host.ID, HostGeneration: host.LeaseGeneration, LeaseOwner: "fixture", LeaseToken: "fixture-lease", LeaseTTL: time.Minute})
			if err != nil {
				t.Fatal(err)
			}
			if _, err = server.taskPlane.MarkPreparing(ctx, claimed.ID, claimed.LeaseToken, claimed.FencingToken); err != nil {
				t.Fatal(err)
			}
			if _, err = server.taskPlane.MarkRunning(ctx, claimed.ID, claimed.LeaseToken, claimed.FencingToken, "provider-session", "workspace"); err != nil {
				t.Fatal(err)
			}
			taskToken, err := server.taskTokens.MintFenced(tasks[0].ID, claimed.ID, claimed.DispatchGeneration, claimed.FencingToken, host.ID, time.Now())
			if err != nil {
				t.Fatal(err)
			}
			if test.credentialRefresh {
				taskToken = exerciseAgentServiceHostCredentialRefresh(t, server, claimed, host, true)
			}
			mcp := func(token, internal string) int {
				request := httptest.NewRequest("POST", "/api/internal/runtime/mcp/collaboration", strings.NewReader(`{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"math.evaluate","arguments":{"expression":"7*6"}}}`))
				request.Header.Set("Content-Type", "application/json")
				request.Header.Set("Authorization", "Bearer "+token)
				request.Header.Set("X-Managed-Agents-Internal-Token", internal)
				out := httptest.NewRecorder()
				server.router.ServeHTTP(out, request)
				if out.Code == 200 && !strings.Contains(out.Body.String(), `"result":"42"`) {
					t.Fatalf("authenticated MCP did not execute its tool=%s", out.Body.String())
				}
				return out.Code
			}
			if mcp(taskToken, "internal-fixture") != 200 || mcp(taskToken, "") != 401 || mcp("", "internal-fixture") != 401 {
				t.Fatal("MCP did not require both physical task identity and service transport")
			}
			staleToken, _ := server.taskTokens.MintFenced(tasks[0].ID, claimed.ID, claimed.DispatchGeneration, claimed.FencingToken+1, host.ID, time.Now())
			legacyToken, _ := server.taskTokens.MintScoped(tasks[0].ID, claimed.ID, claimed.DispatchGeneration, time.Now())
			if mcp(staleToken, "internal-fixture") != 401 || mcp(legacyToken, "internal-fixture") != 401 {
				t.Fatal("MCP accepted an absent or stale physical lease fence")
			}
			leased, err := st.RuntimeAgentService().ClaimTurns(ctx, time.Now().Add(3*time.Second), 45*time.Second, 1)
			if err != nil || len(leased) != 1 {
				t.Fatalf("grant gate claim=%v %v", leased, err)
			}
			gate := leased[0]
			validUntil := gate.GrantUntil
			gate.GrantUntil = 1
			if err = st.RuntimeAgentService().SaveTurn(ctx, gate, nil, time.Now(), false); err != nil {
				t.Fatal(err)
			}
			if _, err = server.taskPlane.RenewExecution(ctx, claimed.ID, claimed.LeaseToken, claimed.FencingToken, time.Minute); !errors.Is(err, store.ErrForbidden) {
				t.Fatalf("expired root grant renewed Host execution=%v", err)
			}
			if mcp(taskToken, "internal-fixture") != 403 {
				t.Fatal("expired root authorization accepted collaboration work")
			}
			if test.credentialRefresh {
				exerciseAgentServiceHostCredentialRefresh(t, server, claimed, host, false)
			}
			gate.GrantUntil = validUntil
			if err = st.RuntimeAgentService().SaveTurn(ctx, gate, nil, time.Now(), true); err != nil {
				t.Fatal(err)
			}
			if test.approval || test.pendingApproval {
				exerciseAgentServiceHostedApproval(t, server, st, worker, row, claimed, taskToken, advance, test.deny, test.pendingApproval)
			}
			var parallelAttempt *model.ExecutionAttempt
			if test.parallel {
				parallelAttempt = exerciseAgentServiceTeamParallel(t, server, st, tasks[0], taskToken, host, agent.ID.String())
			}
			var done *model.ExecutionAttempt
			if test.cancel {
				command := runtimeprotocol.InteractionCommand{Context: row.Command.Context, Kind: "cancel", Payload: map[string]any{
					"target_command_id": turnID, "expected_generation": row.Generation, "target_activation_command_id": row.ActivationCommandID}}
				command.Context.CommandID = "cancel:" + turnID
				if _, err = st.RuntimeAgentService().AcceptCancellation(ctx, command, time.Now()); err != nil {
					t.Fatal(err)
				}
				advance()
				cancelled, _ := st.ExecutionAttempts().Get(ctx, claimed.ID)
				if cancelled.State != model.ExecutionCancelRequested {
					t.Fatalf("native stop not requested=%s", cancelled.State)
				}
				if _, err = server.taskPlane.RenewExecution(ctx, claimed.ID, claimed.LeaseToken, claimed.FencingToken, time.Minute); !errors.Is(err, store.ErrForbidden) {
					t.Fatalf("cancelled activation renewed Host=%v", err)
				}
				done, err = server.taskPlane.ConfirmCancelled(ctx, claimed.ID, claimed.LeaseToken, claimed.FencingToken)
			} else {
				if test.team {
					// A Team cannot converge merely because the provider returned.
					// Its active leader concludes the semantic barrier through the
					// same fenced private MCP used by the real Host daemon.
					req := httptest.NewRequest("POST", "/api/internal/runtime/mcp/collaboration", strings.NewReader(`{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"run.node.complete","arguments":{"output":{"output":"answer","usage":{"inputTokens":3,"outputTokens":2}}}}}`))
					req.Header.Set("Content-Type", "application/json")
					req.Header.Set("Authorization", "Bearer "+taskToken)
					req.Header.Set("X-Managed-Agents-Internal-Token", "internal-fixture")
					out := httptest.NewRecorder()
					server.router.ServeHTTP(out, req)
					var response struct {
						Result struct {
							IsError bool `json:"isError"`
						} `json:"result"`
						Error any `json:"error"`
					}
					if json.Unmarshal(out.Body.Bytes(), &response) != nil || out.Code != 200 || response.Result.IsError || response.Error != nil {
						t.Fatalf("Team conclude=%d %s", out.Code, out.Body.String())
					}
				}
				done, err = server.taskPlane.Complete(ctx, claimed.ID, claimed.LeaseToken, claimed.FencingToken, json.RawMessage(`{"output":"answer","usage":{"inputTokens":3,"outputTokens":2}}`), nil)
			}
			if err != nil {
				t.Fatal(err)
			}
			if test.approval {
				replayed, _ := st.RuntimeAgentService().Turn(ctx, turnID)
				replayed.ControlCommandID, replayed.ControlAnswerIndex = "approval-control", 0
				index, complete, replayErr := (&agentServiceNativeBackend{server: server}).applyApprovals(ctx, *replayed)
				if replayErr != nil || !complete || index != 1 {
					t.Fatalf("decision committed before crash could not finish after provider terminal=%d %t %v", index, complete, replayErr)
				}
			}
			if err = server.projectHostedAttemptTerminal(ctx, done); err != nil {
				t.Fatal(err)
			}
			if err = (&orchestration.Engine{Store: st}).ReconcileRun(ctx, *inv.RunID); err != nil {
				t.Fatal(err)
			}
			advance()
			row, _ = st.RuntimeAgentService().Turn(ctx, turnID)
			if agentservice.Terminal(row.Status) {
				t.Fatal("Turn ended before Host physical-stop proof")
			}
			// A process restart keeps only shared runtime state and the original
			// Host/Attempt credentials; no in-memory execution owner is needed.
			restarted := NewServer(ServerOptions{RuntimeOnly: true, Store: st, InternalToken: "internal-fixture", TaskTokenSecret: strings.Repeat("s", 32), Features: features.Gates{RuntimeHost: true}})
			worker.Backend = restarted.AgentServiceBackend()
			hostToken, _, _ := restarted.runtimeTokens.Mint(host.HostKey, "t", "n", time.Now())
			attemptToken, _ := restarted.taskTokens.MintAttempt(done.ID, done.DispatchGeneration, string(done.BackendKind), host.ID.String(), time.Now())
			hostAction := func(operation string, payload map[string]any) *httptest.ResponseRecorder {
				body, _ := json.Marshal(payload)
				request := httptest.NewRequest("POST", "/api/internal/runtime/runtime-hosts/"+host.ID.String()+"/execution-attempts/"+done.ID.String()+"/"+operation, bytes.NewReader(body))
				request.Header.Set("Content-Type", "application/json")
				request.Header.Set("X-Managed-Agents-Internal-Token", "internal-fixture")
				request.Header.Set("Authorization", "Bearer "+hostToken)
				request.Header.Set("X-Execution-Attempt-Token", attemptToken)
				out := httptest.NewRecorder()
				restarted.router.ServeHTTP(out, request)
				return out
			}
			proof := func(fence int64) int {
				return hostAction("physical-stop", map[string]any{"leaseToken": done.LeaseToken, "fencingToken": fence}).Code
			}
			if out := hostAction("observe", map[string]any{"leaseToken": done.LeaseToken, "fencingToken": done.FencingToken + 1}); out.Code != 409 {
				t.Fatalf("stale observation=%d %s", out.Code, out.Body.String())
			}
			if out := hostAction("observe", map[string]any{"leaseToken": done.LeaseToken, "fencingToken": done.FencingToken}); out.Code != 200 {
				t.Fatalf("lease observation=%d %s", out.Code, out.Body.String())
			}
			observed, _ := st.ExecutionAttempts().Get(ctx, done.ID)
			if (observed.LeaseExpiresAt == nil) != (done.LeaseExpiresAt == nil) || observed.LeaseExpiresAt != nil && !observed.LeaseExpiresAt.Equal(*done.LeaseExpiresAt) {
				t.Fatal("observation extended the physical lease")
			}
			event := map[string]any{"leaseToken": done.LeaseToken, "fencingToken": done.FencingToken, "ordinal": 1,
				"provider": "codex", "eventType": "fixture", "raw": map[string]any{"text": "committed late provider event"}}
			if out := hostAction("events", event); out.Code != 202 {
				t.Fatalf("terminal Attempt sealed final events too early=%d %s", out.Code, out.Body.String())
			}
			if test.largeEvents {
				for ordinal := 2; ordinal <= 4; ordinal++ {
					blocks := make([]any, 64)
					for index := range blocks {
						blocks[index] = map[string]any{"type": "text", "text": fmt.Sprintf("block:%d:%d", ordinal, index)}
					}
					if out := hostAction("events", map[string]any{"leaseToken": done.LeaseToken, "fencingToken": done.FencingToken,
						"ordinal": ordinal, "provider": "claude-code", "eventType": "assistant",
						"raw": map[string]any{"message": map[string]any{"id": fmt.Sprintf("large:%d", ordinal), "content": blocks}}}); out.Code != 202 {
						t.Fatalf("large native event=%d %s", out.Code, out.Body.String())
					}
				}
			}
			if test.capacityFailure {
				blocks := make([]any, 65)
				for index := range blocks {
					blocks[index] = map[string]any{"type": "text", "text": "capacity fixture"}
				}
				if out := hostAction("events", map[string]any{"leaseToken": done.LeaseToken, "fencingToken": done.FencingToken,
					"ordinal": 2, "provider": "claude-code", "eventType": "assistant",
					"raw": map[string]any{"message": map[string]any{"id": "capacity", "content": blocks}}}); out.Code != 202 {
					t.Fatalf("capacity event=%d %s", out.Code, out.Body.String())
				}
				before := grants
				advance()
				pending, _ := st.RuntimeAgentService().Turn(ctx, turnID)
				if !pending.StopRequested || agentservice.Terminal(pending.Status) {
					t.Fatalf("capacity did not persist stop intent before physical completion=%+v", pending)
				}
				worker.Backend = restarted.AgentServiceBackend()
				advance()
				pending, _ = st.RuntimeAgentService().Turn(ctx, turnID)
				if agentservice.Terminal(pending.Status) || grants != before {
					t.Fatal("capacity abort skipped physical stop or renewed execution admission")
				}
			}
			if status := proof(done.FencingToken + 1); status != 409 {
				t.Fatalf("stale physical-stop=%d", status)
			}
			for range 2 {
				if status := proof(done.FencingToken); status != 200 {
					t.Fatalf("physical-stop=%d", status)
				}
			}
			event["ordinal"] = 2
			if test.largeEvents {
				event["ordinal"] = 5
			}
			if test.capacityFailure {
				event["ordinal"] = 3
			}
			out := hostAction("events", event)
			var sealed map[string]any
			_ = json.Unmarshal(out.Body.Bytes(), &sealed)
			if out.Code != 200 || sealed["accepted"] != false {
				t.Fatalf("sealed Attempt accepted late event=%d %s", out.Code, out.Body.String())
			}
			if parallelAttempt != nil {
				advance()
				pending, _ := st.RuntimeAgentService().Turn(ctx, turnID)
				if agentservice.Terminal(pending.Status) {
					t.Fatal("Team ended before its parallel worker physically stopped")
				}
				token, _ := restarted.taskTokens.MintAttempt(parallelAttempt.ID, parallelAttempt.DispatchGeneration, string(parallelAttempt.BackendKind), host.ID.String(), time.Now())
				raw, _ := json.Marshal(map[string]any{"leaseToken": parallelAttempt.LeaseToken, "fencingToken": parallelAttempt.FencingToken})
				request := httptest.NewRequest("POST", "/api/internal/runtime/runtime-hosts/"+host.ID.String()+"/execution-attempts/"+parallelAttempt.ID.String()+"/physical-stop", bytes.NewReader(raw))
				request.Header.Set("Content-Type", "application/json")
				request.Header.Set("X-Managed-Agents-Internal-Token", "internal-fixture")
				request.Header.Set("Authorization", "Bearer "+hostToken)
				request.Header.Set("X-Execution-Attempt-Token", token)
				out := httptest.NewRecorder()
				restarted.router.ServeHTTP(out, request)
				if out.Code != 200 {
					t.Fatalf("worker physical-stop=%d %s", out.Code, out.Body.String())
				}
			}
			beforeFinal := grants
			advance()
			row, _ = st.RuntimeAgentService().Turn(ctx, turnID)
			if test.largeEvents {
				var position agentServiceNativeCursor
				if err := json.Unmarshal([]byte(row.NativeCursor), &position); err != nil {
					t.Fatal(err)
				}
				if agentservice.Terminal(row.Status) || position.Attempts[done.ID.String()].EventFactOffset == 0 {
					t.Fatal("oversized fact page ended without persisting its partial native event")
				}
				worker.Backend = NewServer(ServerOptions{RuntimeOnly: true, Store: st, InternalToken: "internal-fixture", TaskTokenSecret: strings.Repeat("s", 32), Features: features.Gates{RuntimeHost: true}}).AgentServiceBackend()
				for round := 0; round < 8 && !agentservice.Terminal(row.Status); round++ {
					advance()
					row, _ = st.RuntimeAgentService().Turn(ctx, turnID)
				}
				seen := map[string]bool{}
				blocks := 0
				for round := 0; round < 256; round++ {
					facts, err := st.RuntimeAgentService().ClaimFacts(ctx, time.Now(), time.Minute, 128)
					if err != nil {
						t.Fatal(err)
					}
					for _, fact := range facts {
						if seen[fact.Value.EventID] {
							t.Fatal("fact replay duplicated a committed event")
						}
						seen[fact.Value.EventID] = true
						if itemID, _ := fact.Value.Payload["item_id"].(string); strings.Contains(itemID, ":large:") {
							blocks++
						}
						if err := st.RuntimeAgentService().AcknowledgeFact(ctx, fact.Value.Context.SessionID, fact.Value.SourceSequence, fact.ClaimToken, time.Now()); err != nil {
							t.Fatal(err)
						}
					}
					if len(facts) == 0 {
						break
					}
				}
				if blocks != 192 {
					t.Fatalf("partial event replay emitted %d/192 blocks", blocks)
				}
			}
			expected, tokens := "completed", int64(5)
			if test.cancel {
				expected, tokens = "cancelled", 0
			}
			if test.schemaFailure {
				expected = "failed"
			}
			if test.capacityFailure {
				expected = "failed"
				failed, actionStopped, input, output := false, false, int64(0), int64(0)
				for range 128 {
					facts, err := st.RuntimeAgentService().ClaimFacts(ctx, time.Now(), time.Minute, 128)
					if err != nil {
						t.Fatal(err)
					}
					if len(facts) == 0 {
						break
					}
					for _, fact := range facts {
						if fact.Value.Type == "turn.failed" {
							if test.pendingApproval && !actionStopped {
								t.Fatal("capacity failure preceded Hosted action drainage")
							}
							failed = fact.Value.Payload["reason"] == agentServiceCapacityFailureCode && fact.Value.Payload["capacity"] == "provider_content_blocks"
						}
						if fact.Value.Type == "required_action.resolved" && fact.Value.Payload["resolution_status"] == "cancelled" {
							actionStopped = true
						}
						if fact.Value.Type == "usage.recorded" {
							in, out := agentServiceUsage(fact.Value.Payload["usage"])
							input, output = input+in, output+out
						}
						if err = st.RuntimeAgentService().AcknowledgeFact(ctx, fact.Value.Context.SessionID, fact.Value.SourceSequence, fact.ClaimToken, time.Now()); err != nil {
							t.Fatal(err)
						}
					}
				}
				wantInput, wantOutput := int64(3), int64(2)
				if test.cancel {
					wantInput, wantOutput = 0, 0
				}
				if !failed || input != wantInput || output != wantOutput {
					t.Fatalf("capacity failure receipt=%t usage=%d/%d", failed, input, output)
				}
			}
			if test.pendingApproval && !test.capacityFailure {
				resolved, stopped := false, false
				for range 128 {
					facts, err := st.RuntimeAgentService().ClaimFacts(ctx, time.Now(), time.Minute, 128)
					if err != nil {
						t.Fatal(err)
					}
					if len(facts) == 0 {
						break
					}
					for _, fact := range facts {
						if fact.Value.Type == "required_action.resolved" {
							resolved = fact.Value.Payload["resolution_status"] == "cancelled"
						}
						if fact.Value.Type == "turn.cancelled" {
							if !resolved {
								t.Fatal("Turn ended before Hosted pending action drainage")
							}
							stopped = true
						}
						if err = st.RuntimeAgentService().AcknowledgeFact(ctx, fact.Value.Context.SessionID, fact.Value.SourceSequence, fact.ClaimToken, time.Now()); err != nil {
							t.Fatal(err)
						}
					}
				}
				if !resolved || !stopped {
					t.Fatalf("pending Hosted action resolved=%t stopped=%t", resolved, stopped)
				}
			}
			if row.Status != expected || row.ReportedTokens != tokens || grants != beforeFinal {
				t.Fatalf("final drainage=%+v grants=%d->%d", row, beforeFinal, grants)
			}
			inv, _ = st.Endpoints().GetInvocation(ctx, inv.ID)
			if !serviceapi.Terminal(inv.Status) || inv.CompletedAt == nil {
				t.Fatalf("native invocation remained active=%+v", inv)
			}
			if test.schemaFailure && inv.ErrorCode != "output_schema_violation" {
				t.Fatalf("frozen schema not enforced=%s", inv.ErrorCode)
			}
			if test.capacityFailure && (inv.ErrorCode != agentServiceCapacityFailureCode || inv.Status != model.EndpointInvocationFailed) {
				t.Fatalf("native capacity failure lost=%+v", inv)
			}
		})
	}
}

func agentServiceNativePostgresFixture(t *testing.T) store.Config {
	t.Helper()
	dsn := os.Getenv("MANAGED_AGENTS_NATIVE_RUNTIME_TEST_DSN")
	if dsn == "" {
		t.Skip("MANAGED_AGENTS_NATIVE_RUNTIME_TEST_DSN is not configured")
	}
	pool, err := pgxpool.New(t.Context(), dsn)
	if err != nil {
		t.Fatal(err)
	}
	schema := "portal_native_" + strings.ReplaceAll(uuid.NewString(), "-", "")
	identifier := pgx.Identifier{schema}.Sanitize()
	if _, err = pool.Exec(t.Context(), "CREATE SCHEMA "+identifier); err != nil {
		pool.Close()
		t.Fatal(err)
	}
	t.Cleanup(func() { _, _ = pool.Exec(context.Background(), "DROP SCHEMA "+identifier+" CASCADE"); pool.Close() })
	parsed, err := url.Parse(dsn)
	if err != nil {
		t.Fatal(err)
	}
	query := parsed.Query()
	query.Set("search_path", schema)
	parsed.RawQuery = query.Encode()
	return store.Config{Driver: store.DriverPostgres, PostgresDSN: parsed.String(), RuntimeMasterKey: strings.Repeat("fixture-key", 4), MaxOpenConns: 8, MaxIdleConns: 2}
}

func exerciseAgentServiceTeamParallel(t *testing.T, server *Server, st store.Store, leader *model.AgentTask, token string, host *model.RuntimeHost, agentID string) *model.ExecutionAttempt {
	t.Helper()
	call := func(name string, arguments map[string]any, wantError bool) {
		t.Helper()
		raw, _ := json.Marshal(map[string]any{"jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": map[string]any{"name": name, "arguments": arguments}})
		req := httptest.NewRequest("POST", "/api/internal/runtime/mcp/collaboration", bytes.NewReader(raw))
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Authorization", "Bearer "+token)
		req.Header.Set("X-Managed-Agents-Internal-Token", "internal-fixture")
		out := httptest.NewRecorder()
		server.router.ServeHTTP(out, req)
		var response struct {
			Result struct {
				IsError bool `json:"isError"`
			} `json:"result"`
			Error any `json:"error"`
		}
		if json.Unmarshal(out.Body.Bytes(), &response) != nil || out.Code != 200 || response.Result.IsError != wantError || response.Error != nil {
			t.Fatalf("%s=%d %s", name, out.Code, out.Body.String())
		}
	}
	call("run.replan", map[string]any{"key": "parallel-worker", "type": "agent", "agentId": agentID, "role": "worker"}, false)
	call("run.node.complete", map[string]any{"output": "premature"}, true)
	tasks, err := st.Collaboration().ListAgentTasks(t.Context(), store.AgentTaskFilter{RunID: leader.OrchestrationRunID, Limit: 3})
	if err != nil || len(tasks) != 2 {
		t.Fatalf("parallel tasks=%v %v", tasks, err)
	}
	var worker *model.AgentTask
	for _, task := range tasks {
		if task.ID != leader.ID {
			worker = task
		}
	}
	if err = server.DispatchAgentTask(t.Context(), worker.ID); err != nil {
		t.Fatal(err)
	}
	attempt, err := server.taskPlane.Claim(t.Context(), store.ExecutionClaim{Tenant: leader.Tenant, Namespace: leader.Namespace, RuntimePoolName: host.PoolName,
		HostID: host.ID, HostGeneration: host.LeaseGeneration, LeaseOwner: "parallel-worker", LeaseToken: "parallel-lease", LeaseTTL: time.Minute})
	if err != nil || attempt.AgentTaskID != worker.ID {
		t.Fatalf("worker claim=%+v %v", attempt, err)
	}
	if _, err = server.taskPlane.MarkPreparing(t.Context(), attempt.ID, attempt.LeaseToken, attempt.FencingToken); err != nil {
		t.Fatal(err)
	}
	if _, err = server.taskPlane.MarkRunning(t.Context(), attempt.ID, attempt.LeaseToken, attempt.FencingToken, "worker-provider", "worker-workspace"); err != nil {
		t.Fatal(err)
	}
	attempt, err = server.taskPlane.Complete(t.Context(), attempt.ID, attempt.LeaseToken, attempt.FencingToken, json.RawMessage(`{"output":"worker deliverable"}`), nil)
	if err != nil {
		t.Fatal(err)
	}
	if err = server.projectHostedAttemptTerminal(t.Context(), attempt); err != nil {
		t.Fatal(err)
	}
	if err = (&orchestration.Engine{Store: st}).ReconcileRun(t.Context(), leader.OrchestrationRunID); err != nil {
		t.Fatal(err)
	}
	return attempt
}
