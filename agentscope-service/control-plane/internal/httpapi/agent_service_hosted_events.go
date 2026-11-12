// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package httpapi

import (
	"strconv"

	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/runtimeprotocol"
	"gitlab.alibaba-inc.com/ken.lj/agentcore-managed-agents/managed-agents/control-plane/v2/internal/store"
)

// Provider totals are retained alongside the native event cursor. Replaying a
// committed page cannot count the same provider update again. A resumed Codex
// thread's earlier turns are excluded by using total-last as its initial base.
type agentServiceProviderUsage struct {
	Input  int64 `json:"input"`
	Output int64 `json:"output"`
}

func agentServiceHostedEvents(event *store.SessionEvent, name string, meta map[string]any, state *agentServiceAttemptCursor) ([]runtimeprotocol.RuntimeFact, error) {
	raw, _ := meta["raw"].(map[string]any)
	typ, _ := meta["providerEventType"].(string)
	text := func(value any) string { valueText, _ := value.(string); return valueText }
	object := func(value any) map[string]any { obj, _ := value.(map[string]any); return obj }
	fact := func(kind string, payload map[string]any) []runtimeprotocol.RuntimeFact {
		return []runtimeprotocol.RuntimeFact{{Type: kind, Payload: payload}}
	}
	item := func(id, content, kind string) []runtimeprotocol.RuntimeFact {
		status := "running"
		if kind == "item.completed" {
			status = "completed"
		}
		return fact(kind, map[string]any{"item_id": id, "item": map[string]any{"role": "assistant",
			"status": status, "content": []any{map[string]any{"type": "text", "text": content}}}})
	}
	if name == "codex" {
		params := object(raw["params"])
		switch typ {
		case "item/agentMessage/delta":
			if delta := text(params["delta"]); delta != "" {
				return fact("item.delta", map[string]any{"item_id": text(params["itemId"]),
					"content": []any{map[string]any{"type": "text", "text": delta}}}), nil
			}
		case "item/started", "item/completed":
			native := object(params["item"])
			id := text(native["id"])
			if id == "" {
				return nil, nil
			}
			completed := typ == "item/completed"
			if text(native["type"]) == "agentMessage" {
				kind := "item.started"
				if completed {
					kind = "item.completed"
				}
				return item(id, text(native["text"]), kind), nil
			}
			payload := map[string]any{"tool_call_id": id}
			switch text(native["type"]) {
			case "commandExecution":
				payload["name"], payload["input"] = "shell", map[string]any{"command": native["command"], "cwd": native["cwd"]}
				payload["result"] = map[string]any{"output": native["aggregatedOutput"], "exit_code": native["exitCode"]}
			case "fileChange":
				payload["name"], payload["input"] = "file_change", native["changes"]
				payload["result"] = map[string]any{"status": native["status"]}
			case "mcpToolCall":
				payload["name"], payload["input"] = text(native["server"])+"."+text(native["tool"]), native["arguments"]
				payload["result"] = native["result"]
				payload["error"] = native["error"]
			default:
				return nil, nil
			}
			kind := "tool.requested"
			if completed {
				kind, payload["status"] = "tool.completed", native["status"]
			} else {
				delete(payload, "result")
				delete(payload, "error")
			}
			return fact(kind, payload), nil
		case "item/commandExecution/outputDelta":
			return fact("tool.delta", map[string]any{"tool_call_id": text(params["itemId"]),
				"output": []any{map[string]any{"type": "text", "text": text(params["delta"])}}}), nil
		case "thread/tokenUsage/updated":
			usage := object(params["tokenUsage"])
			total, last := object(usage["total"]), object(usage["last"])
			if len(total) == 0 || len(last) == 0 || text(params["turnId"]) == "" {
				return nil, nil
			}
			input, output := agentServiceUsage(total)
			lastInput, lastOutput := agentServiceUsage(last)
			key := text(params["threadId"]) + ":" + text(params["turnId"])
			if state.ProviderUsage == nil {
				state.ProviderUsage = map[string]*agentServiceProviderUsage{}
			}
			previous := state.ProviderUsage[key]
			if previous == nil {
				if len(state.ProviderUsage) >= 256 {
					return nil, agentServiceObservationCapacityError("provider_usage_streams")
				}
				previous = &agentServiceProviderUsage{Input: max(0, input-lastInput), Output: max(0, output-lastOutput)}
				state.ProviderUsage[key] = previous
			}
			deltaIn, deltaOut := max(0, input-previous.Input), max(0, output-previous.Output)
			previous.Input, previous.Output = max(previous.Input, input), max(previous.Output, output)
			if deltaIn+deltaOut > 0 {
				return fact("usage.recorded", map[string]any{"model_call_id": "provider:" + key + ":" + strconv.Itoa(event.Seq),
					"usage": map[string]any{"inputTokens": deltaIn, "outputTokens": deltaOut}}), nil
			}
		}
	}
	if (name == "claude-code" || name == "qoder") && typ == "assistant" {
		message := object(raw["message"])
		contents, _ := message["content"].([]any)
		if len(contents) > 64 {
			return nil, agentServiceObservationCapacityError("provider_content_blocks")
		}
		id := text(message["id"])
		if id == "" {
			id = "message:" + strconv.Itoa(event.Seq)
		}
		var facts []runtimeprotocol.RuntimeFact
		for index, value := range contents {
			content := object(value)
			switch text(content["type"]) {
			case "text":
				facts = append(facts, item(id+":"+strconv.Itoa(index), text(content["text"]), "item.completed")...)
			case "tool_use":
				facts = append(facts, runtimeprotocol.RuntimeFact{Type: "tool.requested", Payload: map[string]any{
					"tool_call_id": text(content["id"]), "name": text(content["name"]), "input": content["input"]}})
			}
		}
		return facts, nil
	}
	if name == "qwenpaw" && typ == "session/update" {
		update := object(object(raw["params"])["update"])
		content := object(update["content"])
		if text(update["sessionUpdate"]) == "agent_message_chunk" && text(content["type"]) == "text" {
			return fact("item.delta", map[string]any{"item_id": "provider-message",
				"content": []any{map[string]any{"type": "text", "text": text(content["text"])}}}), nil
		}
	}
	return nil, nil
}
