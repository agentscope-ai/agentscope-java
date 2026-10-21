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
	"encoding/json"
	"fmt"
	"strings"
	"testing"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/runtimeprotocol"
	"github.com/google/uuid"
)

func hostedEventsFixture(t *testing.T) (func(string, string, string) []runtimeprotocol.RuntimeFact, *portalAttemptCursor) {
	t.Helper()
	attempt := &model.ExecutionAttempt{ID: uuid.New(), AgentTaskID: uuid.New(), AgentID: uuid.New()}
	inv := &model.EndpointInvocation{ID: uuid.New()}
	state := &portalAttemptCursor{}
	sequence := 0
	return func(name, kind, raw string) []runtimeprotocol.RuntimeFact {
		t.Helper()
		sequence++
		event := hostedProviderSessionEvent(attempt, name, kind, "thread", int64(sequence), json.RawMessage(raw))
		event.Seq = sequence
		facts, err := portalNativeEvents(event, attempt, inv, state)
		if err != nil {
			t.Fatal(err)
		}
		return facts
	}, state
}

func TestPortalHostedCodexItemsAndToolIdentity(t *testing.T) {
	normalize, _ := hostedEventsFixture(t)
	started := normalize("codex", "item/started", `{"params":{"item":{"id":"message-1","type":"agentMessage","text":""}}}`)
	delta := normalize("codex", "item/agentMessage/delta", `{"params":{"itemId":"message-1","delta":"你好 🧰"}}`)
	completed := normalize("codex", "item/completed", `{"params":{"item":{"id":"message-1","type":"agentMessage","text":"你好 🧰"}}}`)
	if len(started) != 1 || len(delta) != 1 || len(completed) != 1 || started[0].Type != "item.started" || delta[0].Type != "item.delta" || completed[0].Type != "item.completed" {
		t.Fatalf("message facts=%+v %+v %+v", started, delta, completed)
	}
	if started[0].Payload["item_id"] != delta[0].Payload["item_id"] || delta[0].Payload["item_id"] != completed[0].Payload["item_id"] {
		t.Fatal("message identity changed between deltas and completion")
	}
	requested := normalize("codex", "item/started", `{"params":{"item":{"id":"tool-1","type":"commandExecution","command":"date","cwd":"/workspace"}}}`)
	output := normalize("codex", "item/commandExecution/outputDelta", `{"params":{"itemId":"tool-1","delta":"today"}}`)
	finished := normalize("codex", "item/completed", `{"params":{"item":{"id":"tool-1","type":"commandExecution","command":"date","cwd":"/workspace","status":"completed","aggregatedOutput":"today","exitCode":0}}}`)
	if requested[0].Type != "tool.requested" || output[0].Type != "tool.delta" || finished[0].Type != "tool.completed" || requested[0].Payload["tool_call_id"] != finished[0].Payload["tool_call_id"] {
		t.Fatalf("tool facts=%+v %+v %+v", requested, output, finished)
	}
	if _, exists := requested[0].Payload["result"]; exists {
		t.Fatal("pending tool carries a result")
	}
	if facts := normalize("codex", "item/reasoning/textDelta", `{"params":{"delta":"private reasoning"}}`); len(facts) != 0 {
		t.Fatal("raw reasoning leaked into the public stream")
	}
}

