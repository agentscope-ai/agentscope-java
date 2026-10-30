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
	"encoding/json"
	"testing"

	"github.com/google/uuid"

	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
)

// TestProjectedSessionEventTranslatesPublicVocabulary pins the two-stage
// translation (LegacySessionEventAdapter + managedReportToSessionEvent) that
// feeds the Console chat transcript: the public vocabulary must arrive in the
// legacy shape with role, content and correlation metadata populated.
func TestProjectedSessionEventTranslatesPublicVocabulary(t *testing.T) {
	textBlocks := func(text string) []any {
		return []any{map[string]any{"type": "text", "text": text}}
	}
	cases := []struct {
		name        string
		event       serviceapi.PublicEvent
		wantType    string
		wantRole    string
		wantContent string
		wantTool    string
		wantMeta    map[string]any
	}{
		{
			name: "user message becomes user.message",
			event: serviceapi.PublicEvent{ID: "evt-1", Type: "item.completed", CreatedAt: 1000,
				Data: map[string]any{
					"turn_id": "turn-1",
					"item": map[string]any{"id": "item-1", "type": "message",
						"role": "USER", "status": "completed", "content": textBlocks("hello")},
				}},
			wantType: evUserMessage, wantRole: "user", wantContent: "hello",
			wantMeta: map[string]any{"message_id": "item-1", "turnId": "turn-1"},
		},
		{
			name: "assistant message becomes agent.message",
			event: serviceapi.PublicEvent{ID: "evt-2", Type: "item.completed", CreatedAt: 2000,
				Data: map[string]any{
					"item": map[string]any{"id": "item-2", "type": "message",
						"role": "ASSISTANT", "status": "completed", "content": textBlocks("hi there")},
				}},
			wantType: evAgentMessage, wantRole: "assistant", wantContent: "hi there",
			wantMeta: map[string]any{"message_id": "item-2"},
		},
		{
			name: "tool result block becomes agent.tool_result",
			event: serviceapi.PublicEvent{ID: "evt-3", Type: "item.completed", CreatedAt: 3000,
				Data: map[string]any{
					"item": map[string]any{"id": "item-3", "type": "tool_result",
						"content": []any{map[string]any{"type": "tool_result", "id": "call-1",
							"name": "get_weather", "state": "completed"}}},
				}},
			wantType: evAgentToolRes, wantRole: "tool", wantTool: "get_weather",
			wantMeta: map[string]any{"tool_use_id": "call-1"},
		},
		{
			name: "tool.requested becomes agent.tool_use",
			event: serviceapi.PublicEvent{ID: "evt-4", Type: "tool.requested", CreatedAt: 4000,
				Data: map[string]any{"tool_call_id": "call-9", "name": "search"}},
			wantType: evAgentToolUse, wantTool: "search",
			wantMeta: map[string]any{"toolCallId": "call-9"},
		},
		{
			name: "item.delta becomes event_delta",
			event: serviceapi.PublicEvent{ID: "evt-5", Type: "item.delta", CreatedAt: 5000,
				Data: map[string]any{"item_id": "item-5", "content": textBlocks("partial")}},
			wantType: evEventDelta,
		},
		{
			name:     "usage becomes span.model_request_end",
			event:    serviceapi.PublicEvent{ID: "evt-6", Type: "usage.recorded", CreatedAt: 6000},
			wantType: evModelReqEnd,
		},
		{
			name:     "unrecognized events pass through unchanged",
			event:    serviceapi.PublicEvent{ID: "evt-7", Type: "run.started", CreatedAt: 7000},
			wantType: "run.started",
		},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			row := projectedSessionEvent(uuid.New(), 7, &tc.event)
			if row.EventType != tc.wantType {
				t.Fatalf("event type = %q, want %q", row.EventType, tc.wantType)
			}
			if row.Seq != 7 {
				t.Fatalf("seq = %d, want 7", row.Seq)
			}
			if tc.wantRole != "" && row.Role != tc.wantRole {
				t.Fatalf("role = %q, want %q", row.Role, tc.wantRole)
			}
			if tc.wantContent != "" && row.Content != tc.wantContent {
				t.Fatalf("content = %q, want %q", row.Content, tc.wantContent)
			}
			if tc.wantTool != "" && row.ToolName != tc.wantTool {
				t.Fatalf("tool name = %q, want %q", row.ToolName, tc.wantTool)
			}
			if tc.wantMeta != nil {
				var meta map[string]any
				if err := json.Unmarshal(row.FrameworkMeta, &meta); err != nil {
					t.Fatalf("framework meta is not JSON: %v", err)
				}
				for key, want := range tc.wantMeta {
					if meta[key] != want {
						t.Fatalf("meta[%s] = %v, want %v", key, meta[key], want)
					}
				}
			}
		})
	}
}

// TestLegacyAdaptKeepsSequenceStable guards the adapter contract: one input
// event always yields one output event with the same identity, so legacy
// clients can keep repairing contiguous sequences.
func TestLegacyAdaptKeepsSequenceStable(t *testing.T) {
	event := serviceapi.PublicEvent{ID: "evt-keep", Type: "session.context_initialized", CreatedAt: 1,
		Data: map[string]any{"schema_version": 1}}
	eventType, payload := legacyAdapt(&event)
	if eventType != "session.context_initialized" {
		t.Fatalf("event type = %q, want passthrough", eventType)
	}
	if payload["schema_version"] != 1 {
		t.Fatalf("payload lost on passthrough: %v", payload)
	}
}
