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