func TestPortalHostedUsageExcludesHistoryAndPersistsReplayPosition(t *testing.T) {
	normalize, state := hostedEventsFixture(t)
	usage := func(input, output int) string {
		return fmt.Sprintf(`{"params":{"threadId":"thread","turnId":"turn","tokenUsage":{"total":{"inputTokens":%d,"outputTokens":%d},"last":{"inputTokens":3,"outputTokens":2}}}}`, input, output)
	}
	first := normalize("codex", "thread/tokenUsage/updated", usage(103, 42))
	input, output := portalUsage(first[0].Payload["usage"])
	if input != 3 || output != 2 {
		t.Fatalf("prior thread usage counted: %d/%d", input, output)
	}
	if len(normalize("codex", "thread/tokenUsage/updated", usage(103, 42))) != 0 {
		t.Fatal("duplicate cumulative update counted twice")
	}
	bytes, _ := json.Marshal(state)
	*state = portalAttemptCursor{}
	if err := json.Unmarshal(bytes, state); err != nil {
		t.Fatal(err)
	}
	if len(normalize("codex", "thread/tokenUsage/updated", usage(103, 42))) != 0 {
		t.Fatal("restart lost provider usage position")
	}
	next := normalize("codex", "thread/tokenUsage/updated", usage(109, 44))
	input, output = portalUsage(next[0].Payload["usage"])
	if input != 6 || output != 2 || first[0].Payload["model_call_id"] == next[0].Payload["model_call_id"] {
		t.Fatalf("next usage=%+v", next)
	}
}

func TestPortalHostedMessageBlocksAndLargeToolNumbers(t *testing.T) {
	normalize, _ := hostedEventsFixture(t)
	facts := normalize("claude-code", "assistant", `{"message":{"id":"message-2","content":[{"type":"text","text":"ready"},{"type":"tool_use","id":"use-1","name":"calculate","input":{"number":9007199254740993}}]}}`)
	if len(facts) != 2 || facts[0].Type != "item.completed" || facts[1].Type != "tool.requested" {
		t.Fatalf("multi-block message=%+v", facts)
	}
	input := facts[1].Payload["input"].(map[string]any)
	if input["number"] != json.Number("9007199254740993") {
		t.Fatalf("tool argument precision lost: %+v", input)
	}
	encoded, _ := json.Marshal(facts)
	if strings.Contains(string(encoded), "providerEventType") || strings.Contains(string(encoded), `"raw"`) {
		t.Fatal("provider envelope leaked into public facts")
	}
}

func TestPortalHostedDeferredUsageDoesNotConsumeProviderTotal(t *testing.T) {
	attempt := &model.ExecutionAttempt{ID: uuid.New(), AgentTaskID: uuid.New(), AgentID: uuid.New()}
	inv := &model.EndpointInvocation{ID: uuid.New()}
	state := &portalAttemptCursor{}
	event := hostedProviderSessionEvent(attempt, "codex", "thread/tokenUsage/updated", "thread", 1,
		json.RawMessage(`{"params":{"threadId":"thread","turnId":"turn","tokenUsage":{"total":{"inputTokens":103,"outputTokens":42},"last":{"inputTokens":3,"outputTokens":2}}}}`))
	event.Seq = 1
	facts, drained, err := portalNativeEventPage(event, attempt, inv, state, 0)
	if err != nil || drained || len(facts) != 0 || state.Sequence != 0 || len(state.ProviderUsage) != 0 || state.InputTokens+state.OutputTokens != 0 {
		t.Fatalf("deferred usage mutated cursor=%+v facts=%v %t %v", state, facts, drained, err)
	}
	facts, drained, err = portalNativeEventPage(event, attempt, inv, state, 1)
	if err != nil || !drained || len(facts) != 1 || state.Sequence != 1 || state.InputTokens != 3 || state.OutputTokens != 2 {
		t.Fatalf("usage page=%+v %v %t %v", state, facts, drained, err)
	}
	raw, _ := json.Marshal(state)
	state = &portalAttemptCursor{}
	if err = json.Unmarshal(raw, state); err != nil {
		t.Fatal(err)
	}
	event.Seq = 2
	facts, drained, err = portalNativeEventPage(event, attempt, inv, state, 1)
	if err != nil || !drained || len(facts) != 0 || state.Sequence != 2 || state.InputTokens != 3 || state.OutputTokens != 2 {
		t.Fatalf("restart counted provider total twice=%+v %v %t %v", state, facts, drained, err)
	}
}
