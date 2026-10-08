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

package qoder

import (
	"context"
	"encoding/json"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
	"io"
	"slices"
	"strings"
	"testing"
)

func TestChildQoderResultDoesNotFinishParent(t *testing.T) {
	input := strings.Join([]string{
		`{"type":"system","subtype":"init","session_id":"parent"}`,
		`{"type":"assistant","session_id":"child","parent_tool_use_id":"delegate","message":{"content":[{"type":"text","text":"child output"}]}}`,
		`{"type":"result","session_id":"child","parent_tool_use_id":"delegate","is_error":true,"result":"child failed"}`,
		`{"type":"result","session_id":"parent","result":"parent reviewed child result"}`,
	}, "\n")
	var childEvents int
	result := &provider.Result{}
	if err := consumeStreamJSON(context.Background(), strings.NewReader(input), io.Discard, result, func(event provider.Event) error {
		if event.ProviderSessionID == "child" {
			childEvents++
		}
		return nil
	}, nil); err != nil {
		t.Fatal(err)
	}
	if result.ProviderSessionID != "parent" || result.Output != "parent reviewed child result" || childEvents != 2 {
		t.Fatalf("result=%+v childEvents=%d", result, childEvents)
	}
}

func TestSubagentsCannotExpandParentToolExposure(t *testing.T) {
	request := provider.Request{Definition: &provider.AgentDefinition{Tools: json.RawMessage(`[{"configs":[{"name":"Read","enabled":true},{"name":"Agent","enabled":true}]}]`)}}
	encoded := `{"reviewer":{"description":"Review","prompt":"Review only"}}`
	output, err := constrainSubagentTools(encoded, request, configuration{DisallowedTools: []string{"Bash"}})
	if err != nil {
		t.Fatal(err)
	}
	var agents map[string]provider.NativeSubagent
	if err := json.Unmarshal([]byte(output), &agents); err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(agents["reviewer"].Tools, []string{"Read", "Agent"}) || !slices.Contains(agents["reviewer"].DisallowedTools, "Bash") {
		t.Fatalf("policy not inherited: %s", output)
	}
	if _, err := constrainSubagentTools(`{"reviewer":{"tools":["Write"]}}`, request, configuration{}); err == nil {
		t.Fatal("subagent expanded parent exposure")
	}
	if _, err := constrainSubagentTools(encoded, request, configuration{DisallowedTools: []string{"Agent"}}); err == nil {
		t.Fatal("ignored disabled delegation tool")
	}
}
