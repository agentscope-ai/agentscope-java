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
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/google/uuid"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/agentservice"
	model "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/controlplane/model"
	serviceapi "gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/invocation"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/orchestration"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

func TestAgentServiceWorkflowApprovalPreservesParallelWorkAndAppliedProof(t *testing.T) {
	for _, driver := range []string{store.DriverMemory, store.DriverPostgres} {
		for _, caseName := range []string{"allow", "deny", "parallel", "signal", "wide", "cancel_before_grant", "reject_before_grant", "nested_allow", "nested_deny", "nested_signal", "nested_parallel", "node_capacity"} {
			t.Run(driver+"/"+caseName, func(t *testing.T) {
				nested := strings.HasPrefix(caseName, "nested_")
				scenario := strings.TrimPrefix(caseName, "nested_")
				cfg := store.Config{Driver: store.DriverMemory}
				if driver == store.DriverPostgres {
					cfg = agentServiceNativePostgresFixture(t)
				}
				st, err := store.Open(t.Context(), cfg)
				if err != nil {
					t.Fatal(err)
				}
				defer st.Close()
				if err = st.Migrate(t.Context()); err != nil {
					t.Fatal(err)
				}
				actor := model.Actor{Type: model.ActorHuman, Ref: "owner"}
				spec := `{"nodes":[{"key":"gate","type":"approval","input":{"value":"run.input.value","apiKey":"run.input.apiKey"},"approval":{"approverRef":"owner","prompt":"Ship?"}},{"key":"after","type":"condition","input":{"answer":"nodes.gate.output.allow"}}],"edges":[{"from":"gate","to":"after"}]}`
				if scenario == "signal" {
					spec = `{"nodes":[{"key":"gate","type":"signal","signalName":"ready"},{"key":"after","type":"condition","input":{"answer":"nodes.gate.output.value"}}],"edges":[{"from":"gate","to":"after"}]}`
				}
				if scenario == "parallel" {
					spec = `{"nodes":[{"key":"gate","type":"approval","input":{"value":"run.input.value","apiKey":"run.input.apiKey"},"approval":{"approverRef":"owner"}},{"key":"independent","type":"timer","timer":{"durationSeconds":3600}}]}`
				}
				if scenario == "wide" {
					var value map[string]any
					_ = json.Unmarshal([]byte(spec), &value)
					nodes := value["nodes"].([]any)
					for index := 0; index < 33; index++ {
						nodes = append(nodes, map[string]any{"key": fmt.Sprintf("wide-%d", index), "type": "condition"})
					}
					value["nodes"] = nodes
					raw, _ := json.Marshal(value)
					spec = string(raw)
				}
				if nested {
					childSpec := spec
					if scenario == "parallel" {
						childSpec = `{"nodes":[{"key":"gate","type":"approval","input":{"value":"run.input.value","apiKey":"run.input.apiKey"},"approval":{"approverRef":"owner"}},{"key":"after","type":"condition","input":{"answer":"nodes.gate.output.allow"}}],"edges":[{"from":"gate","to":"after"}]}`
					}
					// Two nested revisions deliberately reuse node keys. Their UUID
					// identities and graph parents must remain distinct publicly.
					for depth := 0; depth < 2; depth++ {
						child, err := st.Orchestration().CreateDefinition(t.Context(), &model.OrchestrationDefinition{Tenant: "t", Namespace: "n", Name: fmt.Sprintf("child-%d", depth), DraftSpec: json.RawMessage(childSpec), CreatedBy: actor})
						if err != nil {
							t.Fatal(err)
						}
						childRevision, err := (&orchestration.Service{Store: st}).Publish(t.Context(), child.ID, actor)
						if err != nil {
							t.Fatal(err)
						}
						childSpec = fmt.Sprintf(`{"nodes":[{"key":"gate","type":"subrun","definitionRevisionId":%q,"input":{"value":"run.input.value","apiKey":"run.input.apiKey"}},{"key":"after","type":"condition","input":{"answer":"nodes.gate.output.after.answer"}}],"edges":[{"from":"gate","to":"after"}]}`, childRevision.ID.String())
					}
					spec = childSpec
					if scenario == "parallel" {
						var value map[string]any
						_ = json.Unmarshal([]byte(spec), &value)
						value["nodes"] = append(value["nodes"].([]any), map[string]any{"key": "independent", "type": "timer", "timer": map[string]any{"durationSeconds": 3600}})
						raw, _ := json.Marshal(value)
						spec = string(raw)
					}
				}
				definition, err := st.Orchestration().CreateDefinition(t.Context(), &model.OrchestrationDefinition{Tenant: "t", Namespace: "n", Name: "approval", DraftSpec: json.RawMessage(spec), CreatedBy: actor})
				if err != nil {
					t.Fatal(err)
				}
				revision, err := (&orchestration.Service{Store: st}).Publish(t.Context(), definition.ID, actor)
				if err != nil {
					t.Fatal(err)
				}
				sessionID := uuid.New()
				endpoint := &model.Endpoint{ID: sessionID, Tenant: "t", Namespace: "n", TargetType: model.EndpointTargetOrchestrationRevision, TargetRef: revision.ID, InvocationMode: model.EndpointJobMode}
				raw, err := serviceapi.Freeze(t.Context(), st, endpoint)
				if err != nil {
					t.Fatal(err)
				}
				contract, _ := serviceapi.ReadContract(raw)
				contract.PublicSessionID = sessionID
				configuration, _ := json.Marshal(map[string]any{"session": map[string]any{"id": sessionID.String(), "ownerId": "owner"}, "contract": contract})
				rc := runtimeprotocol.Context{Version: 1, Tenant: "t", Namespace: "n", SessionID: sessionID.String(), CommandID: "bootstrap", Actor: map[string]any{"user": "owner"}, TraceID: "fixture", PermissionExpiresAt: time.Now().Add(time.Minute).UnixMilli()}
				rev, _ := runtimeprotocol.Digest(json.RawMessage(configuration))
				bootstrap := runtimeprotocol.RuntimeSessionBootstrap{Context: rc, RuntimeKind: "workflow", ConfigurationRevision: rev, Configuration: configuration}
				digest, _ := runtimeprotocol.BootstrapDigest(bootstrap)
				if _, err = st.RuntimeAgentService().Bootstrap(t.Context(), bootstrap, digest, time.Now()); err != nil {
					t.Fatal(err)
				}
				// Later control-plane edits cannot change this permitted frozen graph.
				live, _ := st.Orchestration().GetDefinition(t.Context(), definition.ID)
				archived := time.Now()
				live.ArchivedAt = &archived
				if _, err = st.Orchestration().UpdateDefinition(t.Context(), live, live.Version); err != nil {
					t.Fatal(err)
				}
				turnID := strings.Repeat("c", 64)
				rc.CommandID, rc.TurnID = turnID, &turnID
				input := map[string]any{"value": 42, "apiKey": "secret"}
				inputDigest, _ := runtimeprotocol.Digest(input)
				command := runtimeprotocol.TurnCommand{Context: rc, Input: input, InputDigest: inputDigest, IdempotencyKey: "turn", AdmissionSequence: 1}
				digest, _ = runtimeprotocol.TurnDigest(command)
				if _, err = st.RuntimeAgentService().AcceptTurn(t.Context(), command, digest, time.Now()); err != nil {
					t.Fatal(err)
				}
				grants := 0
				rejectGrant := false
				agentService := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
					grants++
					if rejectGrant {
						w.WriteHeader(403)
						return
					}
					var request runtimeprotocol.RuntimeExecutionRequest
					if err := runtimeprotocol.Decode(r.Body, &request); err != nil {
						t.Error(err)
						w.WriteHeader(400)
						return
					}
					if request.Context.Actor["user"] != "owner" {
						t.Error("lost original caller")
					}
					_ = json.NewEncoder(w).Encode(runtimeprotocol.RuntimeExecutionGrant{Context: request.Context, ExecutionGeneration: request.ExecutionGeneration, ExecutionFence: request.ExecutionFence, ValidUntil: time.Now().Add(time.Minute).UnixMilli()})
				}))
				defer agentService.Close()
				client, err := agentservice.NewClient(agentService.URL, "fixture")
				if err != nil {
					t.Fatal(err)
				}
				defer client.Close()
				server := NewServer(ServerOptions{RuntimeOnly: true, Store: st, InternalToken: "fixture"})
				worker := &agentservice.Worker{Repository: st.RuntimeAgentService(), Client: client, Backend: server.AgentServiceBackend(), Slots: 1}
				advance := func() {
					t.Helper()
					rows, err := st.RuntimeAgentService().ClaimTurns(t.Context(), time.Now().Add(3*time.Second), 45*time.Second, 1)
					if err != nil || len(rows) != 1 {
						t.Fatalf("claim=%v %v", rows, err)
					}
					if err = worker.Advance(t.Context(), rows[0]); err != nil {
						t.Fatal(err)
					}
				}
				advance()
				if nested {
					for range 5 {
						advance()
					}
				}
				if scenario == "wide" {
					// Graph construction, execution and observation each commit
					// finite pages. Keep the final public-state assertion below.
					for pass := 0; pass < 16; pass++ {
						current, err := st.RuntimeAgentService().Turn(t.Context(), turnID)
						if err != nil {
							t.Fatal(err)
						}
						if current.Status == "requires_action" {
							break
						}
						advance()
					}
				}
				turn, _ := st.RuntimeAgentService().Turn(t.Context(), turnID)
				expected := "requires_action"
				if scenario == "parallel" {
					expected = "running"
				}
				if turn.Status != expected {
					t.Fatalf("parallel wait=%s wanted %s", turn.Status, expected)
				}
				inv, _ := st.Endpoints().GetInvocation(t.Context(), uuid.MustParse(turn.NativeInvocationID))
				nodes, err := st.Orchestration().ListNodes(t.Context(), *inv.RunID)
				if err != nil {
					t.Fatal(err)
				}
				if nested {
					root, err := st.Orchestration().GetRun(t.Context(), *inv.RunID)
					if err != nil {
						t.Fatal(err)
					}
					runs, err := st.Orchestration().ListRuns(t.Context(), store.OrchestrationRunFilter{RootIssueID: root.RootIssueID, OldestFirst: true, Limit: 4})
					if err != nil || len(runs) != 3 {
						t.Fatalf("nested materialization=%+v %v", runs, err)
					}
					nodes, err = st.Orchestration().ListNodes(t.Context(), runs[2].ID)
					if err != nil {
						t.Fatal(err)
					}
				}
				var gate *model.RunNode
				for _, node := range nodes {
					if node.NodeKey == "gate" {
						gate = node
					}
				}
				approvalID := uuid.NewSHA1(gate.ID, []byte("approval"))
				if scenario == "signal" {
					approvalID = gate.ID
				}
				if scenario == "node_capacity" {
					claims, err := st.RuntimeAgentService().ClaimTurns(t.Context(), time.Now().Add(3*time.Second), 45*time.Second, 1)
					if err != nil || len(claims) != 1 {
						t.Fatalf("capacity claim=%v %v", claims, err)
					}
					row := claims[0]
					var position agentServiceNativeCursor
					if err = json.Unmarshal([]byte(row.NativeCursor), &position); err != nil {
						t.Fatal(err)
					}
					for len(position.Nodes) < 8192 {
						position.Nodes[uuid.NewString()] = &agentServiceNodeCursor{Version: 1}
					}
					encoded, _ := json.Marshal(position)
					row.NativeCursor = string(encoded)
					if err = st.RuntimeAgentService().SaveTurn(t.Context(), row, nil, time.Now(), true); err != nil {
						t.Fatal(err)
					}
					if _, err = st.Orchestration().CreateNode(t.Context(), &model.RunNode{Tenant: "t", Namespace: "n", RunID: *inv.RunID,
						NodeKey: "over-capacity", Type: model.RunNodeCondition, State: model.RunNodeReady, Config: json.RawMessage(`{"key":"over-capacity","type":"condition"}`)}); err != nil {
						t.Fatal(err)
					}
					before := grants
					advance()
					current, _ := st.RuntimeAgentService().Turn(t.Context(), turnID)
					if !current.StopRequested || agentservice.Terminal(current.Status) {
						t.Fatalf("capacity stop intent=%+v", current)
					}
					worker.Backend = NewServer(ServerOptions{RuntimeOnly: true, Store: st, InternalToken: "fixture"}).AgentServiceBackend()
					for pass := 0; pass < 8 && !agentservice.Terminal(current.Status); pass++ {
						advance()
						current, _ = st.RuntimeAgentService().Turn(t.Context(), turnID)
					}
					if current.Status != "failed" || grants != before {
						t.Fatalf("capacity convergence=%+v grants=%d->%d", current, before, grants)
					}
					resolved, failed := false, false
					for range 128 {
						facts, err := st.RuntimeAgentService().ClaimFacts(t.Context(), time.Now(), time.Minute, 128)
						if err != nil {
							t.Fatal(err)
						}
						if len(facts) == 0 {
							break
						}
						for _, fact := range facts {
							if fact.Value.Type == "required_action.resolved" && fact.Value.Payload["request_id"] == approvalID.String() {
								resolved = fact.Value.Payload["resolution_status"] == "cancelled"
							}
							if fact.Value.Type == "turn.failed" {
								if !resolved {
									t.Fatal("capacity terminal preceded pending action drainage")
								}
								failed = fact.Value.Payload["reason"] == agentServiceCapacityFailureCode && fact.Value.Payload["capacity"] == "native_nodes"
							}
							if err = st.RuntimeAgentService().AcknowledgeFact(t.Context(), fact.Value.Context.SessionID, fact.Value.SourceSequence, fact.ClaimToken, time.Now()); err != nil {
								t.Fatal(err)
							}
						}
					}
					if !resolved || !failed {
						t.Fatalf("capacity receipts resolved=%t failed=%t", resolved, failed)
					}
					return
				}

				if err = (&orchestration.Service{Store: st}).Signal(t.Context(), *inv.RunID, "ready", "agent-bypass", json.RawMessage(`{"value":1}`), actor); err != store.ErrForbidden {
					t.Fatalf("legacy/MCP signal reported acceptance: %v", err)
				}
				control := NewServer(ServerOptions{LocalDev: true, Store: st})
				var request *http.Request
				if scenario == "signal" {
					request = httptest.NewRequest("POST", "/api/v1/orchestration-runs/"+inv.RunID.String()+"/signals/ready", strings.NewReader(`{"idempotencyKey":"bypass","payload":{"value":99}}`))
				} else {
					approval, err := st.Collaboration().GetApproval(t.Context(), approvalID)
					if err != nil {
						t.Fatal(err)
					}
					var preview map[string]any
					if json.Unmarshal(approval.Request, &preview) != nil || preview["value"] != float64(42) || preview["apiKey"] != "secret" {
						t.Fatalf("Workflow lost admitted input: %s", approval.Request)
					}
					request = httptest.NewRequest("POST", "/api/v1/approvals/"+approvalID.String()+"/decide", strings.NewReader(fmt.Sprintf(`{"status":"approved","expectedVersion":%d}`, approval.Version)))
				}
				request.Header.Set("Content-Type", "application/json")
				reply := httptest.NewRecorder()
				control.router.ServeHTTP(reply, request)
				if reply.Code != 409 {
					t.Fatalf("legacy bypass=%d %s", reply.Code, reply.Body.String())
				}

				// A waiting graph does not keep asking for execution grants.
				oldGrants := grants
				advance()
				if scenario != "parallel" && grants != oldGrants {
					t.Fatal("human wait renewed execution grant")
				}
				if scenario == "parallel" && grants == oldGrants {
					t.Fatal("approval paused autonomous branch")
				}
				turn, _ = st.RuntimeAgentService().Turn(t.Context(), turnID)
				interaction := runtimeprotocol.InteractionCommand{Context: rc, Kind: "approval", Payload: map[string]any{
					"target_command_id": turnID, "target_activation_command_id": turn.ActivationCommandID, "expected_generation": turn.Generation,
					"answers": []any{map[string]any{"request_id": approvalID.String(), "kind": "confirmation", "allow": scenario != "deny"}},
				}}
				interaction.Context.CommandID = "human-answer"
				if scenario == "signal" {
					interaction.Kind = "signal"
					interaction.Payload["answers"] = []any{map[string]any{"request_id": approvalID.String(), "kind": "signal", "value": map[string]any{"value": json.Number("9007199254740993")}}}
					// A legacy broadcast cannot poison a later targeted Agent Service reply.
					_, err = st.Orchestration().AppendRunEvent(t.Context(), &model.RunEvent{RunID: *inv.RunID, Tenant: "t", Namespace: "n", Type: "run.signal.ready", Payload: json.RawMessage(`{"value":99}`), Actor: actor})
					if err != nil {
						t.Fatal(err)
					}
				}

				accept := func(in runtimeprotocol.InteractionCommand) int {
					raw, _ := json.Marshal(in)
					req := httptest.NewRequest("POST", "/api/internal/runtime/commands/interaction", bytes.NewReader(raw))
					req.Header.Set("Content-Type", "application/json")
					req.Header.Set("X-Managed-Agents-Internal-Token", "fixture")
					out := httptest.NewRecorder()
					server.router.ServeHTTP(out, req)
					return out.Code
				}
				wrong := interaction
				wrong.Context.Actor = map[string]any{"user": "other"}
				if scenario != "signal" {
					if code := accept(wrong); code != 403 {
						t.Fatalf("wrong approver=%d", code)
					}
				}
				for range 2 {
					if code := accept(interaction); code != 200 {
						t.Fatalf("answer=%d", code)
					}
				}
				if err = (&orchestration.Engine{Store: st}).ReconcileRun(t.Context(), *inv.RunID); err != store.ErrForbidden {
					t.Fatalf("graph advanced before its fresh execution grant: %v", err)
				}
				if scenario == "cancel_before_grant" || scenario == "reject_before_grant" {
					before := grants
					if scenario == "cancel_before_grant" {
						queued, _ := st.RuntimeAgentService().Turn(t.Context(), turnID)
						cancellation := runtimeprotocol.InteractionCommand{Context: rc, Kind: "cancel", Payload: map[string]any{
							"target_command_id": turnID, "target_activation_command_id": queued.ActivationCommandID, "expected_generation": 1,
						}}
						cancellation.Context.CommandID = "cancel-before-grant"
						if code := accept(cancellation); code != 200 {
							t.Fatalf("cancel=%d", code)
						}
					} else {
						rejectGrant = true
					}
					// Neither stopping nor final graph drainage may execute the answer.
					worker.Backend = NewServer(ServerOptions{RuntimeOnly: true, Store: st, InternalToken: "fixture"}).AgentServiceBackend()
					for pass := 0; pass < 8; pass++ {
						advance()
						current, _ := st.RuntimeAgentService().Turn(t.Context(), turnID)
						if agentservice.Terminal(current.Status) {
							break
						}
					}
					current, _ := st.RuntimeAgentService().Turn(t.Context(), turnID)
					wanted, wantGrants := "cancelled", before
					if scenario == "reject_before_grant" {
						wanted, wantGrants = "interrupted", before+1
					}
					if current.Status != wanted || grants != wantGrants {
						t.Fatalf("stop=%+v grants=%d wanted %s/%d", current, grants, wanted, wantGrants)
					}
					approval, _ := st.Collaboration().GetApproval(t.Context(), approvalID)
					if approval.Status == model.ApprovalApproved {
						t.Fatal("ungranted answer executed")
					}
					terminal := false
					for range 128 {
						facts, err := st.RuntimeAgentService().ClaimFacts(t.Context(), time.Now(), time.Minute, 128)
						if err != nil {
							t.Fatal(err)
						}
						if len(facts) == 0 {
							break
						}
						for _, fact := range facts {
							if fact.Value.Type == "turn."+wanted {
								terminal = fact.Value.ExecutionID == nil && fact.Value.Payload["control_stop"] == true && fact.Value.Payload["native_stopped"] == true && fact.Value.Payload["activation_command_id"] == "human-answer"
							} else if fact.Value.ExecutionID != nil && *fact.Value.ExecutionID != "1" {
								t.Fatalf("invented observation grant: %+v", fact.Value)
							}
							if err = st.RuntimeAgentService().AcknowledgeFact(t.Context(), fact.Value.Context.SessionID, fact.Value.SourceSequence, fact.ClaimToken, time.Now()); err != nil {
								t.Fatal(err)
							}
						}
					}
					if !terminal {
						t.Fatal("missing stopped control receipt")
					}
					return
				}
				// Reconstruct the worker/backend after durable acceptance.
				worker.Backend = NewServer(ServerOptions{RuntimeOnly: true, Store: st, InternalToken: "fixture"}).AgentServiceBackend()
				advance()
				if nested {
					// The independent orchestration worker consumes the granted
					// child answer; public waiting must not revoke that permission.
					for range 10 {
						current, _ := st.RuntimeAgentService().Turn(t.Context(), turnID)
						if agentservice.Terminal(current.Status) {
							break
						}
						(&orchestration.Worker{Store: st, Batch: 16}).ReconcileOnce(t.Context())
						advance()
						if scenario == "parallel" {
							gateNow, _ := st.Orchestration().GetNode(t.Context(), gate.ID)
							if model.IsRunNodeTerminal(gateNow.State) {
								for range 3 {
									advance()
								}
								break
							}
						}
					}
				}
				current, _ := st.RuntimeAgentService().Turn(t.Context(), turnID)
				if scenario == "wide" {
					if current.Status == "completed" {
						t.Fatal("Turn settled before final bounded node scan")
					}
					before := grants
					for pass := 0; pass < 4 && current.Status != "completed"; pass++ {
						advance()
						current, _ = st.RuntimeAgentService().Turn(t.Context(), turnID)
					}
					if grants != before {
						t.Fatal("final graph drainage requested execution grants")
					}
				}
				expected = "completed"
				if scenario == "deny" {
					expected = "failed"
				}
				if scenario == "parallel" {
					expected = "running"
				}
				if current.Status != expected {
					t.Fatalf("continuation=%+v wanted %s", current, expected)
				}
				if current.Generation != 2 || current.ControlCommandID != "" {
					t.Fatalf("activation=%+v", current)
				}
				if code := accept(interaction); code != 200 {
					t.Fatalf("terminal duplicate=%d", code)
				}
				finishedNode, _ := st.Orchestration().GetNode(t.Context(), gate.ID)
				if strings.Contains(string(finishedNode.Output), "portal_fence") {
					t.Fatal("transport proof leaked into node output")
				}

				if scenario != "signal" {
					decided, _ := st.Collaboration().GetApproval(t.Context(), approvalID)
					if decided.DecidedBy == nil || *decided.DecidedBy != actor {
						t.Fatalf("actor=%+v", decided)
					}
				} else {
					var value map[string]any
					decoder := json.NewDecoder(bytes.NewReader(finishedNode.Output))
					decoder.UseNumber()
					if decoder.Decode(&value) != nil || value["value"] != json.Number("9007199254740993") || len(value) != 1 {
						t.Fatalf("signal value rounded/leaked: %s", finishedNode.Output)
					}
				}

				if scenario == "signal" {
					for _, node := range nodes {
						if node.NodeKey == "after" {
							after, _ := st.Orchestration().GetNode(t.Context(), node.ID)
							var value map[string]any
							decoder := json.NewDecoder(bytes.NewReader(after.Output))
							decoder.UseNumber()
							if decoder.Decode(&value) != nil || value["answer"] != json.Number("9007199254740993") {
								t.Fatalf("downstream CEL rounded signal: %s", after.Output)
							}
						}
					}
				}
				requested, resolved, steps := false, false, 0
				graphs, repeatedKeys := map[string]string{}, map[string]bool{}
				for range 128 {
					facts, err := st.RuntimeAgentService().ClaimFacts(t.Context(), time.Now(), time.Minute, 128)
					if err != nil {
						t.Fatal(err)
					}
					if len(facts) == 0 {
						break
					}
					for _, fact := range facts {
						if fact.Value.Type == "required_action.created" {
							requested = true
							if strings.Contains(fmt.Sprint(fact.Value.Payload), "secret") {
								t.Fatal("preview leaked secret")
							}
							if nested && fact.Value.Payload["graph_id"] != gate.RunID.String() {
								t.Fatal("child pending action lost its graph scope")
							}
						}
						if fact.Value.Type == "required_action.resolved" {
							resolved = fact.Value.Payload["activation_command_id"] == "human-answer" && fact.Value.ExecutionID != nil && *fact.Value.ExecutionID == "2"
						}
						if fact.Value.Type == "step.updated" {
							steps++
							if strings.HasPrefix(fmt.Sprint(fact.Value.Payload["status"]), "node.") {
								t.Fatal("raw node event escaped normalized status")
							}
							if fact.Value.Payload["kind"] == "graph" {
								graphs[fmt.Sprint(fact.Value.Payload["graph_id"])] = fmt.Sprint(fact.Value.Payload["status"])
							}
							if fact.Value.Payload["name"] == "gate" {
								repeatedKeys[fmt.Sprint(fact.Value.Payload["step_id"])] = true
								if fact.Value.Payload["parent_step_id"] != fact.Value.Payload["graph_id"] {
									t.Fatal("node lost its graph parent")
								}
							}
						}
						if nested && fact.Value.Type == "turn.completed" {
							if len(graphs) != 3 {
								t.Fatal("root terminal preceded child graph facts")
							}
							for _, status := range graphs {
								if status != string(model.RunSucceeded) {
									t.Fatal("root terminal preceded final child graph state")
								}
							}
						}
						if err = st.RuntimeAgentService().AcknowledgeFact(t.Context(), fact.Value.Context.SessionID, fact.Value.SourceSequence, fact.ClaimToken, time.Now()); err != nil {
							t.Fatal(err)
						}
					}
				}
				if !requested || !resolved || steps < 2 {
					t.Fatalf("facts requested=%t resolved=%t steps=%d", requested, resolved, steps)
				}
				if nested && (len(graphs) != 3 || len(repeatedKeys) != 3) {
					t.Fatalf("same-name nested steps lost identity: graphs=%v gateIDs=%v", graphs, repeatedKeys)
				}
			})
		}
	}
}
