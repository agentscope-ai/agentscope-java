// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package product

import (
	"context"
	"fmt"
)

// PersonalSessionResources returns only authorization references for the owner.
// Session history remains independently protected by its existing owner check.
func (s *Server) PersonalSessionResources(ctx context.Context, user, id string) (string, []string, error) {
	session, err := s.loadSession(ctx, id)
	if err != nil || session.OwnerID != user {
		return "", nil, fmt.Errorf("session unavailable")
	}
	if session.AgentOwnerID != nil && *session.AgentOwnerID != "" && *session.AgentOwnerID != user {
		return "", nil, fmt.Errorf("session Agent is outside the personal namespace")
	}
	deps := []string{}
	if session.EnvironmentID != "" {
		deps = append(deps, "environment:"+session.EnvironmentID)
	}
	for _, id := range parseStringSlice(deref(session.MemoryStoreIDsJSON)) {
		deps = append(deps, "memory:"+id)
	}
	for _, id := range parseStringSlice(deref(session.VaultIDsJSON)) {
		deps = append(deps, "vault:"+id)
	}
	return session.AgentID, deps, nil
}
