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
