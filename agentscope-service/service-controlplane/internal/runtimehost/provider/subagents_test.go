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

package provider

import (
	"encoding/json"
	"strings"
	"testing"
)

func TestQoderSubagentsPreservePromptModelAndLimits(t *testing.T) {
	definition := &AgentDefinition{Files: map[string]string{"subagents/reviewer.md": "---\ndescription: 'Review: API'\nmodel: example-model\nworkspace:\n  mode: shared\nmaxIters: 4\ntools: [read_file, grep]\n---\n\nKeep quotes \" and newlines.\nReview the API.\n"}}
	data, err := QoderSubagentsJSON(definition)
	if err != nil {
		t.Fatal(err)
	}
	var agents map[string]NativeSubagent
	if err := json.Unmarshal([]byte(data), &agents); err != nil {
		t.Fatal(err)
	}
	reviewer := agents["reviewer"]
	if reviewer.Description != "Review: API" || reviewer.Model != "example-model" || reviewer.MaxTurns == nil || *reviewer.MaxTurns != 4 || strings.Join(reviewer.Tools, ",") != "Read,Grep" || reviewer.Prompt != "Keep quotes \" and newlines.\nReview the API." {
		t.Fatalf("lost subagent configuration: %+v", reviewer)
	}
}

func TestSubagentsRejectUnsupportedFieldsBeforeExecution(t *testing.T) {
	base := "---\ndescription: review\nworkspace:\n  mode: shared\n%s\n---\nReview files."
	for _, tc := range []struct{ name, runtime, content, reason string }{
		{"isolated", "codex", "---\ndescription: review\nworkspace:\n  mode: isolated\n---\nreview", "workspace.mode=shared"},
		{"unknown field", "qodercli", strings.Replace(base, "%s", "permissionMode: bypassPermissions", 1), "field permissionMode not found"},
		{"Codex tools", "codex", strings.Replace(base, "%s", "tools: [Read]", 1), "tools or maxIters"},
		{"Codex limit", "codex", strings.Replace(base, "%s", "maxIters: 2", 1), "tools or maxIters"},
		{"Qoder tool", "qodercli", strings.Replace(base, "%s", "tools: [unknown_tool]", 1), "unsupported tool"},
		{"negative limit", "qodercli", strings.Replace(base, "%s", "maxIters: -1", 1), "must be positive"},
	} {
		t.Run(tc.name, func(t *testing.T) {
			definition := &AgentDefinition{Files: map[string]string{"subagents/reviewer.md": tc.content}}
			descriptor := Descriptor{Runtime: tc.runtime, Subagents: Capability{Supported: true}}
			if err := ValidateDefinition(definition, descriptor); err == nil || !strings.Contains(err.Error(), tc.reason) {
				t.Fatalf("error=%v", err)
			}
		})
	}
}

func TestSubagentsRejectPathTraversalAndUnknownVersions(t *testing.T) {
	definition := &AgentDefinition{Files: map[string]string{"subagents/../escape.md": "review"}}
	if _, err := NativeSubagents(definition, "codex"); err == nil {
		t.Fatal("accepted path traversal")
	}
	for _, version := range []string{"codex-cli 0.153.4", "0.154.0", "1.0.0"} {
		if err := RequireSubagentVersion(version, 0, 153, 4); err != nil {
			t.Fatal(err)
		}
	}
	for _, version := range []string{"codex-cli 0.153.3", "0.90.0", "unknown"} {
		if err := RequireSubagentVersion(version, 0, 153, 4); err == nil {
			t.Fatalf("accepted %q", version)
		}
	}
}
