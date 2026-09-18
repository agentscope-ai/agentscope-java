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
	"context"
	"encoding/json"
	"errors"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/spring-ai-alibaba/aistio/internal/asdp"
	"github.com/spring-ai-alibaba/aistio/internal/controller"
	model "github.com/spring-ai-alibaba/aistio/internal/controlplane/model"
	"github.com/spring-ai-alibaba/aistio/internal/conversation"
	"github.com/spring-ai-alibaba/aistio/internal/store"
)

type failingChatTransport struct{ endpointCommandCapture }

func (s *failingChatTransport) SendConversationTurn(string, string, string, *asdp.ConversationTurnCommand) error {
	return errors.New("runtime disconnected")
}

func TestExternalChatReportsAndRecovery(t *testing.T) {
	testExternalChatReportsAndRecovery(t, false)
}

func TestExternalChatReportsAndRecoveryPostgres(t *testing.T) {
	testExternalChatReportsAndRecovery(t, true)
}

func testExternalChatReportsAndRecovery(t *testing.T, postgres bool) {
	for _, outcome := range []string{"completed", "legacy_completed", "failed", "cancelled", "timeout", "offline", "send_failure"} {
		t.Run(outcome, func(t *testing.T) {
			cfg := store.Config{Driver: store.DriverMemory}
			if postgres {
				cfg = acceptancePostgresConfig(t)
			}
			st, agent, binding, instance := setupConversationAgent(t, cfg)
			ctx := context.Background()
			capture := &endpointCommandCapture{}
			server := NewServer(ServerOptions{Store: st, ASDPCommands: capture})
			chatID := uuid.New()
			session, err := server.resolveAgentConversation(ctx, agent, "", "chat", chatID.String())
			if err != nil {
				t.Fatal(err)
			}
			_, err = st.Chats().Create(ctx, &model.Chat{ID: chatID, Tenant: agent.Tenant, Namespace: agent.Namespace,
				CreatorRef: "owner", AgentID: agent.ID, AgentName: agent.DisplayName, SessionID: session.ID,
				RuntimeSession: session.SessionID, Title: "Test", Status: model.ChatActive})
			if err != nil {
				t.Fatal(err)
			}
			if outcome == "send_failure" {
				server.asdpCommands = &failingChatTransport{}
			}
			err = server.sendAgentConversationTurn(ctx, session, "hello", "chat", chatID.String())
			if outcome == "send_failure" {
				if err == nil {
					t.Fatal("send failure was swallowed")
				}
			} else if err != nil {
				t.Fatal(err)
			}
			current, _ := st.Sessions().GetByID(ctx, session.ID)
			turn := conversation.Read(current)
			if turn == nil {
				t.Fatal("turn was not durably admitted")
			}
			if outcome != "send_failure" {
				if err := server.sendAgentConversationTurn(ctx, session, "overlap", "chat", chatID.String()); !errors.Is(err, store.ErrConflict) {
					t.Fatalf("concurrent turn not rejected: %v", err)
				}
			}
			sink := &controller.SessionEventSink{Store: st}
			identity := controller.RuntimeReportIdentity{Tenant: agent.Tenant, Namespace: agent.Namespace,
				AgentID: agent.ID.String(), BindingID: binding.ID.String(), AgentKey: agent.AgentKey,
				InstanceKey: instance.InstanceKey, InstanceGeneration: instance.Generation}
			report := controller.ObservedConversationTurn{InvocationID: turn.InvocationID.String(), ConversationID: session.ID.String(),
				TurnID: turn.ID.String(), SessionID: session.SessionID, Generation: instance.Generation, Action: outcome,
				Sequence: 2, Payload: json.RawMessage(`{"content":"answer"}`), ErrorCode: "provider_failed", ErrorMessage: "Provider rejected the request"}
			if outcome == "legacy_completed" {
				report.Action, report.Sequence = "completed", 0
			}
			switch outcome {
			case "completed", "legacy_completed", "failed", "cancelled":
				forged := report
				forged.TurnID = uuid.NewString()
				if err = sink.ApplyConversationTurnReport(ctx, identity, forged); err == nil {
					t.Fatal("forged turn accepted")
				}
				stale := identity
				stale.InstanceGeneration++
				if err = sink.ApplyConversationTurnReport(ctx, stale, report); err == nil {
					t.Fatal("stale runtime accepted")
				}
				delta := report
				delta.Action, delta.Sequence = "delta", 1
				if outcome == "legacy_completed" {
					delta.Sequence = 0
				}
				if err = sink.ApplyConversationTurnReport(ctx, identity, delta); err != nil {
					t.Fatal(err)
				}
				if outcome == "legacy_completed" {
					if err = sink.ApplyConversationTurnReport(ctx, identity, delta); err != nil {
						t.Fatal(err)
					}
					stream, _ := st.Events().List(ctx, session.ID)
					chunks := 0
					for _, event := range stream {
						if event.EventType == "assistant.delta" {
							chunks++
						}
					}
					if chunks != 2 {
						t.Fatalf("legacy repeated chunk lost: %d", chunks)
					}
				}
				if err = sink.ApplyConversationTurnReport(ctx, identity, report); err != nil {
					t.Fatal(err)
				}
			case "timeout":
				// A new sweeper uses only persisted state; runtime framework reporting
				// must not hide an admitted turn from timeout recovery.
				current.Framework = "agentscope"
				_, _ = st.Sessions().Upsert(ctx, current)
				if err = conversation.Sweep(ctx, st, turn.Deadline.Add(time.Second)); err != nil {
					t.Fatal(err)
				}
			case "offline":
				instance.Health = model.RuntimeHealthUnhealthy
				_, err = st.RuntimeRegistry().UpsertAgentInstance(ctx, instance)
				if err != nil {
					t.Fatal(err)
				}
				if err = conversation.Sweep(ctx, st, time.Now()); err != nil {
					t.Fatal(err)
				}
			}
			current, _ = st.Sessions().GetByID(ctx, session.ID)
			if conversation.Read(current).Pending() || current.Phase != store.SessionPhaseIdle {
				t.Fatalf("turn stuck busy: %+v", current)
			}
			turns, err := st.Turns().List(ctx, session.ID, 10)
			if err != nil || len(turns) != 1 {
				t.Fatalf("turn history=%v err=%v", turns, err)
			}
			if outcome == "cancelled" && turns[0].Status != store.TurnStatusAborted {
				t.Fatalf("cancelled turn was not aborted: %+v", turns[0])
			}
			before, _ := st.Events().List(ctx, session.ID)
			if len(before) < 3 {
				t.Fatalf("missing durable events: %+v", before)
			}
			last := before[len(before)-1]
			want := "turn." + outcome
			if outcome == "legacy_completed" {
				want = "turn.completed"
			}
			if outcome == "timeout" || outcome == "offline" || outcome == "send_failure" {
				want = "turn.failed"
			}
			if last.EventType != want || want == "turn.failed" && (last.Role != "error" || last.Content == "") {
				t.Fatalf("missing visible terminal event: %+v", last)
			}
			// Duplicate/late completion must not change an already settled turn.
			report.Action, report.Sequence = "completed", 9
			if outcome != "offline" {
				if err = sink.ApplyConversationTurnReport(ctx, identity, report); err != nil {
					t.Fatal(err)
				}
			}
			after, _ := st.Events().List(ctx, session.ID)
			if len(after) != len(before) {
				t.Fatal("late report appended duplicate content")
			}
			if outcome != "offline" && outcome != "send_failure" {
				if err = server.sendAgentConversationTurn(ctx, current, "retry", "chat", chatID.String()); err != nil {
					t.Fatalf("retry rejected: %v", err)
				}
			}
		})
	}
}
