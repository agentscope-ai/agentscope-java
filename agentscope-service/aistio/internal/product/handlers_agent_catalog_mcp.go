package product

import "github.com/gin-gonic/gin"

// builtinMcpCatalog lists curated remote MCP servers offered as starting points
// in the console. Entries carry no credentials; secrets come from vaults at
// session time.
var builtinMcpCatalog = []gin.H{
	{
		"id":          "mcp-everything",
		"name":        "MCP Everything (reference)",
		"description": "Reference remote server used to validate MCP wiring end-to-end.",
		"transport":   "streamable-http",
		"url":         "https://example.invalid/mcp",
		"docsUrl":     "https://modelcontextprotocol.io",
		"requiredEnv": []string{},
	},
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
		"id":          "filesystem",
		"name":        "Filesystem (stdio)",
		"description": "Local filesystem tools via the official MCP filesystem server (stdio).",
		"transport":   "stdio",
		"command":     "npx",
		"args":        []string{"-y", "@modelcontextprotocol/server-filesystem", "/workspace"},
		"docsUrl":     "https://github.com/modelcontextprotocol/servers",
		"requiredEnv": []string{},
	},
	{
		"id":          "fetch",
		"name":        "Fetch",
		"description": "HTTP fetch helper for retrieving URL content into the agent context.",
		"transport":   "stdio",
		"command":     "npx",
		"args":        []string{"-y", "@modelcontextprotocol/server-fetch"},
		"docsUrl":     "https://github.com/modelcontextprotocol/servers",
		"requiredEnv": []string{},
	},
}
