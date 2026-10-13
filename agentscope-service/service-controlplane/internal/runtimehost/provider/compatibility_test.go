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

// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package provider

import "testing"

func TestWorkspaceUnsupportedCapabilitiesFailExplicitly(t *testing.T) {
	descriptor := Descriptor{DisplayName: "limited", Instructions: Capability{Supported: true}, Skills: Capability{Supported: true, Mode: "context-directory"}}
	def := &AgentDefinition{System: "review", Files: map[string]string{"skills/review/SKILL.md": "review"}}
	if err := ValidateDefinition(def, descriptor); err != nil {
		t.Fatal(err)
	}
	def.Files["subagents/review.md"] = "worker"
	if err := ValidateDefinition(def, descriptor); err == nil {
		t.Fatal("Subagent file mistaken for executable native subagent")
	}
	delete(def.Files, "subagents/review.md")
	def.MCPServers = []byte(`[{"name":"docs","url":"https://example.test"}]`)
	if err := ValidateDefinition(def, descriptor); err == nil {
		t.Fatal("unsupported MCP accepted")
	}
}

func TestMCPOnlyDefinitionDoesNotRequireBuiltinPolicySupport(t *testing.T) {
	descriptor := Descriptor{Runtime: "codex", DisplayName: "Codex", MCP: Capability{Supported: true}}
	def := &AgentDefinition{Tools: []byte(`[{"type":"mcp_toolset","mcpServerName":"docs"},{"type":"agent_toolset","configs":[]}]`)}
	if err := ValidateDefinition(def, descriptor); err != nil {
		t.Fatal(err)
	}
	for _, enabled := range []string{"true", "false"} {
		def.Tools = []byte(`[{"type":"agent_toolset","configs":[{"name":"bash","enabled":` + enabled + `}]}]`)
		if err := ValidateDefinition(def, descriptor); err == nil {
			t.Fatal("Codex silently accepted unenforceable tool policy")
		}
	}
}
