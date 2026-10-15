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
	"fmt"
	"net/url"
	"strings"
	"time"

	"github.com/google/uuid"

	controlmodel "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	serviceapi "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/invocation"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
)

// projectManagedChatEvents mirrors a managed agent's native session events into
// rt.session_events — the store the Console chat page reads
// (GET /api/v1/sessions/:sessionId/events).
//
// Task-driven turns report their events through the data-plane outbox, but a
// plain chat turn carries no AgentTask/Attempt, so its events never cross that
// bridge and the chat transcript stays empty. This pulls the native events from
// the data plane instead, advancing a durable per-session cursor so retries and
// restarts neither skip nor duplicate rows (Append is idempotent on
// (session_fk, seq); a crash between append and cursor save replays the same
// seqs and the conflict is swallowed).
//
// Errors are returned for the caller to log: a projection hiccup must not fail
// the read itself.
func (s *Server) projectManagedChatEvents(ctx context.Context, sess *store.Session) error {
	if s.product == nil {
		return nil
	}
	binding, err := s.store.AgentCatalog().GetBinding(ctx, sess.BindingID)
	if err != nil || binding.Kind != controlmodel.DataPlaneManaged {
		return nil
	}
	var cfg controlmodel.ManagedBindingConfiguration
	if json.Unmarshal(binding.Configuration, &cfg) != nil || cfg.OwnerRef == "" {
		return nil
	}

	type cursorState struct {
		After string `json:"after"`
		Seq   int    `json:"seq"`
	}
	state := cursorState{}
	path := "chat-native-projection"
	if row, e := s.store.KV().Get(ctx, sess.Tenant, path, sess.ID.String()); e == nil {
		_ = json.Unmarshal(row.Value, &state)
	} else if !errors.Is(e, store.ErrNotFound) {
		return e
	}

	for {
		raw, e := s.product.ManagedServiceRequest(ctx, cfg.OwnerRef, sess.SessionID, "GET",
			"/events?limit=500&after="+url.QueryEscape(state.After), "", nil)
		if e != nil {
			return e
		}
		var page struct {
			Data []serviceapi.PublicEvent `json:"data"`
			Next string                   `json:"next_cursor"`
			More bool                     `json:"has_more"`
		}
		if e = json.Unmarshal(raw, &page); e != nil {
			return e
		}
		for i := range page.Data {
			event := &page.Data[i]
			state.Seq++
			row := projectedSessionEvent(sess.ID, state.Seq, event)
			if e = s.store.Events().Append(ctx, row); e != nil && !errors.Is(e, store.ErrConflict) {
				return e
			}
		}
		previous := state.After
		if page.Next != "" {
			state.After = page.Next
		}
		rawState, _ := json.Marshal(state)
		if _, e = s.store.KV().Put(ctx, sess.Tenant, path, sess.ID.String(), rawState); e != nil {
			return e
		}
		if !page.More || state.After == "" || state.After == previous {
			return nil
		}
	}
}

// Legacy event vocabulary the Console transcript understands. Mirrors
// io.agentscope.builder.web.managed.SessionEventTypes / LegacySessionEventAdapter.
const (
	evUserMessage   = "user.message"
	evAgentMessage  = "agent.message"
	evAgentToolUse  = "agent.tool_use"
	evAgentToolRes  = "agent.tool_result"
	evEventDelta    = "event_delta"
	evModelReqEnd   = "span.model_request_end"
	evContextCompac = "agent.thread_context_compacted"
)

// projectedSessionEvent maps one native public event onto the runtime event row
// the Console renders, chaining the two translations the canonical reporting
// path applies: LegacySessionEventAdapter (public vocabulary -> legacy
// vocabulary) and managedReportToSessionEvent (legacy payload -> columns).
func projectedSessionEvent(sessionFK uuid.UUID, seq int, event *serviceapi.PublicEvent) *store.SessionEvent {
	eventType, payload := legacyAdapt(event)
	row := &store.SessionEvent{
		SessionFK: sessionFK,
		Seq:       seq,
		EventType: eventType,
		OccurredAt: func() time.Time {
			if event.CreatedAt > 0 {
				return time.UnixMilli(event.CreatedAt).UTC()
			}
			return time.Now().UTC()
		}(),
	}
	if payload == nil {
		payload = map[string]any{}
	}
	switch {
	case strings.HasPrefix(eventType, "user."):
		row.Role = "user"
	case eventType == evAgentToolRes:
		row.Role = "tool"
	case strings.HasPrefix(eventType, "agent."):
		row.Role = "assistant"
	case strings.HasPrefix(eventType, "system.") || strings.HasPrefix(eventType, "session."):
		row.Role = "system"
	}
	row.Content = firstString(payload, "text", "message")
	if row.Content == "" {
		if detail, ok := payload["error"].(map[string]any); ok {
			row.Content = firstString(detail, "message", "code")
		}
	}
	row.ToolName = firstString(payload, "toolName", "name")
	row.ToolOutput = firstString(payload, "output")
	if input, ok := payload["input"]; ok && input != nil {
		row.ToolInput, _ = json.Marshal(input)
	}
	metadata := map[string]any{
		"managedEventId": event.ID,
		"managedSeq":     seq,
	}
	if turnID, ok := event.Data["turn_id"]; ok {
		metadata["turnId"] = turnID
	}
	if runID, ok := event.Data["run_id"]; ok {
		metadata["requestId"] = runID
	}
	for _, key := range []string{"toolCallId", "toolUseId", "tool_use_id", "callId"} {
		if value, ok := payload[key]; ok && value != nil {
			metadata[key] = value
		}
	}
	for _, key := range []string{
		"message_id", "final_output", "tool_results", "approvalId", "decisionVersion",
		"source", "status", "state", "endpointInvocationId", "endpointTurnId",
		"truncated", "originalSize", "usage", "error",
	} {
		if value, ok := payload[key]; ok && value != nil {
			metadata[key] = value
		}
	}
	row.FrameworkMeta, _ = json.Marshal(metadata)
	return row
}

