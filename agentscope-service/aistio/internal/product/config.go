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
// The module does not own an HTTP listener; aistiod mounts it onto the
// shared REST server.
type Config struct {
	DSN            string
	JWTSecret      string
	InternalToken  string
	WorkspaceRoot  string
	SeedUsers      bool
	DataURL        string // BUILDER_DATA_URL
	VaultMasterKey string // BUILDER_VAULT_MASTER_KEY (optional)
}

// DefaultConfig returns development defaults.
func DefaultConfig() Config {
	return Config{
		DSN:           "postgres://builder:builder@localhost:5432/builder?sslmode=disable",
		JWTSecret:     "builder-default-dev-secret-change-in-production-32chars",
		InternalToken: "builder-internal-dev-token",
		WorkspaceRoot: "./data/workspaces",
		SeedUsers:     true,
	}
}
