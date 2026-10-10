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
	"fmt"
	"io"
	"path"
	"regexp"
	"sort"
	"strconv"
	"strings"

	"gopkg.in/yaml.v3"
)

// NativeSubagent is the subset that can be faithfully passed to a CLI. In
// particular, a shared process workspace is not an isolated Harness workspace.
type NativeSubagent struct {
	Name            string   `json:"-" yaml:"-"`
	Description     string   `json:"description" yaml:"description"`
	Prompt          string   `json:"prompt" yaml:"-"`
	Model           string   `json:"model,omitempty" yaml:"model"`
	Tools           []string `json:"tools,omitempty" yaml:"tools"`
	DisallowedTools []string `json:"disallowedTools,omitempty" yaml:"-"`
	MaxTurns        *int     `json:"maxTurns,omitempty" yaml:"maxIters"`
	Workspace       struct {
		Mode string `yaml:"mode"`
		Path string `yaml:"path"`
	} `json:"-" yaml:"workspace"`
}

var subagentName = regexp.MustCompile(`^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$`)

// NativeSubagents validates before launching a provider, so unsupported limits
// never silently disappear during conversion. Omitted workspace mode retains
// the portable definition's isolated default and therefore requires a change.
func NativeSubagents(definition *AgentDefinition, runtime string) ([]NativeSubagent, error) {
	if definition == nil {
		return nil, nil
	}
	files := make([]string, 0)
	for filename := range definition.Files {
		if strings.HasPrefix(filename, "subagents/") {
			files = append(files, filename)
		}
	}
	sort.Strings(files)
	var result []NativeSubagent
	for _, filename := range files {
		name := strings.TrimSuffix(strings.TrimPrefix(filename, "subagents/"), ".md")
		if !strings.HasSuffix(filename, ".md") || !subagentName.MatchString(name) || path.Base(filename) != name+".md" {
			return nil, fmt.Errorf("invalid portable subagent filename %q", filename)
		}
		content := strings.ReplaceAll(definition.Files[filename], "\r\n", "\n")
		if !strings.HasPrefix(content, "---\n") {
			return nil, fmt.Errorf("subagent %q requires YAML frontmatter", name)
		}
		parts := strings.SplitN(content[4:], "\n---", 2)
		if len(parts) != 2 || (parts[1] != "" && !strings.HasPrefix(parts[1], "\n")) {
			return nil, fmt.Errorf("subagent %q has invalid YAML frontmatter", name)
		}
		var item NativeSubagent
		decoder := yaml.NewDecoder(strings.NewReader(parts[0]))
		decoder.KnownFields(true)
		if err := decoder.Decode(&item); err != nil {
			return nil, fmt.Errorf("subagent %q: %w", name, err)
		}
		var extra any
		if err := decoder.Decode(&extra); err != io.EOF {
			return nil, fmt.Errorf("subagent %q requires one YAML document", name)
		}
		item.Name, item.Prompt = name, strings.TrimSpace(parts[1])
		if strings.TrimSpace(item.Description) == "" {
			return nil, fmt.Errorf("subagent %q requires a description", name)
		}
		if item.Prompt == "" {
			item.Prompt = item.Description
		}
		if item.Workspace.Mode != "shared" || item.Workspace.Path != "" {
			return nil, fmt.Errorf("%s subagent %q requires workspace.mode=shared with no workspace.path; isolated Harness workspaces cannot be mapped to this provider", runtime, name)
		}
		if item.MaxTurns != nil && *item.MaxTurns <= 0 {
			return nil, fmt.Errorf("subagent %q maxIters must be positive", name)
		}
		switch runtime {
		case "codex":
			if len(item.Tools) > 0 || item.MaxTurns != nil {
				return nil, fmt.Errorf("Codex subagent %q does not support portable tools or maxIters limits; remove these limits or use a Managed runtime", name)
			}
		case "qodercli":
			for index, tool := range item.Tools {
				mapped, ok := qoderSubagentTools[tool]
				if !ok {
					return nil, fmt.Errorf("Qoder subagent %q has unsupported tool %q", name, tool)
				}
				item.Tools[index] = mapped
			}
		default:
			return nil, fmt.Errorf("%s does not support portable subagents", runtime)
		}
		result = append(result, item)
	}
	return result, nil
}

var qoderSubagentTools = map[string]string{
	"read": "Read", "read_file": "Read", "Read": "Read",
	"write": "Write", "write_file": "Write", "Write": "Write",
	"edit": "Edit", "Edit": "Edit", "shell": "Bash", "bash": "Bash", "Bash": "Bash",
	"grep": "Grep", "Grep": "Grep", "glob": "Glob", "Glob": "Glob",
	"web_fetch": "WebFetch", "WebFetch": "WebFetch", "web_search": "WebSearch", "WebSearch": "WebSearch",
	"Agent": "Agent",
}

func QoderSubagentsJSON(definition *AgentDefinition) (string, error) {
	items, err := NativeSubagents(definition, "qodercli")
	if err != nil || len(items) == 0 {
		return "", err
	}
	agents := make(map[string]NativeSubagent, len(items))
	for _, item := range items {
		agents[item.Name] = item
	}
	data, err := json.Marshal(agents)
	return string(data), err
}

var cliVersion = regexp.MustCompile(`(?:^|\s)([0-9]+)\.([0-9]+)\.([0-9]+)(?:$|[-+\s])`)

// Floors are the CLI contracts verified for this adapter, rather than a guess
// about the first historical release containing similarly named features.
func RequireSubagentVersion(version string, major, minor, patch int) error {
	match := cliVersion.FindStringSubmatch(version)
	if len(match) == 4 {
		for index, required := range []int{major, minor, patch} {
			actual, err := strconv.Atoi(match[index+1])
			if err != nil {
				break
			}
			if actual > required {
				return nil
			}
			if actual < required {
				break
			}
			if index == 2 {
				return nil
			}
		}
	}
	return fmt.Errorf("Workspace subagents require CLI %d.%d.%d or newer; detected %q", major, minor, patch, version)
}