// legacyAdapt mirrors LegacySessionEventAdapter.adapt: the public event
// vocabulary (item.*, tool.requested, usage.recorded, ...) translated into the
// legacy vocabulary the transcript derives messages from. One input row always
// yields one output row, keeping id and sequence stable.
func legacyAdapt(event *serviceapi.PublicEvent) (string, map[string]any) {
	payload := map[string]any{}
	for key, value := range event.Data {
		payload[key] = value
	}
	if source, ok := payload["source"].(map[string]any); ok {
		if _, hasNative := source["native_seq"]; hasNative {
			delete(payload, "source")
		} else if _, hasNative := source["native_event_id"]; hasNative {
			delete(payload, "source")
		}
	}
	eventType := event.Type
	switch eventType {
	case "item.completed":
		item, ok := payload["item"].(map[string]any)
		if !ok {
			break
		}
		content := item["content"]
		payload["message_id"] = item["id"]
		payload["event_id"] = item["id"]
		payload["content"] = content
		text := blockText(content)
		payload["text"] = text
		role, _ := item["role"].(string)
		switch {
		case fmt.Sprint(item["type"]) == "tool_result":
			results := toolResultBlocks(content)
			if len(results) > 0 {
				eventType = evAgentToolRes
				for key, value := range results[0] {
					payload[key] = value
				}
				payload["tool_results"] = results
			}
		case text != "" || item["final_output"] == true ||
			strings.EqualFold(role, "user"):
			if strings.EqualFold(role, "user") {
				eventType = evUserMessage
			} else {
				eventType = evAgentMessage
			}
		}
	case "tool.requested":
		eventType = evAgentToolUse
		payload["id"] = payload["tool_call_id"]
		payload["toolCallId"] = payload["tool_call_id"]
		payload["toolName"] = payload["name"]
	case "usage.recorded":
		eventType = evModelReqEnd
	case "context.compacted":
		eventType = evContextCompac
	case "item.delta":
		eventType = evEventDelta
		payload["type"] = evAgentMessage
		if _, ok := payload["item_id"]; ok {
			payload["event_id"] = payload["item_id"]
		} else {
			payload["event_id"] = payload["preview_id"]
		}
		if content, ok := payload["content"]; ok {
			payload["delta"] = blockText(content)
		}
	}
	return eventType, payload
}

// blockText concatenates the text of content blocks, mirroring the adapter's
// text(): only blocks typed "text" contribute.
func blockText(content any) string {
	blocks, ok := content.([]any)
	if !ok {
		if text, ok := content.(string); ok {
			return text
		}
		return ""
	}
	var sb strings.Builder
	for _, raw := range blocks {
		block, ok := raw.(map[string]any)
		if !ok || block["type"] != "text" {
			continue
		}
		if value, ok := block["text"].(string); ok {
			sb.WriteString(value)
		}
	}
	return sb.String()
}

// toolResultBlocks mirrors the adapter's toolResults(): one entry per
// tool_result block, carrying only the legacy display facts.
func toolResultBlocks(content any) []map[string]any {
	blocks, ok := content.([]any)
	if !ok {
		return nil
	}
	var results []map[string]any
	for _, raw := range blocks {
		block, ok := raw.(map[string]any)
		if !ok || block["type"] != "tool_result" {
			continue
		}
		state := block["state"]
		if state == nil {
			state = block["status"]
		}
		results = append(results, map[string]any{
			"id":          block["id"],
			"tool_use_id": block["id"],
			"name":        block["name"],
			"state":       state,
			"output":      "Tool execution " + fmt.Sprint(state),
		})
	}
	return results
}

func firstString(payload map[string]any, keys ...string) string {
	for _, key := range keys {
		if value, ok := payload[key].(string); ok && value != "" {
			return value
		}
	}
	return ""
}
