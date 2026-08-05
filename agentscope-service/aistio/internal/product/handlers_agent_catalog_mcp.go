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

import "github.com/gin-gonic/gin"

// builtinMcpCatalog lists curated remote MCP servers offered as starting points
// in the console. Entries carry no credentials; secrets come from vaults at
// session time via requiredEnv key names.
var builtinMcpCatalog = []gin.H{
	{
		"id":          "github",
		"name":        "GitHub",
		"description": "Issues, pull requests, and repository metadata via the GitHub MCP server.",
		"transport":   "streamable-http",
		"url":         "https://api.githubcopilot.com/mcp/",
		"docsUrl":     "https://github.com/github/github-mcp-server",
		"requiredEnv": []string{"GITHUB_PERSONAL_ACCESS_TOKEN"},
	},
	{
		"id":          "fetch",
		"name":        "Fetch (HTTP)",
		"description": "HTTP fetch helper for retrieving URL content (stdio MCP). Prefer builtin web_fetch when available.",
		"transport":   "stdio",
		"command":     "npx",
		"args":        []string{"-y", "@modelcontextprotocol/server-fetch"},
		"docsUrl":     "https://github.com/modelcontextprotocol/servers",
		"requiredEnv": []string{},
	},
	{
		"id":          "brave-search",
		"name":        "Brave Search",
		"description": "Web search via Brave Search MCP server.",
		"transport":   "stdio",
		"command":     "npx",
		"args":        []string{"-y", "@modelcontextprotocol/server-brave-search"},
		"docsUrl":     "https://github.com/modelcontextprotocol/servers",
		"requiredEnv": []string{"BRAVE_API_KEY"},
	},
	{
		"id":          "filesystem",
		"name":        "Filesystem (stdio)",
		"description": "Local filesystem tools via the official MCP filesystem server. Prefer builtin filesystem tools in sandboxed Environments.",
		"transport":   "stdio",
		"command":     "npx",
		"args":        []string{"-y", "@modelcontextprotocol/server-filesystem", "/workspace"},
		"docsUrl":     "https://github.com/modelcontextprotocol/servers",
		"requiredEnv": []string{},
		"environmentHint": "local",
	},
}
