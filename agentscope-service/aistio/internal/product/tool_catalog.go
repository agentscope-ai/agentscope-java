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

// builtinToolCatalog is the product-facing builtin tool directory.
// Product ids follow Claude Managed Agents naming where useful; harnessName is
// the registered Harness tool id used at runtime.
var builtinToolCatalog = []gin.H{
	{"id": "bash", "harnessName": "execute", "description": "Execute a shell command", "group": "filesystem", "available": true},
	{"id": "read", "harnessName": "read_file", "description": "Read a file from the workspace", "group": "filesystem", "available": true},
	{"id": "write", "harnessName": "write_file", "description": "Write a file in the workspace", "group": "filesystem", "available": true},
	{"id": "edit", "harnessName": "edit_file", "description": "Edit a file via string replacement", "group": "filesystem", "available": true},
	{"id": "glob", "harnessName": "glob_files", "description": "Find files by glob pattern", "group": "filesystem", "available": true},
	{"id": "grep", "harnessName": "grep_files", "description": "Search file contents with regex", "group": "filesystem", "available": true},
	{"id": "web_fetch", "harnessName": "web_fetch", "description": "Fetch content from a URL", "group": "web", "available": true},
	{"id": "web_search", "harnessName": "web_search", "description": "Search the web for information", "group": "web", "available": true},
	{"id": "memory_save", "harnessName": "memory_save", "description": "Save a long-term memory fact", "group": "harness", "available": true},
	{"id": "memory_get", "harnessName": "memory_get", "description": "Get a memory entry", "group": "harness", "available": true},
	{"id": "memory_search", "harnessName": "memory_search", "description": "Search memory", "group": "harness", "available": true},
	{"id": "session_search", "harnessName": "session_search", "description": "Search prior sessions", "group": "harness", "available": true},
	{"id": "agent_spawn", "harnessName": "agent_spawn", "description": "Spawn a subagent", "group": "harness", "available": true},
	{"id": "task_list", "harnessName": "task_list", "description": "List background tasks", "group": "harness", "available": true},
}

// productToHarnessToolName maps product catalog ids (and legacy aliases) to harness tool names.
func productToHarnessToolName(id string) string {
	switch id {
	case "bash", "shell":
		return "execute"
	case "read", "read_file":
		return "read_file"
	case "write", "write_file":
		return "write_file"
	case "edit", "edit_file":
		return "edit_file"
	case "glob", "glob_files":
		return "glob_files"
	case "grep", "grep_files":
		return "grep_files"
	case "list_dir", "list_files":
		return "list_files"
	default:
		return id
	}
}
