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

import (
	"encoding/json"
	"fmt"
	"strings"
)

type DefinitionCapability struct {
	Name      string `json:"name"`
	Mode      string `json:"mode"`
	Supported bool   `json:"supported"`
	Requested bool   `json:"requested"`
	Target    string `json:"target,omitempty"`
	Reason    string `json:"reason,omitempty"`
}

func DefinitionCapabilities(definition *AgentDefinition, descriptor Descriptor) []DefinitionCapability {
	requested := map[string]bool{}
	if definition != nil {
		requested["instructions"] = definition.System != ""
		requested["tools"] = len(definition.Tools) > 0 && string(definition.Tools) != "null" && string(definition.Tools) != "[]"
		// MCP registration is separate from portable built-in tool policies.
		// An MCP-only Workspace must not require a built-in policy adapter.
		var toolsets []struct {
			Type    string            `json:"type"`
			Configs []json.RawMessage `json:"configs"`
		}
		if json.Unmarshal(definition.Tools, &toolsets) == nil {
			requested["tools"] = false
			for _, toolset := range toolsets {
				if toolset.Type != "mcp_toolset" && (toolset.Type != "agent_toolset" || len(toolset.Configs) > 0) {
					requested["tools"] = true
				}
			}
		}
		requested["mcp"] = len(definition.MCPServers) > 0 && string(definition.MCPServers) != "null" && string(definition.MCPServers) != "[]"
		for path := range definition.Files {
			if strings.HasPrefix(path, "skills/") {
				requested["skills"] = true
			}
			if strings.HasPrefix(path, "subagents/") {
				requested["subagents"] = true
			}
		}
	}
	out := []DefinitionCapability{}
	for _, item := range []struct {
		name string
		cap  Capability
	}{{"instructions", descriptor.Instructions}, {"skills", descriptor.Skills}, {"tools", descriptor.Tools}, {"mcp", descriptor.MCP}, {"subagents", descriptor.Subagents}} {
		mode := item.cap.Mode
		if !item.cap.Supported {
			mode = "unsupported"
		}
		capability := DefinitionCapability{Name: item.name, Mode: mode, Supported: item.cap.Supported, Requested: requested[item.name], Target: item.cap.Target}
		if item.name == "tools" && !item.cap.Supported && requested[item.name] && descriptor.Runtime == "codex" {
			capability.Reason = "Codex cannot enforce Managed built-in tool settings. Remove the built-in tool policies from this Workspace or use a Managed Agent; configure Codex sandbox and approval settings in its Runtime Profile. MCP connections remain supported."
		}
		if item.name == "subagents" && item.cap.Supported && requested[item.name] {
			if _, err := NativeSubagents(definition, descriptor.Runtime); err != nil {
				capability.Supported, capability.Mode, capability.Reason = false, "unsupported", err.Error()
			}
		}
		out = append(out, capability)
	}
	return out
}

func ValidateDefinition(definition *AgentDefinition, descriptor Descriptor) error {
	for _, cap := range DefinitionCapabilities(definition, descriptor) {
		if cap.Requested && !cap.Supported {
			if cap.Reason != "" {
				return fmt.Errorf("%s", cap.Reason)
			}
			return fmt.Errorf("%s does not support Workspace capability %s", descriptor.DisplayName, cap.Name)
		}
	}
	return nil
}
