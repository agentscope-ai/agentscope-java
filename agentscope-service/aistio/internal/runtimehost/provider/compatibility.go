// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package provider

import (
	"fmt"
	"strings"
)

type DefinitionCapability struct {
	Name      string `json:"name"`
	Mode      string `json:"mode"`
	Supported bool   `json:"supported"`
	Requested bool   `json:"requested"`
	Target    string `json:"target,omitempty"`
}

func DefinitionCapabilities(definition *AgentDefinition, descriptor Descriptor) []DefinitionCapability {
	requested := map[string]bool{}
	if definition != nil {
		requested["instructions"] = definition.System != ""
		requested["tools"] = len(definition.Tools) > 0 && string(definition.Tools) != "null" && string(definition.Tools) != "[]"
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
		out = append(out, DefinitionCapability{Name: item.name, Mode: mode, Supported: item.cap.Supported, Requested: requested[item.name], Target: item.cap.Target})
	}
	return out
}

func ValidateDefinition(definition *AgentDefinition, descriptor Descriptor) error {
	for _, cap := range DefinitionCapabilities(definition, descriptor) {
		if cap.Requested && !cap.Supported {
			return fmt.Errorf("%s does not support Workspace capability %s", descriptor.DisplayName, cap.Name)
		}
	}
	return nil
}
