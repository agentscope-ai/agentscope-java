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

import "strings"

// mergeSessionMounts applies Agent default mounts when the session caller omitted them.
//
// Environment: empty envID falls back to agent.defaultEnvironmentId (caller may still
// apply a further heuristic after this).
// Vault / memory: when provided==false, use agent defaults; when provided==true (including
// an explicit empty list), keep the caller's value.
func mergeSessionMounts(
	a agentRow,
	envID string,
	memIDs, vaultIDs []string,
	memProvided, vaultProvided bool,
) (string, []string, []string) {
	if strings.TrimSpace(envID) == "" {
		envID = deref(a.DefaultEnvironmentID)
	}
	if !memProvided {
		memIDs = parseStringSlice(deref(a.DefaultMemoryStoreIDsJSON))
	}
	if !vaultProvided {
		vaultIDs = parseStringSlice(deref(a.DefaultVaultIDsJSON))
	}
	if memIDs == nil {
		memIDs = []string{}
	}
	if vaultIDs == nil {
		vaultIDs = []string{}
	}
	return envID, memIDs, vaultIDs
}
