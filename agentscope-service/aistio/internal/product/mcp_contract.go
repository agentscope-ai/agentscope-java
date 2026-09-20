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

package product

import (
	"encoding/json"
	"fmt"
	"net/url"
	"regexp"
	"strings"
)

var mcpConnectionName = regexp.MustCompile(`^[A-Za-z0-9_-]{1,64}$`)

func validateManagedTools(tools, servers any) error {
	var connections []struct{ Name, URL, Transport, Command string }
	raw, err := json.Marshal(servers)
	if err != nil || json.Unmarshal(raw, &connections) != nil {
		return fmt.Errorf("mcpServers must be an array of server declarations")
	}
	names := map[string]bool{}
	for _, server := range connections {
		if !mcpConnectionName.MatchString(server.Name) || strings.Contains(server.Name, "__") || names[server.Name] {
			return fmt.Errorf("MCP names must be unique identifiers without double underscores")
		}
		names[server.Name] = true
		transport := strings.ToLower(server.Transport)
		if transport == "" {
			if server.URL != "" {
				transport = "http"
			} else {
				transport = "stdio"
			}
		}
		switch transport {
		case "http", "streamable-http", "streamablehttp", "sse":
			endpoint, err := url.Parse(server.URL)
			if err != nil || (endpoint.Scheme != "http" && endpoint.Scheme != "https") || endpoint.Host == "" || endpoint.User != nil || endpoint.Fragment != "" {
				return fmt.Errorf("MCP %s requires an HTTP(S) endpoint without userinfo or fragment", server.Name)
			}
		case "stdio":
			if strings.TrimSpace(server.Command) == "" {
				return fmt.Errorf("MCP %s requires a stdio command", server.Name)
			}
		default:
			return fmt.Errorf("MCP %s uses an unsupported transport", server.Name)
		}
	}
	type policy struct {
		Type string `json:"type"`
	}
	type config struct {
		Name             string  `json:"name"`
		PermissionPolicy *policy `json:"permissionPolicy"`
	}
	var toolsets []struct {
		Type          string   `json:"type"`
		MCPServerName string   `json:"mcpServerName"`
		DefaultConfig *config  `json:"defaultConfig"`
		Configs       []config `json:"configs"`
	}
	raw, err = json.Marshal(tools)
	if err != nil || json.Unmarshal(raw, &toolsets) != nil {
		return fmt.Errorf("tools must be an array of toolsets")
	}
	seen := map[string]bool{}
	for _, toolset := range toolsets {
		if toolset.Type != "agent_toolset" && toolset.Type != "mcp_toolset" {
			return fmt.Errorf("unsupported toolset type: %s", toolset.Type)
		}
		key := toolset.Type + ":" + toolset.MCPServerName
		if seen[key] {
			return fmt.Errorf("duplicate toolset: %s", key)
		}
		seen[key] = true
		if toolset.Type == "mcp_toolset" && !names[toolset.MCPServerName] {
			return fmt.Errorf("MCP toolset references an undeclared server")
		}
		configs := append([]config{}, toolset.Configs...)
		if toolset.DefaultConfig != nil {
			configs = append(configs, *toolset.DefaultConfig)
		}
		for _, entry := range configs {
			if entry.PermissionPolicy != nil {
				switch entry.PermissionPolicy.Type {
				case "always_allow", "always_ask", "deny":
				default:
					return fmt.Errorf("unsupported tool permission policy")
				}
			}
		}
	}
	return nil
}

func validateMemoryAccessConfig(config any) error {
	if config == nil {
		return nil
	}
	raw, err := json.Marshal(config)
	if err != nil {
		return fmt.Errorf("invalid environment config")
	}
	var parsed struct {
		MemoryAccess map[string]string `json:"memoryAccess"`
	}
	if json.Unmarshal(raw, &parsed) != nil {
		return fmt.Errorf("invalid environment memoryAccess")
	}
	for _, access := range parsed.MemoryAccess {
		if access != "read_only" && access != "read_write" {
			return fmt.Errorf("memoryAccess must be read_only or read_write")
		}
	}
	return nil
}
