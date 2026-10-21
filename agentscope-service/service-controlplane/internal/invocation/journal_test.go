/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package invocation

import (
	"context"
	"encoding/json"
	"strings"
	"testing"

	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	_ "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store/memory"
	"github.com/google/uuid"
)

func TestJournalSnapshotRetainsPrefixesAndToolFragments(t *testing.T) {
	ctx := context.Background()
	st, err := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	if err != nil {
		t.Fatal(err)
	}
	defer st.Close()
	j := Journal{Store: st, Tenant: "t", InvocationID: uuid.New()}
	appendEvent := func(key, kind string, data map[string]any) {
		t.Helper()
		if _, err := j.Append(ctx, key, kind, data); err != nil {
			t.Fatal(err)
		}
	}
	d := func(content ...any) map[string]any {
		return map[string]any{"execution_id": "a", "item_id": "m", "content": content}
	}
	appendEvent("1", "item.delta", d(map[string]any{"type": "text", "text": "before "}))
	appendEvent("2", "item.delta", d(map[string]any{"type": "text", "text": "refresh"}, map[string]any{"type": "tool_use", "id": "tool-1", "name": "search", "content": "{\"q\":"}))
	appendEvent("3", "item.delta", d(map[string]any{"type": "tool_use", "content": "\"x\"}"}))
	appendEvent("3", "item.delta", d(map[string]any{"type": "tool_use", "content": "\"x\"}"}))
	snapshot, err := j.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if got := field(object(blocks(object(snapshot.Items["m"]["item"])["content"])[0]), "text"); got != "before refresh" {
		t.Fatalf("lost prefix %q", got)
	}
	if got := field(snapshot.Tools["a:tool-1"], "arguments"); got != "{\"q\":\"x\"}" {
		t.Fatalf("bad arguments %q", got)
	}
	appendEvent("4", "tool.completed", map[string]any{"execution_id": "a", "tool_call_id": "tool-1", "output": []any{"ok"}})
	events, _, more, err := j.Events(ctx, snapshot.AsOf, 10)
	if err != nil || more || len(events) != 1 || events[0].Type != "tool.completed" {
		t.Fatalf("replay %v %v %v", events, more, err)
	}
	if _, err = Position(uuid.New(), snapshot.AsOf); err == nil {
		t.Fatal("accepted cross-invocation cursor")
	}
}

func TestFinalizationUpdatesOneItemAndSuspensionPreservesWaitingTool(t *testing.T) {
	state := Snapshot{Items: map[string]map[string]any{}, Tools: map[string]map[string]any{}}
	event := func(kind string, data map[string]any) { apply(&state, &PublicEvent{Type: kind, Data: data}) }
	event("tool.requested", map[string]any{"execution_id": "a", "tool_call_id": "call"})
	event("tool.waiting", map[string]any{"execution_id": "a", "tool_call_id": "call", "status": "waiting"})
	event("execution.ended", map[string]any{"execution_id": "a", "status": "suspended"})
	if field(state.Tools["a:call"], "status") != "waiting" {
		t.Fatal(state.Tools)
	}
	event("item.completed", map[string]any{"item_id": "m", "item": map[string]any{"text": "draft"}})
	event("item.finalized", map[string]any{"item_id": "m", "final_output": true, "item": map[string]any{"text": "final"}})
	if len(state.Items) != 1 || field(object(state.Items["m"]["item"]), "text") != "final" {
		t.Fatal(state.Items)
	}
}
func TestUncommittedSourceIndexCannotAliasAnotherEvent(t *testing.T) {
	ctx := context.Background()
	st, _ := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	defer st.Close()
	j := Journal{Store: st, Tenant: "t", InvocationID: uuid.New()}
	id := uuid.NewSHA1(j.InvocationID, []byte("orphan")).String()
	raw, _ := json.Marshal(sourceReceipt{Sequence: 1})
	_, _ = st.KV().Put(ctx, "t", j.path(), "source/"+id, raw)
	if _, err := j.Append(ctx, "other", "step.updated", map[string]any{"step_id": "b"}); err != nil {
		t.Fatal(err)
	}
	event, err := j.Append(ctx, "orphan", "step.updated", map[string]any{"step_id": "a"})
	if err != nil {
		t.Fatal(err)
	}
	if event.Sequence != 2 {
		t.Fatalf("orphan aliased committed event: %+v", event)
	}
}
func TestSchemasSupportNestedConstraintsAndBlockRemoteReferences(t *testing.T) {
	schema := json.RawMessage(`{"type":"object","properties":{"n":{"type":"integer","minimum":2}},"required":["n"],"additionalProperties":false}`)
	if err := Validate(schema, map[string]any{"n": float64(1)}); err == nil {
		t.Fatal("minimum not enforced")
	}
	if err := Validate(schema, map[string]any{"n": float64(2)}); err != nil {
		t.Fatal(err)
	}
	if err := CheckSchema(json.RawMessage(`{"$ref":"https://example.invalid/schema"}`)); err == nil {
		t.Fatal("remote schema allowed")
	}
}

func TestRemoteToolOperationsKeepOneModelToolAndBoundItsPreview(t *testing.T) {
	state := Snapshot{Items: map[string]map[string]any{}, Tools: map[string]map[string]any{}}
	event := func(kind string, data map[string]any) {
		data["execution_id"] = "attempt"
		data["tool_call_id"] = "call"
		apply(&state, &PublicEvent{Type: kind, Data: data})
	}
	event("tool.requested", map[string]any{"name": "execute"})
	event("tool.waiting", map[string]any{"status": "waiting"})
	for i := 0; i < 8; i++ {
		event("tool.delta", map[string]any{"task_id": "operation", "operation_name": "sandbox.exec", "source_seq": i + 1, "output": []any{map[string]any{"type": "text", "text": strings.Repeat("x", 16384)}}})
	}
	tool := state.Tools["attempt:call"]
	if len(state.Tools) != 1 || field(tool, "name") != "execute" || field(tool, "status") != "waiting" || tool["progress_truncated"] != true || len(field(object(blocks(tool["progress"])[0]), "text")) != 65536 {
		t.Fatalf("unbounded or incorrect model tool: %+v", tool)
	}
	operation := object(object(tool["operations"])["operation"])
	if operation["output"] != nil || operation["source_seq"] != 8 {
		t.Fatal(operation)
	}
	event("tool.operation.completed", map[string]any{"task_id": "operation", "name": "sandbox.exec", "status": "succeeded"})
	if field(state.Tools["attempt:call"], "status") != "waiting" {
		t.Fatal(state.Tools)
	}
	event("tool.completed", map[string]any{"status": "completed", "output": []any{map[string]any{"type": "text", "text": "actual result"}}})
	event("tool.progress", map[string]any{"task_id": "operation", "status": "running"})
	if field(state.Tools["attempt:call"], "status") != "completed" || len(blocks(state.Tools["attempt:call"]["progress"])) != 1 {
		t.Fatal(state.Tools)
	}
}
