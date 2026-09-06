// Copyright 2024-2026 the original author or authors.
// Licensed under the Apache License, Version 2.0.

package httpapi

import (
	"encoding/json"
	"testing"
)

func TestProviderEventSummaryReadsAppServerItem(t *testing.T) {
	raw := json.RawMessage(`{"jsonrpc":"2.0","method":"item/completed","params":{"item":{"type":"agentMessage","text":"final answer"}}}`)
	if got := providerEventSummary(raw); got != "final answer" {
		t.Fatalf("summary=%q", got)
	}
}
