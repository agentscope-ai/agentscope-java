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

// Config holds runtime settings for the product control plane module.
// The module does not own an HTTP listener; control-plane mounts it onto the
// shared REST server.
type Config struct {
	SchemaMode            string // validate for service replicas; migrate only for explicit initialization
	LocalDev              bool   // MANAGED_AGENTS_LOCAL_DEV: local, unauthenticated single-user mode
	OAuthPublicURL        string // MANAGED_AGENTS_OAUTH_PUBLIC_URL: public console origin for OAuth callbacks
	DSN                   string
	JWTSecret             string
	InternalToken         string
	WorkspaceRoot         string
	SeedUsers             bool
	BootstrapAdmin        string // CONTROL_PLANE_BOOTSTRAP_ADMIN: initial username
	BootstrapPassword     string // CONTROL_PLANE_BOOTSTRAP_PASSWORD: used only on an empty database
	AllowLocalEnvironment bool   // MANAGED_AGENTS_ALLOW_LOCAL_ENVIRONMENT
	DataURL               string // MANAGED_AGENTS_AGENT_HARNESS_RUNTIME_URL
	AgentServiceURL       string // MANAGED_AGENTS_AGENT_SERVICE_URL: native Managed admission and queries
	VaultMasterKey        string // MANAGED_AGENTS_VAULT_MASTER_KEY (optional)
}

// DefaultConfig returns development defaults.
func DefaultConfig() Config {
	return Config{
		DSN:           "postgres://managed-agents:builder@localhost:5432/builder?sslmode=disable",
		JWTSecret:     "builder-default-dev-secret-change-in-production-32chars",
		InternalToken: "builder-internal-dev-token",
		WorkspaceRoot: "./data/workspaces",
		SeedUsers:     true,
		// Local environments execute shell commands in the data-plane process.
		// This development default is deliberately overridden to false by control-plane;
		// supported local launchers opt in explicitly.
		AllowLocalEnvironment: true,
	}
}
