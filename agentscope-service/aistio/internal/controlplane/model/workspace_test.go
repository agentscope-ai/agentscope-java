// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.
package model

import (
	"encoding/json"
	"testing"
)

func TestWorkspaceConsumerRequiresExplicitCapability(t *testing.T) {
	for raw, want := range map[string]bool{`["workspace-definition-v1"]`: true, `{"workspace-definition-v1":true}`: true, `["no-workspace-definition-v1"]`: false, `{"workspace-definition-v1":false}`: false, `{}`: false, `null`: false} {
		if got := ConsumesWorkspaceDefinition(json.RawMessage(raw)); got != want {
			t.Errorf("%s: %v", raw, got)
		}
	}
}
