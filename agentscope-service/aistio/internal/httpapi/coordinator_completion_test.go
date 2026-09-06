// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"encoding/json"
	"testing"
)

func TestCoordinatorOutcomeTextUsesReason(t *testing.T) {
	output := json.RawMessage(`{"reason":"all child outcomes were synthesized"}`)
	if got := coordinatorOutcomeText(output); got != "all child outcomes were synthesized" {
		t.Fatalf("coordinator reason was not projected as text: %q", got)
	}
}
