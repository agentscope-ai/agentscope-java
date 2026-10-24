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
	"errors"
	"fmt"
	"strings"
	"testing"

	model "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/controlplane/model"
	"github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store"
	_ "github.com/agentscope-ai/agentscope-java/agentscope-service/service-controlplane/v2/internal/store/memory"
	"github.com/google/uuid"
)

func TestResultMappingProjectsBeforeSchemaValidation(t *testing.T) {
	ep := &model.Endpoint{ResultMapping: json.RawMessage(`{"answer":"/steps/0/a~1b","count":"/big","whole":""}`), OutputSchema: json.RawMessage(`{"type":"object","required":["answer","count"],"properties":{"answer":{"type":"string"}}}`)}
	inv := &model.EndpointInvocation{Status: model.EndpointInvocationPartialSucceeded}
	CompleteResult(ep, inv, json.RawMessage(`{"steps":[{"a/b":"ok"}],"big":9007199254740993}`))
	if inv.Status != model.EndpointInvocationPartialSucceeded || !strings.Contains(string(inv.Result), `9007199254740993`) {
		t.Fatalf("mapping changed result: %+v", inv)
	}
	CompleteResult(ep, inv, json.RawMessage(`{"steps":[]}`))
	if inv.ErrorCode != "output_mapping_failed" {
		t.Fatalf("missing pointer accepted: %+v", inv)
	}
	if err := CheckResultMapping(json.RawMessage(`{"bad":"/x~2"}`)); err == nil {
		t.Fatal("invalid pointer escape accepted")
	}
}
func TestFreezeIncludesNestedWorkflowAgentsAndTeams(t *testing.T) {
	ctx := context.Background()
	st, _ := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	defer st.Close()
	agent, member := uuid.New(), uuid.New()
	team, err := st.Collaboration().CreateTeam(ctx, &model.CollaborationTeam{Tenant: "t", Namespace: "n", Name: "team", LeaderAgentRef: agent.String(), Members: []model.CollaborationTeamMember{{AgentRef: member.String(), Role: "researcher"}}})
	if err != nil {
		t.Fatal(err)
	}
	definition, err := st.Orchestration().CreateDefinition(ctx, &model.OrchestrationDefinition{Tenant: "t", Namespace: "n", Name: "flow"})
	if err != nil {
		t.Fatal(err)
	}
	child, err := st.Orchestration().CreateRevision(ctx, &model.OrchestrationRevision{Tenant: "t", Namespace: "n", DefinitionID: definition.ID, Spec: json.RawMessage(fmt.Sprintf(`{"nodes":[{"type":"team","teamRef":%q}]}`, team.ID.String()))})
	if err != nil {
		t.Fatal(err)
	}
	root, err := st.Orchestration().CreateRevision(ctx, &model.OrchestrationRevision{Tenant: "t", Namespace: "n", DefinitionID: definition.ID, Spec: json.RawMessage(fmt.Sprintf(`{"nodes":[{"type":"subrun","definitionRevisionId":%q}]}`, child.ID.String()))})
	if err != nil {
		t.Fatal(err)
	}
	raw, err := Freeze(ctx, st, &model.Endpoint{ID: uuid.New(), Tenant: "t", Namespace: "n", TargetType: model.EndpointTargetOrchestrationRevision, TargetRef: root.ID})
	if err != nil {
		t.Fatal(err)
	}
	c, err := ReadContract(raw)
	if err != nil {
		t.Fatal(err)
	}
	if len(c.Agents) != 2 || len(c.Teams) != 1 || len(c.Revisions) != 2 {
		t.Fatalf("incomplete dependency snapshot: %s", raw)
	}
}
func TestJournalRetentionKeepsSnapshotAndDedup(t *testing.T) {
	ctx := context.Background()
	st, _ := store.Open(ctx, store.Config{Driver: store.DriverMemory})
	defer st.Close()
	j := Journal{Store: st, Tenant: "t", InvocationID: uuid.New()}
	for n := 0; n < 70; n++ {
		_, err := j.Append(ctx, fmt.Sprint(n), "item.delta", map[string]any{"item_id": "m", "content": []any{map[string]any{"type": "text", "text": "x"}}})
		if err != nil {
			t.Fatal(err)
		}
	}
	before, err := j.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	done, err := j.PruneEvents(ctx)
	if err != nil || !done {
		t.Fatalf("prune: %v %v", done, err)
	}
	if _, _, _, err = j.Events(ctx, "", 100); !errors.Is(err, ErrCursorExpired) {
		t.Fatalf("old cursor not expired: %v", err)
	}
	after, err := j.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	a, _ := json.Marshal(before)
	b, _ := json.Marshal(after)
	if string(a) != string(b) {
		t.Fatal("snapshot changed after prune")
	}
	duplicate, err := j.Append(ctx, "0", "item.delta", map[string]any{})
	if err != nil || duplicate.Sequence != 1 {
		t.Fatalf("dedup lost: %v %v", duplicate, err)
	}
	if events, _, _, err := j.Events(ctx, after.AsOf, 100); err != nil || len(events) != 0 {
		t.Fatalf("snapshot cursor unusable: %v %v", events, err)
	}
}
