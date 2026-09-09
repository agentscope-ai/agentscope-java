// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package model

import "encoding/json"

// ConsumesWorkspaceDefinition accepts the SDK registration capability list and registry capability maps.
func ConsumesWorkspaceDefinition(raw json.RawMessage) bool {
	var names []string
	if json.Unmarshal(raw, &names) == nil {
		for _, name := range names {
			if name == "workspace-definition-v1" {
				return true
			}
		}
	}
	var flags map[string]any
	return json.Unmarshal(raw, &flags) == nil && flags["workspace-definition-v1"] == true
}
