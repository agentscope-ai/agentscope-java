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
	"encoding/json"
	"fmt"
	"github.com/spring-ai-alibaba/aistio/internal/runtimehost/provider"
	"slices"
)

var qoderToolAliases = map[string]string{
	"read": "Read", "read_file": "Read", "write": "Write", "write_file": "Write",
	"edit": "Edit", "shell": "Bash", "bash": "Bash", "grep": "Grep", "glob": "Glob",
	"web_fetch": "WebFetch", "web_search": "WebSearch",
}

func constrainSubagentTools(encoded string, request provider.Request, cfg configuration) (string, error) {
	allowed, denied := provider.DefinitionToolPolicy(request, qoderToolAliases)
	denied = provider.MergeUnique(denied, cfg.DisallowedTools)
	if (len(allowed) > 0 && !slices.Contains(allowed, "Agent")) || slices.Contains(denied, "Agent") {
		return "", fmt.Errorf("Workspace subagents require the Qoder Agent tool; the parent tool policy currently excludes it")
	}
	var agents map[string]provider.NativeSubagent
	if err := json.Unmarshal([]byte(encoded), &agents); err != nil {
		return "", err
	}
	for name, agent := range agents {
		if len(allowed) > 0 {
			if len(agent.Tools) == 0 {
				agent.Tools = allowed
			} else {
				for _, tool := range agent.Tools {
					if !slices.Contains(allowed, tool) {
						return "", fmt.Errorf("Qoder subagent %q requests tool %q outside the parent tool policy", name, tool)
					}
				}
			}
		}
		agent.DisallowedTools = provider.MergeUnique(agent.DisallowedTools, denied)
		agents[name] = agent
	}
	data, err := json.Marshal(agents)
	return string(data), err
}
