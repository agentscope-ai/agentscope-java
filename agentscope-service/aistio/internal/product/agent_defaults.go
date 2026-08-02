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
